// 西洋跳棋 UI 冒烟（真实浏览器）：
// 1) 大厅能看到并筛选出跳棋；
// 2) 规则弹层内容正确；
// 3) 通过真实鼠标坐标完成「选子 → 点目标」并收到服务端落子；
// 4) 强制吃子提示与合法落点提示可见；
// 5) 390px 手机布局无横向溢出，画布可点击。
// 需要服务端 19000/19001 与 headless Chrome CDP（默认 9347）。
const CDP_PORT = process.env.CDP_PORT || 9347;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'target/checkers-validation/shots';
const sleep = ms => new Promise(r => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[ui-checkers] PASS ' + name); }
  else { failed++; console.error('[ui-checkers] FAIL ' + name + (detail ? ' :: ' + JSON.stringify(detail) : '')); }
}
async function until(fn, label, timeout = 12000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await fn()) return true; await sleep(120); }
  throw new Error('等待超时: ' + label);
}

class Cdp {
  constructor(ws) {
    this.ws = ws; this.id = 0; this.pending = new Map(); this.errors = [];
    ws.onmessage = ev => {
      const m = JSON.parse(ev.data);
      if (m.method === 'Runtime.exceptionThrown') this.errors.push(m.params.exceptionDetails.text);
      if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
        this.errors.push(m.params.args.map(a => a.value || a.description).join(' '));
      }
      const p = this.pending.get(m.id);
      if (!p) return;
      clearTimeout(p.timer); this.pending.delete(m.id);
      m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result);
    };
  }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((resolve, reject) => {
      ws.onopen = resolve; ws.onerror = () => reject(new Error('Chrome CDP 连接失败'));
    });
    return new Cdp(ws);
  }
  send(method, params = {}, session = true) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('CDP 超时: ' + method)); }, 30000);
      this.pending.set(id, { resolve, reject, timer });
      this.ws.send(JSON.stringify({ id, method, params,
        ...(session && this.sessionId ? { sessionId: this.sessionId } : {}) }));
    });
  }
  async eval(expr) {
    const r = await this.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    return r.result.value;
  }
  async shot(name) {
    const fs = await import('node:fs');
    fs.mkdirSync(OUT, { recursive: true });
    const r = await this.send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(OUT + '/' + name + '.png', Buffer.from(r.data, 'base64'));
  }
}

