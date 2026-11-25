import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';

const CDP = process.env.CDP_PORT || 9353;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'target/ultimate-validation/shots';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let passed = 0;
function check(label, condition, detail) {
  assert.ok(condition, label + (detail ? ' :: ' + JSON.stringify(detail) : ''));
  passed++;
  console.log('[ui-ultimate] PASS ' + label);
}
async function until(test, label, timeout = 15000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await test()) return;
    await sleep(100);
  }
  throw new Error('等待超时: ' + label);
}

class Chrome {
  constructor(socket) {
    this.socket = socket;
    this.id = 0;
    this.pending = new Map();
    this.contexts = [];
    this.errors = [];
    this.pages = [];
    socket.onmessage = event => {
      const message = JSON.parse(String(event.data));
      if (message.method === 'Runtime.exceptionThrown') this.errors.push(message.params.exceptionDetails.text);
      if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
        this.errors.push(message.params.args.map(arg => arg.value || arg.description).join(' '));
      }
      const request = this.pending.get(message.id);
      if (!request) return;
      clearTimeout(request.timer);
      this.pending.delete(message.id);
      if (message.error) request.reject(new Error(JSON.stringify(message.error)));
      else request.resolve(message.result);
    };
  }
  send(method, params = {}, sessionId) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('CDP 超时: ' + method)); }, 15000);
      this.pending.set(id, { resolve, reject, timer });
      this.socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
    });
  }
  async page(name, width, height, mobile) {
    const response = await fetch(REST + '/api/guest', { method: 'POST' });
    assert.ok(response.ok);
    const guest = await response.json();
    const { browserContextId } = await this.send('Target.createBrowserContext');
    this.contexts.push(browserContextId);
    const { targetId } = await this.send('Target.createTarget', { url: 'about:blank', browserContextId });
    const { sessionId } = await this.send('Target.attachToTarget', { targetId, flatten: true });
    const page = { send: (method, params) => this.send(method, params, sessionId) };
    this.pages.push(page);
    page.eval = async expression => {
      const result = await page.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
      if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
      return result.result.value;
    };
    page.shot = async label => {
      mkdirSync(OUT, { recursive: true });
      const result = await page.send('Page.captureScreenshot', { format: 'png' });
      writeFileSync(OUT + '/' + label + '.png', Buffer.from(result.data, 'base64'));
    };
    await page.send('Runtime.enable');
    await page.send('Page.enable');
    await page.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile });
    if (mobile) await page.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 1 });
    await page.send('Page.addScriptToEvaluateOnNewDocument', {
      source: 'localStorage.setItem("gomoku_guest",' + JSON.stringify(guest.token) + ');'
        + 'localStorage.setItem("gomoku_onboarded","1");window.alert=text=>{window.__alert=String(text)};window.confirm=()=>true;'
    });
    await page.send('Page.navigate', { url: APP });
    await until(() => page.eval('typeof joinGame === "function"'), name + ' 页面加载');
    await page.eval('document.getElementById("ws-url").value=' + JSON.stringify(WS)
      + ';document.getElementById("player-name").value=' + JSON.stringify(name) + ';joinGame()');
    await until(() => page.eval('gameState === "LOBBY" && ws.readyState === 1'), name + ' 大厅');
    await page.eval('window.__sentMoves=0;(()=>{const original=ws.send.bind(ws);ws.send=data=>{'
      + 'if(typeof data==="string"){try{if(JSON.parse(data).type==="MOVE")window.__sentMoves++}catch{}}return original(data)}})()');
    return page;
  }
  async close() {
    for (const browserContextId of this.contexts) {
      try { await this.send('Target.disposeBrowserContext', { browserContextId }); } catch {}
    }
    this.socket.close();
  }
}

async function cell(page, row, col, touch = false) {
  await page.eval('canvas.scrollIntoView({block:"center",behavior:"instant"})');
  const point = await page.eval('(()=>{const rect=canvas.getBoundingClientRect();const module=GameModules.ULTIMATE_TTT;'
    + 'return {x:rect.left+(module.padding+(' + col + '+0.5)*module.cell)*rect.width/canvas.width,'
    + 'y:rect.top+(module.top+(' + row + '+0.5)*module.cell)*rect.height/canvas.height};})()');
  page.lastPoint = { row, col, ...point };
  if (touch) {
    await page.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [point] });
    await page.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  } else {
    await page.send('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', clickCount: 1, ...point });
    await page.send('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', clickCount: 1, ...point });
  }
}

