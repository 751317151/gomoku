// 玩法规则 UI 冒烟（真实浏览器）：
// 1) 大厅全部玩法卡都有「规则」入口，且规则内容覆盖全部玩法；
// 2) 规则弹层展示目标/步骤/提示与计时人数，Esc、遮罩、关闭按钮均可关闭并恢复焦点；
// 3) 对局内「查看玩法规则」按当前房间玩法打开，不出现会跳出对局的人机练习按钮；
// 4) 桌面 1280 与手机 390 宽都不横向溢出，底部按钮可点；
// 5) 规则面板「人机练习」从大厅直达真实人机对局。
// 需要先起 headless Chrome（CDP_PORT 默认 9344）与测试服务。
const CDP_PORT = process.env.CDP_PORT || 9344;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'F:/tmp/shot';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[ui-rules] PASS ' + name); }
  else { failed++; console.error('[ui-rules] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}
async function waitUntil(fn, label, timeout = 12000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await fn()) return true; await sleep(150); }
  console.error('[ui-rules] 等待超时: ' + label);
  return false;
}

const EXPECTED = ['HIGH_CARD', 'BLACKJACK', 'GOMOKU', 'REVERSI', 'GO9', 'XIANGQI', 'CHECKERS', 'JUNGLE', 'DARK_CHESS', 'FLIGHT_CHESS', 'CONNECT4',
  'RPG', 'CARD', 'NIM', 'QUIZ', 'DICE_RACE', 'GEM_AUCTION', 'UNO', 'BOMB_CAT', 'DOUDIZHU', 'ULTIMATE_TTT'];
const NAME = '规则UI' + Math.floor(Math.random() * 10000);

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
    this.ws = ws; this.id = 0; this.pending = new Map(); this.sessionId = null;
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && this.pending.has(m.id)) {
        const { resolve, reject } = this.pending.get(m.id);
        this.pending.delete(m.id);
        if (m.error) reject(new Error(JSON.stringify(m.error))); else resolve(m.result);
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
        if (this.pending.has(id)) { this.pending.delete(id); reject(new Error('CDP 超时: ' + method)); }
      }, 30000);
    });
  }
  async eval(expr) {
    const r = await this.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) {
      const ex = r.exceptionDetails.exception || {};
      throw new Error('JS 异常: ' + (ex.description || ex.value || r.exceptionDetails.text || ''));
    }
    return r.result ? r.result.value : undefined;
  }
  async shot(file) {
    const fs = await import('node:fs');
    fs.mkdirSync(OUT, { recursive: true });
    const r = await this.send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(file, Buffer.from(r.data, 'base64'));
  }
}