(async () => {
  console.log('== 西洋跳棋 UI ==');
  const guest = await (await fetch(REST + '/api/guest', { method: 'POST' })).json();
  const info = await (await fetch('http://127.0.0.1:' + CDP_PORT + '/json/version')).json();
  const cdp = await Cdp.connect(info.webSocketDebuggerUrl);
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' }, false);
  cdp.sessionId = (await cdp.send('Target.attachToTarget', { targetId, flatten: true }, false)).sessionId;
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Network.enable');
  await cdp.send('Network.clearBrowserCache');
  await cdp.send('Storage.clearDataForOrigin', {
    origin: new URL(APP).origin, storageTypes: 'service_workers,cache_storage'
  });
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `localStorage.setItem('gomoku_guest',${JSON.stringify(guest.token)});`
      + `localStorage.setItem('gomoku_onboarded','1');window.alert=m=>{window.__alert=String(m);};`
      + `window.confirm=()=>true;window.__errors=[];`
      + `window.addEventListener('error',e=>window.__errors.push(String(e.message)));`
      + `window.addEventListener('unhandledrejection',e=>window.__errors.push(String(e.reason)));`
  });
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 1280, height: 900, deviceScaleFactor: 1, mobile: false
  });
  await cdp.send('Page.navigate', { url: APP });
  await until(() => cdp.eval("typeof joinGame==='function'"), '页面加载');
  await cdp.eval("document.getElementById('ws-url').value=" + JSON.stringify(WS_URL)
    + ";document.getElementById('player-name').value='跳棋UI';joinGame();");
  await until(() => cdp.eval("gameState==='LOBBY' && !!guestToken && ws.readyState===1"), '进入大厅');

  // 分类筛选必须包含跳棋。
  await cdp.eval("Array.from(document.querySelectorAll('#game-categories .game-category'))"
    + ".find(b=>b.textContent==='棋盘').click()");
  const boardCards = await cdp.eval("Array.from(document.querySelectorAll('#game-selector .game-card'))"
    + ".map(c=>c.dataset.gameType)");
  check('棋盘分类包含西洋跳棋', boardCards.includes('CHECKERS'), boardCards);
  check('跳棋卡片有独立名称与规则入口', await cdp.eval(
    "!!document.querySelector('.game-card[data-game-type=\"CHECKERS\"] [data-rules]')"
    + " && document.querySelector('.game-card[data-game-type=\"CHECKERS\"]').textContent.includes('西洋跳棋')"));

  // 规则：强制吃子与升王必须能读懂。
  await cdp.eval("document.querySelector('.game-card[data-game-type=\"CHECKERS\"] [data-rules]').click()");
  await until(() => cdp.eval("document.getElementById('rules-modal').classList.contains('show')"), '跳棋规则');
  const rules = await cdp.eval("({title:document.getElementById('rules-title').textContent,"
    + "text:document.getElementById('rules-body').textContent,"
    + "meta:document.getElementById('rules-meta').textContent})");
  check('规则标题与玩法一致', rules.title.includes('西洋跳棋'), rules);
  check('规则说明强制吃子、连吃与升王', rules.text.includes('必须吃') && rules.text.includes('连吃')
    && rules.text.includes('升为王'), rules.text);
  check('规则显示 45 秒步时与 2 人', rules.meta.includes('45 秒') && rules.meta.includes('2 人'), rules.meta);
  await cdp.shot('checkers-rules');
  await cdp.eval("document.getElementById('rules-close').click()");

  // 真实人机开局，画布上完成两阶段选子/走子。
  await cdp.eval("document.querySelector('.game-card[data-game-type=\"CHECKERS\"]').click()");
  await cdp.eval("document.querySelector('.game-card[data-game-type=\"CHECKERS\"] [data-solo]').click()");
  await until(() => cdp.eval("document.getElementById('difficulty-dialog').classList.contains('show')"), '难度弹层');
  await cdp.eval("document.querySelector('#difficulty-dialog .difficulty-opt[data-diff=\"2\"]').click()");
  await until(() => cdp.eval("gameState==='PLAYING' && currentGameType==='CHECKERS'"), '跳棋人机开局', 15000);
  const initial = await cdp.eval("({myStone,currentTurn,gameState,rect:(()=>{const r=canvas.getBoundingClientRect();"
    + "return {left:r.left,top:r.top,width:r.width,height:r.height};})(),cell:GameModules.CHECKERS.cell,"
    + "pad:GameModules.CHECKERS.padding,canvasW:canvas.width,canvasH:canvas.height})");
  console.log('[ui-checkers] initial', JSON.stringify(initial));
  check('开局棋盘 12+12 枚棋子', await cdp.eval(
    "board.flat().filter(v=>Math.floor(v/10)===1).length===12 && board.flat().filter(v=>Math.floor(v/10)===2).length===12"));
  check('开局合法走法提示已下发', await cdp.eval("Array.isArray(rpgView?.legalMoves) && rpgView.legalMoves.length===7"));

  // 用真实 DOM 坐标点击：红方先手，选中 (5,2)，落到 (4,3)。
  const clickCell = async (row, col) => {
    const p = await cdp.eval("(()=>{const r=canvas.getBoundingClientRect();"
      + "const m=GameModules.CHECKERS;const x=r.left+(m.padding+(" + col + "+0.5)*m.cell)*(r.width/canvas.width);"
      + "const y=r.top+(m.padding+(" + row + "+0.5)*m.cell)*(r.height/canvas.height);"
      + "return {x,y,left:r.left,top:r.top,width:r.width,height:r.height};})()");
    await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: p.x, y: p.y, button: 'left', clickCount: 1 });
    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: p.x, y: p.y, button: 'left', clickCount: 1 });
    return p;
  };
  const clicked = await clickCell(5, 2);
  console.log('[ui-checkers] click', JSON.stringify(clicked), 'selected', JSON.stringify(await cdp.eval('selectedFrom')));
  check('点击己方棋子出现选中框', await cdp.eval("!!selectedFrom && selectedFrom.row===5 && selectedFrom.col===2"),
    await cdp.eval('selectedFrom'));
  await clickCell(4, 3);
  await until(() => cdp.eval("board[4][3]===11 && board[5][2]===0"), '走子同步', 8000);
  check('真实鼠标点击完成选子与落子', await cdp.eval("board[4][3]===11 && board[5][2]===0"));
  check('AI 随后在真实房间内自动行动', await until(() => cdp.eval("currentTurn===1 || gameState==='OVER'"), 'AI 行动', 10000));
  await cdp.shot('checkers-desktop');

  // 手机布局与点击命中。
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 390, height: 844, deviceScaleFactor: 2, mobile: true
  });
  const mobile = await cdp.eval("(()=>{const r=canvas.getBoundingClientRect();"
    + "return {scroll:document.documentElement.scrollWidth,width:innerWidth,left:r.left,right:r.right,"
    + "height:r.height,canvasWidth:canvas.width};})()");
  check('390px 页面无横向溢出', mobile.scroll <= mobile.width + 1, mobile);
  check('手机画布在视口内可交互', mobile.left >= 0 && mobile.right <= mobile.width + 1
    && mobile.height > 100, mobile);
  await cdp.shot('checkers-mobile');

  const errors = await cdp.eval("window.__errors || []");
  check('页面无运行时异常', errors.length === 0 && cdp.errors.length === 0,
    { page: errors, browser: cdp.errors });
  console.log(failed ? `\n[ui-checkers] FAIL (${failed} failed, ${passed} passed)`
    : `\n[ui-checkers] PASS (${passed}/${passed})`);
  cdp.ws.close();
  process.exit(failed ? 1 : 0);
})().catch(e => {
  console.error('[ui-checkers] 异常:', e.stack);
  process.exit(1);
});