let chrome;
try {
  const info = await (await fetch('http://127.0.0.1:' + CDP + '/json/version')).json();
  const socket = new WebSocket(info.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  chrome = new Chrome(socket);
  const desktop = await chrome.page('九宫桌面', 1280, 960, false);
  const mobile = await chrome.page('九宫手机', 390, 844, true);
  await desktop.eval('[...document.querySelectorAll(".game-category")].find(button=>button.textContent==="棋盘").click()');
  check('棋盘分类包含终极井字棋与规则入口', await desktop.eval('!!document.querySelector("[data-game-type=ULTIMATE_TTT] [data-rules]")'));
  await desktop.eval('document.querySelector("[data-game-type=ULTIMATE_TTT] [data-rules]").click()');
  const rules = await desktop.eval('document.getElementById("rules-body").textContent');
  check('教程解释区域限制封盘与大盘连线', rules.includes('相对位置') && rules.includes('封闭') && rules.includes('三个小盘'));
  check('教程显示正确人数与计时', await desktop.eval('document.getElementById("rules-meta").textContent.includes("45 秒")'
    + ' && document.getElementById("rules-meta").textContent.includes("2 人")'));
  await desktop.eval('document.getElementById("rules-close").click();document.querySelector("[data-game-type=ULTIMATE_TTT]").click();createRoom()');
  await until(() => desktop.eval('gameState === "WAITING" && !!roomId'), '创建九宫房间');
  const room = await desktop.eval('roomId');
  await mobile.eval('joinSpecificRoom(' + JSON.stringify(room) + ',"ULTIMATE_TTT")');
  await until(async () => await desktop.eval('gameState === "PLAYING" && !!rpgView')
    && await mobile.eval('gameState === "PLAYING" && !!rpgView'), '双页面开局');
  check('真实双页面分别分配叉方与圈方', await desktop.eval('myStone === 1') && await mobile.eval('myStone === 2'));
  check('开局服务端下发全部合法空格', await desktop.eval('rpgView.legalMoves.length === 81 && rpgView.nextBoard === -1'));
  await cell(desktop, 4, 4);
  await until(() => mobile.eval('moveSeq === 1 && board[4][4] === 1'), '首手广播');
  check('真实鼠标首手后手机限定中央区域', await mobile.eval('rpgView.nextBoard === 4 && rpgView.legalMoves.length === 8'));
  check('手机状态栏明确指出落子区域', await mobile.eval('document.getElementById("status-msg").textContent.includes("中央")'));

  await cell(mobile, 0, 0, true);
  await mobile.send('Runtime.evaluate', { expression: 'new Promise(resolve=>setTimeout(resolve,400))', awaitPromise: true });
  check('触摸非指定区域不会提交落子', await mobile.eval('moveSeq === 1 && board[0][0] === 0'));
  await cell(mobile, 3, 3, true);
  await until(async () => await desktop.eval('moveSeq === 2 && board[3][3] === 2')
    && await mobile.eval('moveSeq === 2 && rpgView.nextBoard === 0'), '真实触屏落子');
  check('单次触屏只提交一手落子', await mobile.eval('window.__sentMoves === 1'),
    await mobile.eval('({sent:window.__sentMoves,moveSeq,currentTurn,view:rpgView.nextBoard})'));
  check('手机触摸后双方恢复相同的新区域', await mobile.eval('moveSeq === 2 && rpgView.nextBoard === 0')
    && await desktop.eval('rpgView.nextBoard === 0'));

  const timeOrigin = await mobile.eval('performance.timeOrigin');
  await mobile.send('Page.reload');
  await until(() => mobile.eval('performance.timeOrigin !== ' + timeOrigin + ' && typeof joinGame === "function"'), '手机刷新');
  await mobile.eval('document.getElementById("ws-url").value=' + JSON.stringify(WS) + ';joinGame()');
  await until(() => mobile.eval('gameState === "PLAYING" && rpgView?.nextBoard === 0 && moveSeq === 2'), '手机断线重连');
  check('刷新保留盘面座位与指定区域', await mobile.eval('myStone === 2 && board[4][4] === 1 && board[3][3] === 2'));

  let seed = 42;
  for (let index = 0; index < 65 && !(await desktop.eval('rpgView.subBoards.flat().some(Boolean)')); index++) {
    const turn = await desktop.eval('rpgView.turn');
    const actor = turn === 1 ? desktop : mobile;
    const moves = await actor.eval('rpgView.legalMoves');
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    const move = moves[seed % moves.length];
    const before = await desktop.eval('moveSeq');
    await sleep(350);
    await cell(actor, move[0], move[1], actor === mobile);
    // sendMove 会先乐观增加本地 moveSeq；必须等双方真正应用服务端落子，
    // 否则下一轮可能在旧视图上选点，造成自动化误报。
    const synced = 'moveCount === ' + (before + 1) + ' && moveSeq === ' + (before + 1)
      + ' && board[' + move[0] + '][' + move[1] + '] === ' + turn;
    await until(async () => await desktop.eval(synced) && await mobile.eval(synced), '合法落子同步');
  }
  check('真实点击能够赢下或封闭小盘', await desktop.eval('rpgView.subBoards.flat().some(Boolean)'));
  check('回放重建算法与服务端小盘状态一致', await desktop.eval('JSON.stringify(GameModules.ULTIMATE_TTT.position(board,lastMove).subBoards) === JSON.stringify(rpgView.subBoards)'));
  const layout = await mobile.eval('(()=>{const rect=canvas.getBoundingClientRect();return {width:innerWidth,'
    + 'scroll:document.documentElement.scrollWidth,left:rect.left,right:rect.right,height:rect.height};})()');
  check('390px 页面无横向溢出', layout.scroll <= layout.width + 1, layout);
  check('手机棋盘完整落在视口宽度内', layout.left >= 0 && layout.right <= 391 && layout.height > 250, layout);
  await desktop.eval('scrollTo(0,0)');
  await mobile.eval('scrollTo(0,0)');
  await desktop.shot('ultimate-desktop');
  await mobile.shot('ultimate-mobile');

  await desktop.eval('surrender();document.getElementById("confirm-ok").click()');
  await until(() => mobile.eval('gameState === "OVER" && document.getElementById("gameover-overlay").classList.contains("show")'), '认输结算');
  check('结算解释识别新玩法与实际认输原因', await mobile.eval('document.getElementById("gameover-insight").textContent.includes("终极井字棋")'
    + ' && document.getElementById("gameover-insight").textContent.includes("认输")'));
  await mobile.eval('document.getElementById("gameover-overlay").classList.remove("show");enterReplay();replaySeek(replayMoves.length)');
  check('对局回放重建封盘和区域状态', await mobile.eval('replayMode && GameModules.ULTIMATE_TTT.position(board,lastMove).subBoards.flat().some(Boolean)'));
  const replayControls = await mobile.eval('(()=>{const nodes=[...document.querySelectorAll(".replay-controls > *")];'
    + 'return {width:innerWidth,scroll:document.documentElement.scrollWidth,items:nodes.map(node=>({'
    + 'tag:node.tagName,text:node.textContent.trim(),left:node.getBoundingClientRect().left,'
    + 'right:node.getBoundingClientRect().right,height:node.getBoundingClientRect().height}))};})()');
  check('手机回放控件不溢出或挤成竖排', replayControls.scroll <= replayControls.width + 1
    && replayControls.items.every(item => item.left >= 0 && item.right <= replayControls.width + 1), replayControls);
  check('手机回放按钮均达到 44px 触控高度', replayControls.items.filter(item=>item.tag==='BUTTON').every(item=>item.height>=44), replayControls);
  await mobile.shot('ultimate-replay-mobile');
  await mobile.eval('replaySeek(0)');
  check('回放起点为空盘自由选择不串用终局视图', await mobile.eval('board.flat().every(value=>value===0)'
    + ' && GameModules.ULTIMATE_TTT.position(board,lastMove).nextBoard === -1'));
  await mobile.eval('exitReplay();leaveRoom()');
  await until(() => mobile.eval('gameState === "LOBBY"'), '返回大厅');
  await mobile.eval('startSoloGame("ULTIMATE_TTT")');
  await until(() => mobile.eval('document.getElementById("difficulty-dialog").classList.contains("show")'), '人机难度');
  await mobile.eval('[...document.querySelectorAll(".difficulty-opt")].find(button=>button.dataset.diff==="6").click()');
  await until(() => mobile.eval('gameState === "PLAYING" && currentGameType === "ULTIMATE_TTT"'), '困难人机开局');
  await cell(mobile, 4, 4, true);
  await until(() => mobile.eval('moveSeq === 2 && currentTurn === myStone'), '困难 AI 回应');
  check('困难电脑能在真实人机房内合法回应', await mobile.eval('board.flat().filter(Boolean).length === 2 && rpgView.legalMoves.length > 0'));
  check('所有页面无脚本异常或控制台错误', chrome.errors.length === 0, chrome.errors);
  console.log('[ui-ultimate] PASS ' + passed + '/' + passed);
} catch (error) {
  console.error('[ui-ultimate] FAIL', error.stack);
  for (const [index, page] of (chrome?.pages || []).entries()) {
    try {
      console.error('[ui-ultimate] state', index, page.lastPoint,
        await page.eval('({gameState,myStone,currentTurn,moveSeq,next:rpgView?.nextBoard,'
          + 'status:document.getElementById("status-msg").textContent,scrollY,innerHeight,'
          + 'confirm:document.getElementById("confirm-dialog").className,rect:canvas.getBoundingClientRect().toJSON()})'));
      await page.shot('ultimate-failure-' + index);
    } catch {}
  }
  process.exitCode = 1;
} finally {
  if (chrome) await chrome.close();
}