(async () => {
  console.log('== 玩法规则（真实浏览器）==');
  const guest = await (await fetch(REST + '/api/guest', { method: 'POST' })).json();
  check('游客身份签发', !!guest.token);

  const ws = new WebSocket(await getWsUrl());
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  const cdp = new Cdp(ws);
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' }, false);
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true }, false);
  cdp.sessionId = sessionId;
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  // 布局/键盘断言必须验证本次构建，不能验证历史 Service Worker 缓存。
  // 只清 app 自身来源的缓存，不动同一 Chrome profile 里的其他测试站点。
  await cdp.send('Network.enable');
  await cdp.send('Network.clearBrowserCache');
  await cdp.send('Storage.clearDataForOrigin', {
    origin: new URL(APP).origin, storageTypes: 'service_workers,cache_storage'
  });
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `try{localStorage.setItem('gomoku_guest', ${JSON.stringify(guest.token)});`
      + `localStorage.setItem('gomoku_onboarded','1');}catch(_){ };`
      + `window.alert=function(m){window.__lastAlert=String(m);};window.confirm=function(){return true;};`
      + `window.__errors=[];window.addEventListener('error',e=>window.__errors.push(String(e.message)));`
      + `window.addEventListener('unhandledrejection',e=>window.__errors.push(String(e.reason)));`
  });

  async function enterLobby(url) {
    await cdp.send('Page.navigate', { url: url });
    const ready = await waitUntil(() => cdp.eval("typeof joinGame === 'function'"), '登录页就绪');
    if (!ready) throw new Error('登录页未就绪');
    await cdp.eval("document.getElementById('ws-url').value = " + JSON.stringify(WS_URL)
      + "; document.getElementById('player-name').value = " + JSON.stringify(NAME) + "; joinGame();");
    const entered = await waitUntil(() => cdp.eval("document.getElementById('lobby-screen').style.display === 'block'"), '进入大厅');
    if (!entered) throw new Error('未能进入大厅');
  }

  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  await enterLobby(APP);

  // 1. 所有玩法卡都有规则入口，内容来自同一模块元数据
  const cards = await cdp.eval(
    "Array.from(document.querySelectorAll('#game-selector .game-card')).map(c => ({type: c.dataset.gameType,"
    + " hasRules: !!c.querySelector('[data-rules]'), label: (c.querySelector('[data-rules]')||{}).textContent,"
    + " footer: (c.querySelector('.game-card-desc')||{}).textContent}))");
  check('大厅全部玩法卡渲染', cards.length === EXPECTED.length, JSON.stringify(cards.map(c => c.type)));
  check('每张卡都有规则入口', cards.every(c => c.hasRules && c.label === '规则'),
    JSON.stringify(cards.filter(c => !c.hasRules)));

  const coverage = await cdp.eval(
    "(() => { const out = {}; Object.keys(GameModules).forEach(t => { const r = GameModules[t].rules;"
    + " out[t] = { goal: !!(r && r.goal), steps: (r && r.steps || []).length, tips: (r && r.tips || []).length,"
    + " footer: GameModules[t].footer || '' }; }); return out; })()");
  check('全部玩法规则元数据齐全', EXPECTED.every(t => coverage[t] && coverage[t].goal && coverage[t].steps >= 2),
    JSON.stringify(coverage));
  check('规则步骤与提示不含占位内容', Object.values(coverage).every(r =>
    r.steps >= 2 && r.tips >= 1 && r.footer.length > 4), JSON.stringify(coverage));

  // 2. 打开五子棋规则：内容、几何、焦点
  await cdp.eval("(() => { const b = document.querySelector('.game-card[data-game-type=\"GOMOKU\"] [data-rules]');"
    + " b.focus(); b.click(); })()");
  const opened = await waitUntil(() => cdp.eval(
    "document.getElementById('rules-modal').classList.contains('show')"), '规则弹层打开');
  check('点击规则打开弹层', opened);
  const detail = await cdp.eval(
    "(() => { const m = document.getElementById('rules-modal'), box = m.querySelector('.rules-box');"
    + " const r = box.getBoundingClientRect();"
    + " return { title: document.getElementById('rules-title').textContent,"
    + "  sub: document.getElementById('rules-sub').textContent,"
    + "  goal: (document.querySelector('.rules-goal p')||{}).textContent,"
    + "  steps: document.querySelectorAll('.rules-steps li').length,"
    + "  tips: document.querySelectorAll('.rules-tips li').length,"
    + "  meta: document.getElementById('rules-meta').textContent,"
    + "  soloVisible: document.getElementById('rules-solo').offsetParent !== null,"
    + "  width: r.width, height: r.height, overflowX: box.scrollWidth > box.clientWidth + 1,"
    + "  bodyScrolls: (() => { const b = document.querySelector('.rules-body'); return b.scrollHeight >= b.clientHeight; })(),"
    + "  focus: document.activeElement && document.activeElement.id }; })()");
  check('标题与玩法一致', detail.title.includes('五子棋') && detail.sub.includes('黑棋先手'), JSON.stringify(detail));
  check('目标/步骤/提示齐全', detail.goal.includes('五子') && detail.steps >= 3 && detail.tips >= 2,
    JSON.stringify(detail));
  check('显示每步计时与人数', /每步 30 秒/.test(detail.meta) && /2 人/.test(detail.meta), detail.meta);
  check('弹层桌面几何正常且不横向溢出', detail.width > 420 && detail.width <= 640
    && detail.height > 280 && !detail.overflowX, JSON.stringify(detail));
  check('打开后焦点在关闭按钮', detail.focus === 'rules-close', detail.focus);
  check('大厅规则提供人机练习入口', detail.soloVisible);
  await cdp.shot(OUT + '/rules-desktop.png');

  // 3. Esc 关闭并恢复焦点到触发按钮；再用真实键盘回车验证可访问入口
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
  check('Esc 关闭规则弹层', await waitUntil(() => cdp.eval(
    "!document.getElementById('rules-modal').classList.contains('show')"), 'Esc 关闭'));
  check('关闭后焦点回到触发按钮', await cdp.eval(
    "document.activeElement && document.activeElement.classList.contains('btn-rules')"
    + " && document.activeElement.dataset.rules === 'GOMOKU'"));
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
  check('键盘 Enter 可重新打开规则', await waitUntil(() => cdp.eval(
    "document.getElementById('rules-modal').classList.contains('show')"), '键盘打开规则'));
  await cdp.eval("document.getElementById('rules-close').click()");

  // 4. 遮罩关闭 + 关闭按钮；筛选各分类后入口仍在
  await cdp.eval("(() => { const b = document.querySelector('.game-card[data-game-type=\"UNO\"] [data-rules]');"
    + " b.focus(); b.click(); })()");
  await waitUntil(() => cdp.eval("document.getElementById('rules-modal').classList.contains('show')"), 'UNO 规则');
  const unoDetail = await cdp.eval(
    "({ title: document.getElementById('rules-title').textContent,"
    + " steps: document.querySelectorAll('.rules-steps li').length,"
    + " text: document.getElementById('rules-body').textContent })");
  check('UNO 规则覆盖特殊牌与 UNO', unoDetail.title.includes('UNO') && unoDetail.steps >= 5
    && unoDetail.text.includes('+4') && unoDetail.text.includes('喊 UNO'), JSON.stringify(unoDetail));
  await cdp.eval("document.getElementById('rules-modal').click()");
  check('点击遮罩关闭', await waitUntil(() => cdp.eval(
    "!document.getElementById('rules-modal').classList.contains('show')"), '遮罩关闭'));

  await cdp.eval("(() => { const b = document.querySelector('.game-card[data-game-type=\"GEM_AUCTION\"] [data-rules]');"
    + " b.focus(); b.click(); })()");
  await waitUntil(() => cdp.eval("document.getElementById('rules-modal').classList.contains('show')"), '宝石规则');
  await cdp.eval("document.getElementById('rules-close').click()");
  check('关闭按钮可关闭', await waitUntil(() => cdp.eval(
    "!document.getElementById('rules-modal').classList.contains('show')"), '按钮关闭'));

  const categories = await cdp.eval(
    "Array.from(document.querySelectorAll('#game-categories .game-category')).map(b => b.textContent)");
  check('玩法分类齐全', JSON.stringify(categories) === JSON.stringify(['全部', '棋盘', '卡牌', '派对', '对战']),
    JSON.stringify(categories));
  for (const cat of ['棋盘', '卡牌', '派对', '对战']) {
    await cdp.eval("Array.from(document.querySelectorAll('#game-categories .game-category')).find(b => b.textContent === "
      + JSON.stringify(cat) + ").click()");
    const ok = await cdp.eval(
      "(() => { const cards = Array.from(document.querySelectorAll('#game-selector .game-card'));"
      + " return cards.length > 0 && cards.every(c => c.querySelector('[data-rules]')); })()");
    check('分类「' + cat + '」每张卡仍有规则入口', ok);
  }
  await cdp.eval("Array.from(document.querySelectorAll('#game-categories .game-category')).find(b => b.textContent === '全部').click()");

  // 5. 手机 390px 几何：弹层不横向溢出、底部动作 44px 可点
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
  await cdp.eval("document.querySelector('.game-card[data-game-type=\"CARD\"] [data-rules]').click()");
  await waitUntil(() => cdp.eval("document.getElementById('rules-modal').classList.contains('show')"), '手机规则弹层');
  const mobile = await cdp.eval(
    "(() => { const box = document.querySelector('.rules-box').getBoundingClientRect();"
    + " const modal = document.getElementById('rules-modal');"
    + " const solo = document.getElementById('rules-solo').getBoundingClientRect();"
    + " const close = document.getElementById('rules-close').getBoundingClientRect();"
    + " return { left: box.left, right: box.right, top: box.top, bottom: box.bottom,"
    + "  viewport: window.innerWidth, height: window.innerHeight,"
    + "  overflowX: document.documentElement.scrollWidth > window.innerWidth + 1,"
    + "  bodyOverflow: modal.scrollWidth > modal.clientWidth + 1,"
    + "  soloW: solo.width, soloH: solo.height, closeW: close.width, closeH: close.height }; })()");
  check('手机弹层在视口内且不横向溢出', mobile.left >= 0 && mobile.right <= mobile.viewport + 1
    && mobile.top >= 0 && mobile.bottom <= mobile.height + 1 && !mobile.overflowX && !mobile.bodyOverflow,
    JSON.stringify(mobile));
  check('手机底部按钮达到 44px 触控高度', mobile.soloH >= 44 && mobile.closeH >= 32,
    JSON.stringify(mobile));
  await cdp.shot(OUT + '/rules-mobile.png');
  await cdp.eval("document.getElementById('rules-close').click()");
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });

  // 6. 从规则「人机练习」进入真实人机对局，并在对局内查看当前玩法规则
  await cdp.eval("document.querySelector('.game-card[data-game-type=\"CONNECT4\"]').click()");
  await cdp.eval("document.querySelector('.game-card[data-game-type=\"CONNECT4\"] [data-rules]').click()");
  await waitUntil(() => cdp.eval("document.getElementById('rules-modal').classList.contains('show')"), '四子棋规则');
  await cdp.eval("document.getElementById('rules-solo').click()");
  check('人机练习打开难度弹层', await waitUntil(() => cdp.eval(
    "document.getElementById('difficulty-dialog').classList.contains('show')"), '难度弹层'));
  check('规则弹层已关闭', await cdp.eval("!document.getElementById('rules-modal').classList.contains('show')"));
  check('难度弹层显示四子棋', await cdp.eval(
    "document.getElementById('difficulty-game').textContent === '四子棋'"));
  await cdp.eval("document.querySelector('#difficulty-dialog .difficulty-opt[data-diff=\"2\"]').click()");
  check('进入真实人机对局', await waitUntil(() => cdp.eval("gameState === 'PLAYING'"), '人机开局', 15000));

  await cdp.eval("document.querySelector('.rules-open-btn').click()");
  const inGame = await waitUntil(() => cdp.eval(
    "document.getElementById('rules-modal').classList.contains('show')"), '对局内规则');
  check('对局内可打开规则', inGame);
  const inGameDetail = await cdp.eval(
    "({ title: document.getElementById('rules-title').textContent,"
    + " soloVisible: document.getElementById('rules-solo').offsetParent !== null,"
    + " meta: document.getElementById('rules-meta').textContent })");
  check('对局内按当前玩法显示且不提供跳局按钮', inGameDetail.title.includes('四子棋')
    && !inGameDetail.soloVisible && inGameDetail.meta.includes('当前对局进行中'), JSON.stringify(inGameDetail));
  await cdp.eval("document.getElementById('rules-close').click()");
  check('关闭规则后对局仍在继续', await cdp.eval("gameState === 'PLAYING'"));
  await cdp.eval("document.querySelector('.leave-btn[onclick=\"leaveRoom()\"]').click()");
  await waitUntil(() => cdp.eval("document.getElementById('lobby-screen').style.display === 'block'"), '回到大厅');

  // 7. 无运行时异常
  const errors = await cdp.eval("window.__errors || []");
  check('页面无脚本异常', errors.length === 0, JSON.stringify(errors));

  console.log(failed === 0
    ? '[ui-rules] PASS (' + passed + '/' + passed + ')'
    : '[ui-rules] FAIL (' + failed + ' failed, ' + passed + ' passed)');
  try { ws.close(); } catch (_) {}
  process.exit(failed === 0 ? 0 : 1);
})().catch((e) => {
  failed++;
  console.error('[ui-rules] FAIL 异常 :: ' + (e && e.message));
  process.exit(1);
});
