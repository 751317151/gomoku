// 用 Chrome DevTools Protocol 驱动页面：登录 → 打完一局 → 截图战绩面板与复盘弹窗
// 目的：确认新增的战绩/复盘 UI 在真实浏览器里真的渲染出来（不是只有接口通）
const CDP_PORT = process.env.CDP_PORT || 9222;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const OUT_DIR = process.env.OUT_DIR || 'F:/tmp/shot';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 轮询直到条件成立；用于替代固定 sleep，冷启动/慢请求下更稳定 */
async function waitUntil(check, label, timeoutMs = 8000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await check()) return;
    await sleep(200);
  }
  throw new Error('等待超时: ' + label);
}

async function getWsUrl() {
  for (let i = 0; i < 40; i++) {
    try {
      const r = await fetch('http://127.0.0.1:' + CDP_PORT + '/json/version');
      const j = await r.json();
      if (j.webSocketDebuggerUrl) return j.webSocketDebuggerUrl;
    } catch {}
    await sleep(250);
  }
  throw new Error('Chrome 调试端口未就绪');
}

class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    this.sessionId = null;
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && this.pending.has(m.id)) {
        const { resolve, reject } = this.pending.get(m.id);
        this.pending.delete(m.id);
        if (m.error) reject(new Error(JSON.stringify(m.error)));
        else resolve(m.result);
      }
    };
  }
  send(method, params, useSession = true) {
    const id = ++this.id;
    const payload = { id, method, params: params || {} };
    if (useSession && this.sessionId) payload.sessionId = this.sessionId;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify(payload));
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error('CDP 超时: ' + method));
        }
      }, 30000);
    });
  }
  async eval(expr) {
    // 轮询辅助函数可能传入 Promise，统一等待 Promise，避免拿到包装对象。
    // 一个 Promise 包装对象，条件判断会永远为真或永远为假。
    const r = await this.send('Runtime.evaluate', {
      expression: expr, returnByValue: true, awaitPromise: true
    });
    if (r.exceptionDetails) {
      throw new Error('JS 异常: ' + (r.exceptionDetails.text || '') + ' ' +
        JSON.stringify(r.exceptionDetails.exception && r.exceptionDetails.exception.description));
    }
    return r.result ? r.result.value : undefined;
  }

  /** 执行 async 表达式并等待结果（统一走 awaitPromise） */
  async evalAsync(expr) {
    return this.eval(expr);
  }
  async shot(file) {
    const r = await this.send('Page.captureScreenshot', { format: 'png' });
    const fs = await import('node:fs');
    fs.writeFileSync(file, Buffer.from(r.data, 'base64'));
    return file;
  }
}

