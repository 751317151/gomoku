// 回合制 RPG 面板的真实浏览器验证（CDP 驱动 Chrome）
// 目的：接口通不代表界面能看。这里要确认
//   1. 选中「回合制对战」后画布隐藏、HTML 面板显示
//   2. 血条/蓝条/技能栏/战斗日志都渲染出内容
//   3. 轮到自己时技能可点，点下去真的能打完整局
//   4. 终局浮层出现，且复盘弹窗对 RPG 走文字战报（不画空棋盘）
//
// 前置：Chrome 以 --remote-debugging-port=9222 启动；服务在 19000/19001
const CDP_PORT = process.env.CDP_PORT || 9222;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const OUT_DIR = process.env.OUT_DIR || 'F:/tmp/shot';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const problems = [];

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
  async eval(expr, awaitPromise = false) {
    const r = await this.send('Runtime.evaluate', {
      expression: expr, returnByValue: true, awaitPromise
    });
    if (r.exceptionDetails) {
      throw new Error('JS 异常: ' + (r.exceptionDetails.text || '') + ' ' +
        JSON.stringify(r.exceptionDetails.exception && r.exceptionDetails.exception.description));
    }
    return r.result ? r.result.value : undefined;
  }
  async evalAsync(expr) { return this.eval(expr, true); }
  async shot(file) {
    const r = await this.send('Page.captureScreenshot', { format: 'png' });
    const fs = await import('node:fs');
    fs.mkdirSync(OUT_DIR, { recursive: true });
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
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 1400, height: 1000, deviceScaleFactor: 1, mobile: false
  });

  // 页面里用了 alert；原生 alert 会阻塞 JS 执行，把后续 CDP 调用拖到超时
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `window.__alertLog = []; window.alert = function (m) { window.__alertLog.push(String(m)); };`
  }).catch(() => {});

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
  await sleep(1600);

  const name = '对战UI' + Math.floor(Math.random() * 900 + 100);
  await cdp.eval(`
    document.getElementById('ws-url').value = ${JSON.stringify(WS_URL)};
    document.getElementById('player-name').value = ${JSON.stringify(name)};
    joinGame();
  `);
  await sleep(3000);

  const lobbyVisible = await cdp.eval(`document.getElementById('lobby-screen').style.display !== 'none'`);
  console.log('[ui] 已进入大厅:', lobbyVisible);
  if (!lobbyVisible) problems.push('没有进入大厅（WS 未连上？）');

  // 大厅里应当出现「回合制对战」卡片
  const cardNames = await cdp.eval(`
    Array.from(document.querySelectorAll('.game-card .game-card-name')).map(e => e.textContent).join(',')
  `);
  console.log('[ui] 大厅游戏卡片:', cardNames);
  if (!cardNames.includes('回合制对战')) problems.push('大厅缺少回合制对战卡片');

  // 选中 RPG 并开局
  await cdp.eval(`(() => {
    selectedGameType = 'RPG';
    buildGameSelector();
    createRoom();
  })()`);
  await sleep(2200);

  // 加电脑，让对局能自己跑完
  await cdp.eval(`addAI()`);
  await sleep(2500);

  const layout = await cdp.eval(`JSON.stringify({
    canvas: getComputedStyle(document.getElementById('board-canvas')).display,
    panel: getComputedStyle(document.getElementById('rpg-panel')).display,
    gameType: currentGameType,
    state: gameState
  })`);
  console.log('[ui] 布局:', layout);
  const lay = JSON.parse(layout);
  if (lay.canvas !== 'none') problems.push('RPG 局里画布没有隐藏');
  if (lay.panel === 'none') problems.push('RPG 面板没有显示');
  if (lay.gameType !== 'RPG') problems.push('当前游戏类型不是 RPG: ' + lay.gameType);

  const panelText = await cdp.eval(`JSON.stringify({
    round: document.getElementById('rpg-round').textContent,
    f1: document.getElementById('rpg-fighter-1').textContent.replace(/\\s+/g, ' ').trim(),
    f2: document.getElementById('rpg-fighter-2').textContent.replace(/\\s+/g, ' ').trim(),
    actions: document.querySelectorAll('#rpg-actions .rpg-action').length,
    logLines: document.querySelectorAll('#rpg-log div').length
  })`);
  console.log('[ui] 面板内容:', panelText);
  const p = JSON.parse(panelText);
  if (p.actions !== 6) problems.push('技能栏应有 6 个行动，实际 ' + p.actions);
  if (!/HP/.test(p.f1) || !/MP/.test(p.f1)) problems.push('玩家卡缺少 HP/MP 显示');
  if (p.logLines === 0) problems.push('战斗日志为空');

  // 几何检测：两张玩家卡不能重叠，技能按钮不能溢出面板
  const geo = await cdp.eval(`(() => {
    const r1 = document.getElementById('rpg-fighter-1').getBoundingClientRect();
    const r2 = document.getElementById('rpg-fighter-2').getBoundingClientRect();
    const panel = document.getElementById('rpg-panel').getBoundingClientRect();
    const btns = Array.from(document.querySelectorAll('#rpg-actions .rpg-action'));
    const overflow = btns.filter(b => {
      const r = b.getBoundingClientRect();
      return r.right > panel.right + 1 || r.left < panel.left - 1;
    }).length;
    return JSON.stringify({
      overlap: !(r1.right <= r2.left || r2.right <= r1.left),
      panelW: Math.round(panel.width),
      overflow,
      btnCount: btns.length
    });
  })()`);
  console.log('[ui] 几何检测:', geo);
  const g = JSON.parse(geo);
  if (g.overlap) problems.push('两张玩家卡重叠');
  if (g.overflow > 0) problems.push(g.overflow + ' 个技能按钮溢出面板');
  if (g.panelW < 400) problems.push('面板宽度异常: ' + g.panelW);

  await cdp.shot(OUT_DIR + '/rpg-battle.png');
  console.log('[ui] 截图: rpg-battle.png');

  // 打完整局：轮到自己就点一个可用技能
  let rounds = 0;
  let clicked = 0;
  while (rounds++ < 120) {
    const st = await cdp.eval(`JSON.stringify({
      over: gameState === 'OVER',
      myTurn: currentTurn === myStone,
      enabled: document.querySelectorAll('#rpg-actions .rpg-action:not(:disabled)').length
    })`);
    const s = JSON.parse(st);
    if (s.over) break;
    if (s.myTurn && s.enabled > 0) {
      await cdp.eval(`(() => {
        const b = document.querySelector('#rpg-actions .rpg-action:not(:disabled)');
        if (b) b.click();
      })()`);
      clicked++;
    }
    await sleep(700);
  }

  const overState = await cdp.eval(`JSON.stringify({
    state: gameState,
    overlay: document.getElementById('gameover-overlay').classList.contains('show'),
    result: document.getElementById('gameover-result').textContent,
    sub: document.getElementById('gameover-sub').textContent,
    moveCount: document.getElementById('move-count').textContent
  })`);
  console.log('[ui] 终局状态:', overState);
  const ov = JSON.parse(overState);
  console.log('[ui] 点击技能次数:', clicked);
  if (ov.state !== 'OVER') problems.push('对局没有打完（state=' + ov.state + '）');
  if (!ov.overlay) problems.push('终局浮层没有出现');
  if (!ov.result || !/胜|败|平/.test(ov.result)) {
    problems.push('终局文案异常: ' + ov.result);
  }
  if (!ov.sub || ov.sub.indexOf('生命') < 0) {
    problems.push('终局副标题缺少血量说明: ' + ov.sub);
  }

  await cdp.shot(OUT_DIR + '/rpg-gameover.png');
  console.log('[ui] 截图: rpg-gameover.png');

  // 复盘：RPG 应走文字战报，不画空棋盘
  await sleep(1200);
  const recId = await cdp.evalAsync(`(async () => {
    const r = await fetch('/api/games?limit=20&gameType=RPG');
    const list = await r.json();
    return list.length ? list[0].id : '';
  })()`);
  console.log('[ui] RPG 复盘目标记录:', recId || '(无)');
  if (!recId) {
    problems.push('没有找到 RPG 对局记录（存档未生效）');
  } else {
    const opened = await cdp.evalAsync(`(async () => {
      try { await openRecordReplay(${JSON.stringify(recId)}, 'RPG'); return 'ok'; }
      catch (e) { return 'ERR:' + String(e && e.message || e); }
    })()`);
    if (opened !== 'ok') problems.push('打开 RPG 复盘异常: ' + opened);
    await sleep(800);

    const rm = await cdp.eval(`JSON.stringify({
      open: document.getElementById('replay-modal').classList.contains('show'),
      canvas: getComputedStyle(document.getElementById('rm-canvas')).display,
      log: getComputedStyle(document.getElementById('rm-rpg-log')).display,
      lines: document.querySelectorAll('#rm-rpg-log div').length,
      first: (document.querySelector('#rm-rpg-log div') || {}).textContent || ''
    })`);
    console.log('[ui] 复盘弹窗:', rm);
    const r = JSON.parse(rm);
    if (!r.open) problems.push('RPG 复盘弹窗没打开');
    if (r.canvas !== 'none') problems.push('RPG 复盘仍在显示画布');
    if (r.log === 'none') problems.push('RPG 复盘没有显示文字战报');
    if (r.lines === 0) problems.push('RPG 复盘战报为空');
    if (r.first && r.first.indexOf('方') < 0) {
      problems.push('RPG 复盘首行不像战报: ' + r.first);
    }
    await cdp.shot(OUT_DIR + '/rpg-replay.png');
    console.log('[ui] 截图: rpg-replay.png');
  }

  console.log('[ui] 控制台错误:', errors.length ? errors.slice(0, 5) : '无');
  if (errors.length) problems.push('控制台有 ' + errors.length + ' 条异常');

  console.log('[ui] 问题:', problems.length ? problems : '无');
  console.log(problems.length === 0 ? '[ui] PASS' : '[ui] FAIL');
  ws.close();
  process.exitCode = problems.length === 0 ? 0 : 1;
})();
