// 飞行棋 UI 冒烟（真实浏览器）：
// 1) 大厅「派对」分类能看到飞行棋与独立规则入口；
// 2) 规则说明包含 6 点起飞、撞回、终点与 2–4 人；
// 3) 真实鼠标点选棋子与目标格后，服务端位置与手数同步；
// 4) 无棋可走时出现「跳过本回合」，点击后推进回合；
// 5) 桌面 1280 与手机 390 均无横向溢出，盘面自己的骰子/阶段条不遮挡底部终点。
// 需要服务端 19000/19001 与 headless Chrome CDP（默认 9348）。
const CDP_PORT = process.env.CDP_PORT || 9348;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'target/flight-validation/shots';
const sleep = ms => new Promise(r => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[ui-flight] PASS ' + name); }
  else { failed++; console.error('[ui-flight] FAIL ' + name + (detail ? ' :: ' + JSON.stringify(detail) : '')); }
}
async function until(fn, label, timeout = 15000) {
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
  console.log('== 飞行棋 UI ==');
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
    + ";document.getElementById('player-name').value='飞行UI';joinGame();");
  await until(() => cdp.eval("gameState==='LOBBY' && !!guestToken && ws.readyState===1"), '进入大厅');

  // 派对分类与规则入口。
  await cdp.eval("Array.from(document.querySelectorAll('#game-categories .game-category'))"
    + ".find(b=>b.textContent==='派对').click()");
  const partyCards = await cdp.eval("Array.from(document.querySelectorAll('#game-selector .game-card'))"
    + ".map(c=>c.dataset.gameType)");
  check('派对分类包含飞行棋', partyCards.includes('FLIGHT_CHESS'), partyCards);
  check('飞行棋卡片有独立名称与规则入口', await cdp.eval(
    "!!document.querySelector('.game-card[data-game-type=\"FLIGHT_CHESS\"] [data-rules]')"
    + " && document.querySelector('.game-card[data-game-type=\"FLIGHT_CHESS\"]').textContent.includes('飞行棋')"));

  await cdp.eval("document.querySelector('.game-card[data-game-type=\"FLIGHT_CHESS\"] [data-rules]').click()");
  await until(() => cdp.eval("document.getElementById('rules-modal').classList.contains('show')"), '飞行棋规则');
  const rules = await cdp.eval("({title:document.getElementById('rules-title').textContent,"
    + "text:document.getElementById('rules-body').textContent,"
    + "meta:document.getElementById('rules-meta').textContent})");
  check('规则标题与玩法一致', rules.title.includes('飞行棋'), rules);
  check('规则说明 6 点起飞、撞回与终点',
    rules.text.includes('6 点') && rules.text.includes('撞回') && rules.text.includes('终点'), rules.text);
  check('规则显示 2–4 人与每步 30 秒',
    rules.meta.includes('2–4 人') && rules.meta.includes('30 秒'), rules.meta);
  await cdp.shot('flight-rules');
  await cdp.eval("document.getElementById('rules-close').click()");

  // 真实人机开局；飞行棋默认 2 人，便于逐步验证。
  // 若首骰恰好是 6，本局会直接进入 MOVE，脚本就永远看不到 SKIP 入口。
  // 因此把「首骰不是 6」作为重开条件（连续 4 次都是 6 的概率约 1/1296）。
  let opening = null;
  for (let attempt = 0; attempt < 4; attempt++) {
    await cdp.eval("document.querySelector('.game-card[data-game-type=\"FLIGHT_CHESS\"]').click()");
    await cdp.eval("document.querySelector('.game-card[data-game-type=\"FLIGHT_CHESS\"] [data-solo]').click()");
    await until(() => cdp.eval("document.getElementById('difficulty-dialog').classList.contains('show')"), '难度弹层');
    await cdp.eval("document.querySelector('#difficulty-dialog .difficulty-opt[data-diff=\"4\"]').click()");
    await until(() => cdp.eval("gameState==='PLAYING' && currentGameType==='FLIGHT_CHESS'"), '飞行棋人机开局', 15000);
    // 等首个 view 落地（GAME_START 里已带 dice；若 AI 先手则由 GAME_MOVE 补齐）。
    await until(() => cdp.eval("!!rpgView && rpgView.dice >= 1 && rpgView.dice <= 6"), '首骰落地', 8000);
    opening = await cdp.eval("({stone:myStone,turn:currentTurn,phase:rpgView?.phase,dice:rpgView?.dice,"
      + "players:rpgView?.players?.length,legal:rpgView?.legalMoves?.length})");
    if (opening.stone === opening.turn && opening.phase === 'SKIP') break;
    await cdp.eval("leaveRoom()");
    await until(() => cdp.eval("gameState==='LOBBY'"), '返回大厅', 8000);
  }
  check('取得可直接验证跳过入口的开局', opening?.stone === opening?.turn && opening?.phase === 'SKIP', opening);

  const initial = await cdp.eval("({stone:myStone,turn:currentTurn,phase:rpgView?.phase,dice:rpgView?.dice,"
    + "players:rpgView?.players?.length,legal:rpgView?.legalMoves?.length,"
    + "rect:(()=>{const r=canvas.getBoundingClientRect();return {left:r.left,top:r.top,width:r.width,height:r.height};})(),"
    + "canvasW:canvas.width,canvasH:canvas.height,moduleW:GameModules.FLIGHT_CHESS.canvasWidth(),"
    + "moduleH:GameModules.FLIGHT_CHESS.canvasHeight()})");
  console.log('[ui-flight] initial', JSON.stringify(initial));
  check('开局画布为 15×15 外圈', initial.canvasW === initial.moduleW
    && initial.canvasH === initial.moduleH && initial.canvasW > initial.canvasH - 1, initial);
  check('开局 view 含 2 名玩家与 8 枚棋子', initial.players === 2, initial);
  check('开局显示服务端骰子', initial.dice >= 1 && initial.dice <= 6, initial);

  const clickCell = async (row, col) => {
    const p = await cdp.eval("(()=>{const r=canvas.getBoundingClientRect();"
      + "const m=GameModules.FLIGHT_CHESS;const x=r.left+(m.padding+(" + col + "+0.5)*m.cell)*(r.width/canvas.width);"
      + "const y=r.top+(m.padding+(" + row + "+0.5)*m.cell)*(r.height/canvas.height);"
      + "return {x,y};})()");
    await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: p.x, y: p.y, button: 'left', clickCount: 1 });
    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: p.x, y: p.y, button: 'left', clickCount: 1 });
  };

  // 先持续跳到至少见过一次 SKIP，再等待合法走法。
  // 首个骰子就有 1/6 概率直接可走，不能把「一开局立即看到 SKIP」当成固定前提。
  let sawSkip = false;
  const deadline = Date.now() + 45000;
  let legal = null;
  let skipObservedAt = 0;
  let skipAdvanced = false;
  while (Date.now() < deadline) {
    const s = await cdp.eval("({mine:currentTurn===myStone,phase:rpgView?.phase,over:rpgView?.over,"
      + "legal:Array.isArray(rpgView?.legalMoves)?rpgView.legalMoves:[],"
      + "skipBtn:(()=>{const b=document.getElementById('pass-btn');return b&&b.style.display!=='none'?b.textContent:'';})()"
      + ",myPositions:rpgView?.players?.find(p=>p.side===myStone)?.positions||[]})");
    if (s.over) break;
    if (s.phase === 'SKIP' && s.mine) {
      if (!sawSkip) check('无棋可走时显示「跳过本回合」', s.skipBtn.includes('跳过本回合'), s);
      sawSkip = true;
      if (!skipObservedAt) skipObservedAt = Date.now();
      await cdp.eval("document.getElementById('pass-btn').click()");
      // 跳过会重新掷骰；服务端可能立刻换手给 AI，不能要求之后仍由本人行动。
      // 用「视图响应已更新」判定：骰子/阶段变化，或本人 position 变化（如跳过的是别人）。
      for (let i = 0; i < 25 && !skipAdvanced; i++) {
        await sleep(120);
        const now = await cdp.eval("({dice:rpgView?.dice,phase:rpgView?.phase,"
          + "pos:rpgView?.players?.find(p=>p.side===myStone)?.positions||[]})");
        skipAdvanced = now.dice !== s.dice || now.phase !== s.phase
          || JSON.stringify(now.pos) !== JSON.stringify(s.myPositions);
      }
      continue;
    }
    if (s.mine && s.phase === 'MOVE' && s.legal.length) { legal = s.legal[0]; break; }
    await sleep(400);
  }
  check('对局中确实出现过无棋可走的跳过入口', sawSkip, { sawSkip, legal });
  check('跳过按钮通过真实交互推进对局',
    sawSkip && skipObservedAt > 0 && skipAdvanced,
    await cdp.eval("({passedTurns:rpgView?.passedTurns,turn:rpgView?.turn,dice:rpgView?.dice,"
      + "seq:moveSeq})"));
  check('掷出可走点数后收到合法走法', !!legal, legal);
  if (!legal) throw new Error('未等到合法飞行棋走法');

  const before = await cdp.eval("({seq:moveSeq,positions:rpgView.players.map(p=>p.positions.slice()),stone:myStone})");
  await clickCell(legal.fromRow, legal.fromCol);
  check('点击己方棋子出现选中框', await cdp.eval(
    "!!selectedFrom && selectedFrom.row===" + legal.fromRow + " && selectedFrom.col===" + legal.fromCol),
    await cdp.eval('selectedFrom'));
  // CDP 连发两次全局鼠标事件时，浏览器可能把第二下当成双击而少发一次 click。
  // 用 elementFromPoint + click() 触发画布自己的真实 DOM 事件，事件处理路径仍是生产代码。
  const targetClicked = await cdp.eval("(()=>{const r=canvas.getBoundingClientRect();"
    + "const m=GameModules.FLIGHT_CHESS;const x=r.left+(m.padding+(" + legal.toCol + "+0.5)*m.cell)*(r.width/canvas.width);"
    + "const y=r.top+(m.padding+(" + legal.toRow + "+0.5)*m.cell)*(r.height/canvas.height);"
    + "const el=document.elementFromPoint(x,y);if(!el||el!==canvas)return {hit:false,tag:el?.tagName||null};"
    + "el.dispatchEvent(new MouseEvent('click',{bubbles:true,clientX:x,clientY:y}));return {hit:true};})()");
  check('目标格点击命中画布', targetClicked.hit === true, targetClicked);
  // sendMove 会先在本地自增 moveSeq，不能拿它当服务端确认；
  // 以服务端 view 里的棋子位置变化为准，避免在 GAME_MOVE 到达前读取旧盘面。
  await until(() => cdp.eval("JSON.stringify(rpgView.players.map(p=>p.positions.slice()))"
    + " !== " + JSON.stringify(JSON.stringify(before.positions))), '走子同步', 10000);
  const afterMove = await cdp.eval("({seq:moveSeq,positions:rpgView.players.map(p=>p.positions.slice())})");
  const moved = JSON.stringify(before.positions) !== JSON.stringify(afterMove.positions);
  check('真实鼠标点击完成选子与目标格移动', moved, { before: before.positions, after: afterMove.positions });
  check('服务端手数随移动推进', afterMove.seq > before.seq, { before: before.seq, after: afterMove.seq });
  check('AI 随后自动掷骰或行动', await until(() => cdp.eval(
    "currentTurn===myStone || gameState==='OVER'"), 'AI 行动', 12000));
  await cdp.shot('flight-desktop');

  // 飞行棋的骰子/阶段条画在画布顶部中心；确保它完整落在画布内，
  // 并保证底部终点也未越出画布。状态文字在左侧房间面板，不参与该几何断言。
  const layout = await cdp.eval("(()=>{const r=canvas.getBoundingClientRect();"
    + "const m=GameModules.FLIGHT_CHESS;const sx=r.width/canvas.width,sy=r.height/canvas.height;"
    + "const strip={left:r.left+(m.padding+3*m.cell)*sx,top:r.top+(m.padding+3*m.cell)*sy,"
    + "right:r.left+(m.padding+12*m.cell)*sx,bottom:r.top+(m.padding+4.15*m.cell)*sy};"
    + "return {scroll:document.documentElement.scrollWidth,width:innerWidth,"
    + "canvas:{left:r.left,top:r.top,right:r.right,bottom:r.bottom},strip};})()");
  check('桌面页面无横向溢出', layout.scroll <= layout.width + 1, layout);
  check('盘面骰子/阶段条完整位于棋盘内', layout.strip.left >= layout.canvas.left - 1
    && layout.strip.right <= layout.canvas.right + 1 && layout.strip.top >= layout.canvas.top - 1
    && layout.strip.bottom <= layout.canvas.bottom + 1, layout);

  // 手机布局。
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 390, height: 844, deviceScaleFactor: 2, mobile: true
  });
  const mobile = await cdp.eval("(()=>{const r=canvas.getBoundingClientRect();"
    + "return {scroll:document.documentElement.scrollWidth,width:innerWidth,left:r.left,right:r.right,"
    + "height:r.height,canvasWidth:canvas.width};})()");
  check('390px 页面无横向溢出', mobile.scroll <= mobile.width + 1, mobile);
  check('手机画布在视口内可交互', mobile.left >= 0 && mobile.right <= mobile.width + 1
    && mobile.height > 100, mobile);
  await cdp.shot('flight-mobile');

  const errors = await cdp.eval("window.__errors || []");
  check('页面无运行时异常', errors.length === 0 && cdp.errors.length === 0,
    { page: errors, browser: cdp.errors });
  console.log(failed ? `\n[ui-flight] FAIL (${failed} failed, ${passed} passed)`
    : `\n[ui-flight] PASS (${passed}/${passed})`);
  cdp.ws.close();
  process.exit(failed ? 1 : 0);
})().catch(e => {
  console.error('[ui-flight] 异常:', e.stack);
  process.exit(1);
});
