import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';

const CDP = process.env.CDP_PORT || 9356;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'target/dots-validation/shots';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let passed = 0;
function check(label, condition, detail) {
  assert.ok(condition, label + (detail ? ' :: ' + JSON.stringify(detail) : ''));
  passed++;
  console.log('[ui-dots] PASS ' + label);
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
  const point = await page.eval('(()=>{const rect=canvas.getBoundingClientRect();const module=GameModules.DOTS_BOXES;'
    + 'return {x:rect.left+(module.padding+(' + col + ')*module.cell)*rect.width/canvas.width,'
    + 'y:rect.top+(module.top+(' + row + ')*module.cell)*rect.height/canvas.height};})()');
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
  const info=await(await fetch('http://127.0.0.1:'+CDP+'/json/version')).json();const socket=new WebSocket(info.webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject;});chrome=new Chrome(socket);
  const desktop=await chrome.page('点格桌面',1280,980,false),mobile=await chrome.page('点格手机',390,844,true);
  await desktop.eval('[...document.querySelectorAll(".game-category")].find(b=>b.textContent==="棋盘").click()');
  check('棋盘分类包含点格棋与规则入口',await desktop.eval('!!document.querySelector("[data-game-type=DOTS_BOXES] [data-rules]")'));
  await desktop.eval('document.querySelector("[data-game-type=DOTS_BOXES] [data-rules]").click()');
  const rules=await desktop.eval('document.getElementById("rules-body").textContent');
  check('教程解释围格继续共享边与退出规则',rules.includes('第四条边')&&rules.includes('继续')&&rules.includes('两格')&&rules.includes('保留'));
  check('教程显示2至4人及独立30秒步时',await desktop.eval('document.getElementById("rules-meta").textContent.includes("2–4")&&document.getElementById("rules-meta").textContent.includes("30 秒")'));
  check('点格首胜接入34项成就',await desktop.eval('(async()=>{const p=await(await fetch("/api/me/progress",{headers:{Authorization:"Bearer "+guestToken}})).json();return p.achievementsTotal===34&&p.achievements.some(a=>a.id==="mode-win-dots_boxes")})()'));
  await desktop.eval('document.getElementById("rules-close").click();document.querySelector("[data-game-type=DOTS_BOXES]").click();createRoom()');
  await until(()=>desktop.eval('gameState==="WAITING"&&!!roomId'),'创建点格房');const room=await desktop.eval('roomId');
  await mobile.eval('joinSpecificRoom('+JSON.stringify(room)+',"DOTS_BOXES")');
  await until(async()=>await desktop.eval('gameState==="PLAYING"&&rpgView?.kind==="DOTS_BOXES"')&&await mobile.eval('gameState==="PLAYING"&&!!rpgView'),'双方开局');
  check('服务端下发40条合法空边',await desktop.eval('rpgView.legalMoves.length===40&&myStone===1')&&await mobile.eval('myStone===2'));
  await cell(desktop,0,0);await sleep(350);
  check('点击圆点不误画附近边',await desktop.eval('window.__sentMoves===0&&moveSeq===0'));
  const order=[[0,1],[1,0],[2,1],[0,3],[1,4],[2,3],[1,2]];
  for(let r=0;r<9;r++)for(let c=0;c<9;c++)if((r+c)%2===1&&!order.some(e=>e[0]===r&&e[1]===c))order.push([r,c]);
  for(let i=0;i<40;i++) {
    const turn=await desktop.eval('rpgView.turn'),actor=turn===1?desktop:mobile,[r,c]=order[i];
    const sent=await actor.eval('window.__sentMoves||0');await sleep(350);await cell(actor,r,c,actor===mobile);
    const synced='moveCount==='+ (i+1)+'&&moveSeq==='+ (i+1)+'&&board['+r+']['+c+']==='+turn;
    await until(async()=>await desktop.eval(synced)&&await mobile.eval(synced),'画边'+(i+1));
    if(i===1)check('一次手机触摸只发送一条MOVE',await actor.eval('window.__sentMoves')===sent+1);
    assert.deepEqual(await desktop.eval('board'),await mobile.eval('board'));
    assert.deepEqual(await desktop.eval('GameModules.DOTS_BOXES.position(board,2).scores'),await desktop.eval('rpgView.players.map(p=>p.score)'));
    if(i===6) {
      check('共享边真实围成两格且仍由本人行动',await desktop.eval('rpgView.lastCaptured===2&&currentTurn===1&&board[1][1]===1&&board[1][3]===1'));
      check('状态栏清楚说明继续画边',await desktop.eval('document.getElementById("status-msg").textContent.includes("继续画边")'));
      const origin=await mobile.eval('performance.timeOrigin');await mobile.send('Page.reload');
      await until(()=>mobile.eval('performance.timeOrigin!=='+origin+'&&typeof joinGame==="function"'),'手机刷新');
      await mobile.eval('document.getElementById("ws-url").value='+JSON.stringify(WS)+';joinGame()');
      await until(()=>mobile.eval('gameState==="PLAYING"&&moveCount===7'),'手机重连');
      check('刷新恢复归属积分与连续行动方',await mobile.eval('myStone===2&&board[1][1]===1&&board[1][3]===1&&currentTurn===1&&rpgView.continued'));
    }
    if(i===23) {
      const layout=await mobile.eval('(()=>{const r=canvas.getBoundingClientRect();return {width:innerWidth,scroll:document.documentElement.scrollWidth,left:r.left,right:r.right,height:r.height}})()');
      check('390px手机页面及棋盘无横向溢出',layout.scroll<=layout.width+1&&layout.left>=0&&layout.right<=391&&layout.height>300,layout);
      await desktop.eval('scrollTo(0,0)');await mobile.eval('canvas.scrollIntoView({block:"start",behavior:"instant"})');
      await desktop.shot('dots-desktop');await mobile.shot('dots-mobile');
    }
  }
  await until(async()=>await desktop.eval('gameState==="OVER"')&&await mobile.eval('gameState==="OVER"'),'自然终局');
  check('两页完成40条边并分配全部16格',await desktop.eval('moveCount===40&&rpgView.remainingBoxes===0&&rpgView.players.reduce((n,p)=>n+p.score,0)===16'));
  check('双人8比8自然终局显示平局',await desktop.eval('rpgView.players.every(p=>p.score===8)&&document.getElementById("gameover-result").textContent.replaceAll(" ","").includes("平局")')&&await mobile.eval('document.getElementById("gameover-result").textContent.replaceAll(" ","").includes("平局")'));
  const tieRecords=await(await fetch(REST+'/api/games?gameType=DOTS_BOXES&limit=10')).json();
  const tieRecord=tieRecords.find(r=>r.roomId===room);assert.ok(tieRecord,'双人平局进入对局记录');
  const tieDetail=await(await fetch(REST+'/api/games/'+tieRecord.id)).json();
  check('双人平局存档双方结果为DRAW',tieDetail.participants.length===2&&tieDetail.participants.every(p=>p.result==='DRAW'));
  check('结算解释包含点格与连续围格建议',await mobile.eval('document.getElementById("gameover-insight").textContent.includes("点格棋")&&document.getElementById("gameover-insight").textContent.includes("围格")'));
  const finalBoard=await mobile.eval('board');await mobile.eval('document.getElementById("gameover-overlay").classList.remove("show");enterReplay();replaySeek(replayMoves.length)');
  check('回放完整重建每条边与方格归属',JSON.stringify(await mobile.eval('board'))===JSON.stringify(finalBoard));
  await mobile.eval('replaySeek(7)');
  check('回放第七手重建双围格不串用终局',await mobile.eval('GameModules.DOTS_BOXES.position(board,2).scores[0]===2&&board[1][1]===1&&board[1][3]===1'));
  await mobile.eval('replaySeek(0)');check('回放起点为空盘零分',await mobile.eval('board.flat().every(v=>v===0)&&GameModules.DOTS_BOXES.position(board,2).scores.every(v=>v===0)'));
  const controls=await mobile.eval('[...document.querySelectorAll(".replay-controls button")].map(b=>({h:b.getBoundingClientRect().height,w:b.getBoundingClientRect().width}))');
  check('手机回放按钮至少44px',controls.length>0&&controls.every(c=>c.h>=44&&c.w>=44));
  await mobile.shot('dots-replay-mobile');await mobile.eval('exitReplay();leaveRoom()');await until(()=>mobile.eval('gameState==="LOBBY"'),'离场');
  await mobile.eval('startSoloGame("DOTS_BOXES")');await until(()=>mobile.eval('document.getElementById("difficulty-dialog").classList.contains("show")'),'选择难度');
  await mobile.eval('[...document.querySelectorAll(".difficulty-opt")].find(b=>b.dataset.diff==="6").click()');
  await until(()=>mobile.eval('gameState==="PLAYING"&&currentGameType==="DOTS_BOXES"'),'困难电脑');await cell(mobile,0,1,true);
  await until(()=>mobile.eval('moveCount===2&&currentTurn===myStone'),'电脑回应');
  check('困难电脑在真实房间合法回应',await mobile.eval('rpgView.remainingEdges===38&&GameModules.DOTS_BOXES.position(board,2).used===2'));
  const quad=[];for(let i=0;i<4;i++)quad.push(await chrome.page('四席'+(i+1),i===0?1280:390,i===0?980:844,i!==0));
  await quad[0].eval('document.querySelector("[data-game-type=DOTS_BOXES]").click();document.getElementById("room-player-count").value="4";createRoom()');
  await until(()=>quad[0].eval('gameState==="WAITING"&&!!roomId'),'四人UI建房');const quadRoom=await quad[0].eval('roomId');
  for(let i=1;i<4;i++)await quad[i].eval('joinSpecificRoom('+JSON.stringify(quadRoom)+',"DOTS_BOXES")');
  await until(async()=>(await Promise.all(quad.map(p=>p.eval('gameState==="PLAYING"&&rpgView?.playerCount===4')))).every(Boolean),'四页开局');
  // JOIN 在各自连接中异步到达，按服务端实际分配的座位选择操作者。
  const bySide=new Map(await Promise.all(quad.map(async page=>[await page.eval('myStone'),page])));
  check('四个真实页面分配四席并同步完整积分榜',bySide.size===4&&[1,2,3,4].every(side=>bySide.has(side))&&(await Promise.all(quad.map(p=>p.eval('rpgView.players.length===4&&rpgView.remainingEdges===40')))).every(Boolean));
  await cell(bySide.get(1),0,1);await until(()=>bySide.get(4).eval('moveCount===1&&currentTurn===2'),'四席首手');
  await bySide.get(2).eval('leaveRoom()');await until(()=>bySide.get(4).eval('rpgView.players[1].active===false&&currentTurn===3'),'四席退出');
  await cell(bySide.get(3),0,3,true);await until(()=>bySide.get(4).eval('moveCount===2&&currentTurn===4'),'退出后触摸继续');
  check('手机四人局能跳过离场者继续画边',await bySide.get(4).eval('gameState==="PLAYING"&&rpgView.players[1].active===false&&board[0][3]===3'));
  const quadLayout=await bySide.get(4).eval('(()=>{const r=canvas.getBoundingClientRect();return {width:innerWidth,scroll:document.documentElement.scrollWidth,left:r.left,right:r.right}})()');
  check('四席手机布局不横向溢出',quadLayout.scroll<=quadLayout.width+1&&quadLayout.left>=0&&quadLayout.right<=391,quadLayout);
  await bySide.get(4).eval('canvas.scrollIntoView({block:"start",behavior:"instant"})');await bySide.get(4).shot('dots-four-mobile');
  check('所有页面无脚本或控制台错误',chrome.errors.length===0,chrome.errors);
  console.log('[ui-dots] PASS '+passed+'/'+passed);
} catch(error) {console.error('[ui-dots] FAIL',error.stack);for(const [i,p] of (chrome?.pages||[]).entries()){try{console.error('state',i,await p.eval('({gameState,myStone,moveCount,moveSeq,currentTurn,view:rpgView})'));await p.shot('failure-'+i)}catch{}}process.exitCode=1;}
finally{if(chrome)await chrome.close();}