(async () => {
  const url = await getWsUrl();
  const ws = new WebSocket(url);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  const cdp = new Cdp(ws);

  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' }, false);
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true }, false);
  cdp.sessionId = sessionId;

  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Page.navigate', { url: 'about:blank' }).catch(() => {});
  // 页面里大量使用 alert，原生 alert 会阻塞 JS 执行、把后续 CDP 调用拖到超时。
  // 这里提前把 alert 换成日志，弹窗内容进 errors 数组，既能发现异常又不卡住。
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `
      window.__alertLog = [];
      window.alert = function (m) { window.__alertLog.push(String(m)); };
      // 记录页面发出的请求，验证套牌筛选不是只改了 UI 状态
      window.__fetchLog = [];
      const __origFetch = window.fetch.bind(window);
      window.fetch = function (input, init) {
        const url = typeof input === 'string' ? input : (input && input.url);
        window.__fetchLog.push(String(url || ''));
        return __origFetch(input, init);
      };
    `
  }).catch(() => {});
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 1400, height: 1150, deviceScaleFactor: 1, mobile: false
  });

  // 收集控制台错误，便于判断是否有 JS 异常；同时自动关掉 alert 框，
  // 否则 alert 会阻塞页面 JS，后续 Runtime.evaluate 会一直挂在超时上。
  const errors = [];
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data);
    if (m.method === 'Runtime.exceptionThrown') {
      errors.push(m.params.exceptionDetails.text + ' ' +
        (m.params.exceptionDetails.exception?.description || ''));
    }
    if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
      errors.push('console.error: ' + m.params.args.map(a => a.value || a.description).join(' '));
    }
    if (m.method === 'Page.javascriptDialogOpening') {
      errors.push('alert/confirm 弹出: ' + m.params.message);
      cdp.send('Page.handleJavaScriptDialog', { accept: true }, false).catch(() => {});
    }
  });

  await cdp.send('Page.navigate', { url: APP });
  // 不能只固定等 1.5s：Chrome 刚启动时页面脚本可能还没执行完，
  // 紧接着 evaluate 调 joinGame() 会报 "joinGame is not defined"。
  // 改为轮询「脚本就绪」信号，冷启动也能稳定通过。
  let pageReady = false;
  for (let i = 0; i < 40 && !pageReady; i++) {
    await sleep(250);
    try {
      pageReady = await cdp.eval(
        `typeof joinGame === 'function' && !!document.getElementById('ws-url')`);
    } catch {}
  }
  if (!pageReady) throw new Error('页面脚本未就绪（joinGame 未定义）');

  // 登录。注意：页面按 index.html 里 data-port="9001" 拼 WS 地址，
  // 服务是用 --netty.port=19001 起的，所以必须显式写 #ws-url，否则连不上、停在登录页。
  const name = '截图测试' + Math.floor(Math.random() * 900 + 100);
  const wsUrl = process.env.WS_URL || 'ws://127.0.0.1:19001';
  await cdp.eval(`
    document.getElementById('ws-url').value = ${JSON.stringify(wsUrl)};
    document.getElementById('player-name').value = ${JSON.stringify(name)};
    joinGame();
  `);
  await sleep(3000);

  const lobbyVisible = await cdp.eval(`document.getElementById('lobby-screen').style.display !== 'none'`);
  console.log('[ui] 已进入大厅:', lobbyVisible);

  // 检查战绩面板元素是否渲染
  const panel = await cdp.eval(`JSON.stringify({
    win: document.getElementById('my-win').textContent,
    lose: document.getElementById('my-lose').textContent,
    rate: document.getElementById('my-rate').textContent,
    elo: document.getElementById('my-elo').textContent,
    recent: document.getElementById('recent-games-list').textContent.slice(0, 40)
  })`);
  console.log('[ui] 战绩面板:', panel);

  await cdp.shot(OUT_DIR + '/lobby-stats.png');
  console.log('[ui] 截图: lobby-stats.png');

  // ===== 卡牌对局记录 · 套牌筛选 =====
  // 默认页签是五子棋，筛选器必须隐藏；切到卡牌后才出现，且非卡牌页签会自动复位。
  const deckHiddenByDefault = await cdp.eval(
    `getComputedStyle(document.getElementById('recent-deck-filter')).display === 'none'`);
  if (!deckHiddenByDefault) throw new Error('默认页签下套牌筛选器应隐藏');

  await cdp.eval(`(() => {
    const btn = Array.from(document.querySelectorAll('.lb-tab'))
      .find(b => b.textContent.includes('卡牌'));
    if (!btn) throw new Error('找不到卡牌页签');
    btn.click();
  })()`);
  await waitUntil(
    () => cdp.eval(`getComputedStyle(document.getElementById('recent-deck-filter')).display !== 'none'`),
    '卡牌页签显示套牌筛选器');

  await cdp.eval(`window.__fetchLog = []; onRecentDeckChange('AGGRO')`);
  await waitUntil(
    () => cdp.eval(`window.__fetchLog.some(u => u.includes('/api/games?') &&
      u.includes('gameType=CARD') && u.includes('deck=AGGRO'))`),
    '最近对局请求携带 deck=AGGRO');
  const aggroApi = await cdp.evalAsync(
    `(async () => await (await fetch('/api/games?limit=15&gameType=CARD&deck=AGGRO')).json())()`);
  // 不能只看「加载中」消失：切换筛选时旧列表可能还在 DOM 里，
  // 必须等页面列表真正变成这次请求对应的结果。
  if (aggroApi.length === 0) {
    await waitUntil(
      () => cdp.eval(`document.getElementById('recent-games-list').textContent.includes('快攻套牌暂无对局记录')`),
      '快攻空状态渲染');
  } else {
    await waitUntil(
      () => cdp.eval(`(() => {
        const rows = Array.from(document.querySelectorAll('#recent-games-list .record-row'));
        return rows.length === ${aggroApi.length} && rows.every(r => r.textContent.includes('快攻'));
      })()`),
      'AGGRO 列表渲染完成');
  }

  const cardState = await cdp.eval(`JSON.stringify({
    selectedRecordDeck,
    rows: Array.from(document.querySelectorAll('#recent-games-list .record-row')).map(r => r.textContent),
    empty: document.getElementById('recent-games-list').textContent
  })`);
  const parsedCardState = JSON.parse(cardState);
  if (parsedCardState.selectedRecordDeck !== 'AGGRO') {
    throw new Error('selectedRecordDeck 应为 AGGRO，实际: ' + parsedCardState.selectedRecordDeck);
  }
  if (aggroApi.length === 0) {
    if (!parsedCardState.empty.includes('快攻套牌暂无对局记录')) {
      throw new Error('无快攻记录时应显示可解释的空状态，实际: ' + parsedCardState.empty);
    }
  } else {
    if (parsedCardState.rows.length !== aggroApi.length) {
      throw new Error('UI 行数与 deck=AGGRO 返回条数不一致: ' +
        parsedCardState.rows.length + ' vs ' + aggroApi.length);
    }
    if (!aggroApi.every(g => (g.participants || []).some(p => p.deck === 'AGGRO'))) {
      throw new Error('deck=AGGRO 返回了没有快攻套牌参与的对局');
    }
    if (!parsedCardState.rows.every(t => t.includes('快攻'))) {
      throw new Error('UI 行未展示快攻套牌信息');
    }
  }
  console.log('[ui] 套牌筛选: PASS（AGGRO / ' + aggroApi.length + ' 条）');

  // 对局列表跟排行榜页签联动：默认停在五子棋，而测试产生的多为四子棋/象棋，
  // 所以先切到有记录的游戏，再验证列表与复盘弹窗。
  const gtype = process.env.GAME_TYPE || 'CONNECT4';
  await cdp.eval(`(() => {
    const btn = Array.from(document.querySelectorAll('.lb-tab'))
      .find(b => b.textContent.includes(${JSON.stringify(gtype === 'CONNECT4' ? '四子棋' : gtype)}));
    if (btn) btn.click();
  })()`);
  await sleep(1500);
  const deckStateAfterLeave = await cdp.eval(`JSON.stringify({
    hidden: getComputedStyle(document.getElementById('recent-deck-filter')).display === 'none',
    deck: selectedRecordDeck
  })`);
  const leaveState = JSON.parse(deckStateAfterLeave);
  if (!leaveState.hidden) throw new Error('切回非卡牌页签后套牌筛选器应隐藏');
  if (leaveState.deck !== '') throw new Error('切回非卡牌页签后套牌筛选应复位');
  console.log('[ui] 已切到游戏页签:', gtype,
    '| 列表项数:', await cdp.eval(`document.querySelectorAll('#recent-games-list .record-row').length`));

  // 打开第一局的复盘弹窗（如果有记录）
  const rowCount = await cdp.eval(`document.querySelectorAll('#recent-games-list .record-row').length`);
  const hasRecord = rowCount > 0;
  if (hasRecord) {
    // 挑一条【有棋谱】的记录：0 手记录（步时判负那种）会走「棋谱未存档」分支，
    // 用它验证弹窗只会得到假失败。
    const firstId = await cdp.evalAsync(`(async () => {
      const r = await fetch('/api/games?limit=20&gameType=' + selectedLbType);
      const list = await r.json();
      const withMoves = list.filter(g => g.moveCount > 0);
      return withMoves.length ? withMoves[0].id : '';
    })()`);
    console.log('[ui] 复盘目标记录:', firstId || '(无有棋谱的记录)');
    if (!firstId) {
      console.log('[ui] 跳过复盘弹窗检查');
      console.log('[ui] 控制台错误:', errors.length ? errors : '无');
      ws.close();
      process.exit(0);
    }

    const opened = await cdp.evalAsync(`(async () => {
      try {
        await openRecordReplay(${JSON.stringify(firstId)}, 'CONNECT4');
        return 'ok';
      } catch (e) {
        return 'ERR:' + String(e && e.message || e);
      }
    })()`);
    if (opened !== 'ok') console.log('[ui] 打开复盘异常:', opened);
    await sleep(900);

    const modalOpen = await cdp.eval(`document.getElementById('replay-modal').classList.contains('show')`);
    const stepText = await cdp.eval(`document.getElementById('rm-step-text').textContent`);
    const subText = await cdp.eval(`document.getElementById('rm-sub').textContent`);
    console.log('[ui] 复盘弹窗打开:', modalOpen, '| 步数:', stepText);
    console.log('[ui] 复盘副标题:', subText);

    // 拖到中段，确认盘面真的重建了
    await cdp.eval(`(() => {
      const s = document.getElementById('rm-slider');
      s.value = Math.floor(s.max / 2);
      rmSeek(s.value);
    })()`);
    await sleep(500);
    console.log('[ui] 拖动后步数:', await cdp.eval(`document.getElementById('rm-step-text').textContent`));

    await cdp.shot(OUT_DIR + '/replay-modal.png');
    console.log('[ui] 截图: replay-modal.png');

    if (!modalOpen) console.log('[ui] 问题: 复盘弹窗未打开');
    if (stepText === '0/0') console.log('[ui] 问题: 复盘步数为 0');
  } else {
    console.log('[ui] 最近对局为空，跳过复盘弹窗检查');
  }

  console.log('[ui] 控制台错误:', errors.length ? errors : '无');
  ws.close();
  process.exit(0);
})().catch((e) => {
  console.error('[ui] FAIL:', e.message);
  process.exit(1);
});
