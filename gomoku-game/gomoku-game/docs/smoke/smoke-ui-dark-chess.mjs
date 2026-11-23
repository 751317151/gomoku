// 暗棋 UI 冒烟（真实浏览器）：
// 1) 大厅棋盘分类包含暗棋，规则弹层解释背面/翻子/炮架/大小关系；
// 2) 真实鼠标点击背面棋子完成翻子，服务端公开身份并推进回合；
// 3) 选中己方明棋后只显示服务端下发的合法目标；
// 4) AI 在真实房间内自动行动；
// 5) 390px 手机布局无横向溢出，画布可交互；
// 6) 页面无运行时异常。
// 需要服务端与 headless Chrome CDP（默认 9350）。
const CDP_PORT = process.env.CDP_PORT || 9350;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'target/dark-chess-validation/shots';
const sleep = ms => new Promise(r => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[ui-dark-chess] PASS ' + name); }
  else { failed++; console.error('[ui-dark-chess] FAIL ' + name + (detail ? ' :: ' + JSON.stringify(detail) : '')); }
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
  console.log('== 暗棋 UI ==');
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
    + ";document.getElementById('player-name').value='暗棋UI';joinGame();");
  await until(() => cdp.eval("gameState==='LOBBY' && !!guestToken && ws.readyState===1"), '进入大厅');

  await cdp.eval("Array.from(document.querySelectorAll('#game-categories .game-category'))"
    + ".find(b=>b.textContent==='棋盘').click()");
  const boardCards = await cdp.eval("Array.from(document.querySelectorAll('#game-selector .game-card'))"
    + ".map(c=>c.dataset.gameType)");
  check('棋盘分类包含暗棋', boardCards.includes('DARK_CHESS'), boardCards);
  check('暗棋卡片有独立名称与规则入口', await cdp.eval(
    "!!document.querySelector('.game-card[data-game-type=\"DARK_CHESS\"] [data-rules]')"
    + " && document.querySelector('.game-card[data-game-type=\"DARK_CHESS\"]').textContent.includes('暗棋')"));

  await cdp.eval("document.querySelector('.game-card[data-game-type=\"DARK_CHESS\"] [data-rules]').click()");
  await until(() => cdp.eval("document.getElementById('rules-modal').classList.contains('show')"), '暗棋规则');
  const rules = await cdp.eval("({title:document.getElementById('rules-title').textContent,"
    + "text:document.getElementById('rules-body').textContent,"
    + "meta:document.getElementById('rules-meta').textContent})");
  check('规则标题与玩法一致', rules.title.includes('暗棋'), rules);
  check('规则说明背面、翻子、炮架与大小关系',
    rules.text.includes('背面') && rules.text.includes('翻开') && rules.text.includes('炮架')
    && rules.text.includes('帅 > 仕'), rules.text);
  check('规则显示 30 秒步时与 2 人', rules.meta.includes('30 秒') && rules.meta.includes('2 人'), rules.meta);
  await cdp.shot('dark-chess-rules');
  await cdp.eval("document.getElementById('rules-close').click()");

  await cdp.eval("document.querySelector('.game-card[data-game-type=\"DARK_CHESS\"]').click()");
  await cdp.eval("document.querySelector('.game-card[data-game-type=\"DARK_CHESS\"] [data-solo]').click()");
  await until(() => cdp.eval("document.getElementById('difficulty-dialog').classList.contains('show')"), '难度弹层');
  await cdp.eval("document.querySelector('#difficulty-dialog .difficulty-opt[data-diff=\"2\"]').click()");
  await until(() => cdp.eval("gameState==='PLAYING' && currentGameType==='DARK_CHESS'"), '暗棋人机开局', 15000);

  check('开局 32 枚背面棋', await cdp.eval("board.flat().filter(v=>v===9).length===32"), await cdp.eval('board'));
  check('开局所有暗子可翻', await cdp.eval("rpgView?.legalFlips?.length===32"));
  check('开局没有走子提示', await cdp.eval("rpgView?.legalMoves?.length===0"));
  check('红黑各 16 枚', await cdp.eval("rpgView?.redRemaining===16 && rpgView?.blackRemaining===16"));

  const clickCell = async (row, col) => {
    const p = await cdp.eval("(()=>{const r=canvas.getBoundingClientRect();"
      + "const m=GameModules.DARK_CHESS;const x=r.left+(m.padding+(" + col + "+0.5)*m.cell)*(r.width/canvas.width);"
      + "const y=r.top+(m.padding+(" + row + "+0.5)*m.cell)*(r.height/canvas.height);"
      + "return {x,y};})()");
    await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: p.x, y: p.y, button: 'left', clickCount: 1 });
    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: p.x, y: p.y, button: 'left', clickCount: 1 });
    return p;
  };

  await clickCell(0, 0);
  await until(() => cdp.eval("board[0][0]!==9 && rpgView?.hiddenCount===31"), '翻子同步', 8000);
  check('真实鼠标点击完成翻子', await cdp.eval("board[0][0]!==9 && board[0][0]>=10"));
  check('翻子后服务端推进回合并让 AI 行动', await until(() => cdp.eval(
    "rpgView?.hiddenCount<=30 && (currentTurn===1 || gameState==='OVER')"), 'AI 行动', 12000));
  await cdp.shot('dark-chess-desktop');

  // 等服务端出现至少一枚己方明棋，验证两阶段选中/目标高亮。
  let hasLegalSelection = false;
  for (let i = 0; i < 18; i++) {
    if (await cdp.eval("currentTurn===1 && Array.isArray(rpgView?.legalMoves) && rpgView.legalMoves.length>0")) {
      hasLegalSelection = true; break;
    }
    if (await cdp.eval("currentTurn===1")) {
      const flip = await cdp.eval("rpgView?.legalFlips?.[0] || null");
      if (flip) await clickCell(flip.row, flip.col);
    }
    await sleep(700);
  }
  check('随机开局可推进到己方明棋走子', hasLegalSelection);
  if (hasLegalSelection) {
    const first = await cdp.eval("rpgView.legalMoves[0]");
    await clickCell(first.fromRow, first.fromCol);
    check('选中己方明棋显示选中框', await cdp.eval(
      "selectedFrom?.row===" + first.fromRow + " && selectedFrom?.col===" + first.fromCol));
    check('只显示服务端下发的合法目标', await cdp.eval(
      "(rpgView.legalMoves||[]).filter(m=>m.fromRow===" + first.fromRow
      + "&&m.fromCol===" + first.fromCol + ").some(m=>m.toRow===" + first.toRow
      + "&&m.toCol===" + first.toCol + ")"));
    await clickCell(first.toRow, first.toCol);
    await until(() => cdp.eval("currentTurn===2 || gameState==='OVER'"), '走子同步', 8000);
    check('真实鼠标完成两阶段走子', await cdp.eval("selectedFrom===null"));
  }

  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 390, height: 844, deviceScaleFactor: 2, mobile: true
  });
  const mobile = await cdp.eval("(()=>{const r=canvas.getBoundingClientRect();"
    + "return {scroll:document.documentElement.scrollWidth,width:innerWidth,left:r.left,right:r.right,"
    + "height:r.height,canvasWidth:canvas.width};})()");
  check('390px 页面无横向溢出', mobile.scroll <= mobile.width + 1, mobile);
  check('手机画布在视口内可交互', mobile.left >= 0 && mobile.right <= mobile.width + 1
    && mobile.height > 100, mobile);
  await cdp.shot('dark-chess-mobile');

  const errors = await cdp.eval("window.__errors || []");
  check('页面无运行时异常', errors.length === 0 && cdp.errors.length === 0,
    { page: errors, browser: cdp.errors });
  console.log(failed ? `\n[ui-dark-chess] FAIL (${failed} failed, ${passed} passed)`
    : `\n[ui-dark-chess] PASS (${passed}/${passed})`);
  cdp.ws.close();
  process.exit(failed ? 1 : 0);
})().catch(e => {
  console.error('[ui-dark-chess] 异常:', e.stack);
  process.exit(1);
});
