import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';

const CDP = process.env.CDP_PORT || 9357;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'target/uno-validation/shots';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let passed = 0;
function check(label, condition, detail) {
  assert.ok(condition, label + (detail ? ' :: ' + JSON.stringify(detail) : ''));
  passed++;
  console.log('[ui-uno] PASS ' + label);
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

async function click(page, selector, touch = false) {
  const point = await page.eval('(()=>{const e=document.querySelector('+JSON.stringify(selector)+');if(!e||e.disabled)throw Error("按钮不可用: "+'+JSON.stringify(selector)+');e.scrollIntoView({block:"center",behavior:"instant"});const r=e.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2}})()');
  if(touch) { await page.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[point]});await page.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]}); }
  else { await page.send('Input.dispatchMouseEvent',{type:'mousePressed',button:'left',clickCount:1,...point});await page.send('Input.dispatchMouseEvent',{type:'mouseReleased',button:'left',clickCount:1,...point}); }
}
async function layout(page,label) {
  const result=await page.eval('(()=>{const p=document.getElementById("uno-panel").getBoundingClientRect();return {w:innerWidth,scroll:document.documentElement.scrollWidth,left:p.left,right:p.right,cards:[...document.querySelectorAll("#uno-hand button")].map(b=>{const r=b.getBoundingClientRect();return {w:r.width,h:r.height}})}})()');
  check(label,result.scroll<=result.w+1&&result.left>=0&&result.right<=result.w+1&&result.cards.every(r=>r.w>=44&&r.h>=44),result);
}
async function fixtures(page) {
  await page.eval('window.__unoSaved={view:rpgView,turn:currentTurn,version:actionVersion,send,side:myStone,id:myId};window.__unoCaptured=[];send=obj=>{if(obj.type==="MOVE")window.__unoCaptured.push(obj);else window.__unoSaved.send(obj)};window.prompt=()=>{throw Error("UNO不得弹出原生输入框")};'+
    'rpgView={...rpgView,over:false,turn:myStone,unoPendingSide:0,drawnPendingSide:0,pendingDraw:0,canDraw:false,canCallUno:false,canEndDraw:false,canCatchUno:false,top:{color:0,value:3,label:"红3"},activeColor:0,players:[1,2,3,4].map(side=>({side,name:side===3?"<img src=x>离场者":"玩家"+side,handCount:side===3?0:side===myStone?5:side+1,active:side!==3,...(side===myStone?{hand:[{index:0,color:0,value:7,label:"红7",playable:true},{index:1,color:-1,value:13,label:"万能",playable:true},{index:2,color:0,value:4,label:"红4",playable:true},{index:3,color:-1,value:14,label:"万能 +4",playable:false},{index:4,color:3,value:9,label:"蓝9",playable:false}]}:{})}))};currentTurn=myStone;renderUnoPanel()');
  await layout(page,'390px四席渲染样例无溢出且手牌触控至少44px');
  check('手机四席使用两行两列而非拥挤的三加一',await page.eval('(()=>{const r=[...document.querySelectorAll(".uno-seat")].map(e=>e.getBoundingClientRect());return r.length===4&&r[0].top===r[1].top&&r[2].top===r[3].top&&r[2].top>r[0].top})()'));
  check('渲染样例顶牌与当前颜色有文字和符号，非法+4禁用',await page.eval('document.getElementById("uno-discard").textContent.includes("红 ◆")&&document.getElementById("uno-colour").textContent.includes("红 ◆")&&document.getElementById("uno-hand").children[3].disabled'));
  await click(page,'[data-uno-index="0"]',true);
  check('换手选择列出在场对手及其牌数，排除本人和离场者',await page.eval('(()=>{const b=[...document.querySelectorAll("[data-uno-target]")];return b.length===2&&b.every(x=>Number(x.dataset.unoTarget)!==myStone&&Number(x.dataset.unoTarget)!==3)&&b.every(x=>x.textContent.includes("张"))&&!document.querySelector("#uno-panel img")})()'));
  await click(page,'#uno-cancel-choice',true);
  check('触摸取消不发送MOVE并还原手牌焦点',await page.eval('window.__unoCaptured.length===0&&document.getElementById("uno-choice").hidden&&document.activeElement.dataset.unoIndex==="0"'));
  await click(page,'[data-uno-index="0"]',true);
  await click(page,'[data-uno-target="1"]',true);
  check('单次触摸换手仅生成一条带目标MOVE',await page.eval('window.__unoCaptured.length===1&&window.__unoCaptured[0].targetSide===2&&window.__unoCaptured[0].targetIndex===1'));
  await click(page,'[data-uno-index="1"]',true);
  check('万能牌四色文字符号完整且每个按钮至少44px',await page.eval('[...document.querySelectorAll(".uno-colour-option")].map(b=>b.textContent).join("|")==="红 ◆|黄 ●|绿 ▲|蓝 ■"&&[...document.querySelectorAll(".uno-colour-option")].every(b=>b.getBoundingClientRect().height>=44&&b.getBoundingClientRect().width>=44)'));
  await page.eval('document.getElementById("uno-panel").scrollIntoView({block:"start",behavior:"instant"})');await page.shot('uno-mobile-colour');
  await click(page,'[data-uno-target="3"]',true);
  check('单次触摸选蓝色仅生成一条选色MOVE',await page.eval('window.__unoCaptured.length===2&&window.__unoCaptured[1].targetSide===1&&window.__unoCaptured[1].targetIndex===3'));
  await click(page,'[data-uno-index="1"]',true);
  await page.send('Input.dispatchKeyEvent',{type:'keyDown',key:'Escape',code:'Escape'});await page.send('Input.dispatchKeyEvent',{type:'keyUp',key:'Escape',code:'Escape'});
  check('Escape取消不会出牌',await page.eval('document.getElementById("uno-choice").hidden&&window.__unoCaptured.length===2'));
  await click(page,'[data-uno-index="1"]',true);
  await page.eval('actionVersion+="-changed";renderUnoPanel()');
  check('局面版本变化立即收起旧选择',await page.eval('document.getElementById("uno-choice").hidden&&unoChoice===null'));
  await page.eval('rpgView={...rpgView,drawnPendingSide:myStone,drawnCardIndex:1,canEndDraw:true,players:rpgView.players.map(p=>p.side===myStone?{...p,hand:p.hand.map(c=>({...c,playable:c.index===1}))}:p)};renderUnoPanel()');
  check('摸牌决定仅开放新牌和结束按钮',await page.eval('!document.getElementById("uno-draw-end").disabled&&document.getElementById("uno-draw").disabled&&document.getElementById("uno-call").disabled&&[...document.querySelectorAll("#uno-hand button:enabled")].length===1'));
  await page.eval('rpgView={...rpgView,drawnPendingSide:0,unoPendingSide:myStone,canEndDraw:false,canCallUno:true,players:rpgView.players.map(p=>p.side===myStone?{...p,hand:p.hand.map(c=>({...c,playable:false}))}:p)};renderUnoPanel()');
  check('喊UNO窗口锁住普通手牌与摸牌',await page.eval('!document.getElementById("uno-call").disabled&&document.getElementById("uno-draw").disabled&&document.querySelectorAll("#uno-hand button:enabled").length===0'));
  await page.eval('rpgView={...rpgView,canCallUno:false,pendingDraw:6,canDraw:true,unoPendingSide:0};renderUnoPanel()');
  check('叠加罚摸显示累计张数且允许选择接受',await page.eval('!document.getElementById("uno-draw").disabled&&document.getElementById("uno-draw").textContent.includes("6")'));
  await page.eval('rpgView={...rpgView,canDraw:false};myStone=0;myId="SPECTATOR";renderUnoPanel()');
  check('观战渲染不暴露手牌和可操作控件',await page.eval('document.querySelectorAll("#uno-hand button").length===0&&[...document.querySelectorAll("#uno-panel .card-actions-row button")].every(b=>b.disabled)'));
  // 显式恢复每个全局，样例只验证渲染与指令构造，后续再走真实服务端对局。
  await page.eval('rpgView=window.__unoSaved.view;currentTurn=window.__unoSaved.turn;actionVersion=window.__unoSaved.version;send=window.__unoSaved.send;myStone=window.__unoSaved.side;myId=window.__unoSaved.id;unoChoice=null;renderUnoPanel()');
}
let chrome;
try {
  const info=await(await fetch('http://127.0.0.1:'+CDP+'/json/version')).json();const socket=new WebSocket(info.webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject});chrome=new Chrome(socket);
  const desktop=await chrome.page('UNO桌面',1280,1000,false),mobile=await chrome.page('UNO手机',390,844,true),pages=[desktop,mobile];
  await desktop.eval('document.querySelector("[data-game-type=UNO]").click();document.getElementById("room-player-count").value="2";createRoom()');
  await until(()=>desktop.eval('gameState==="WAITING"&&!!roomId'),'建房');const room=await desktop.eval('roomId');
  await mobile.eval('joinSpecificRoom('+JSON.stringify(room)+',"UNO")');
  await until(async()=>(await Promise.all(pages.map(p=>p.eval('gameState==="PLAYING"&&rpgView?.kind==="UNO"')))).every(Boolean),'双端开局');
  await layout(desktop,'桌面真实牌桌无横向溢出');await layout(mobile,'390px真实牌桌无横向溢出');
  await desktop.eval('document.getElementById("uno-panel").scrollIntoView({block:"start",behavior:"instant"})');await desktop.shot('uno-desktop');
  await fixtures(mobile);
  let reconnected=false,steps=0;const observed={colour:0,exchange:0,draw:0,end:0,uno:0};
  while(await desktop.eval('gameState==="PLAYING"')&&steps++<310) {
    const states=await Promise.all(pages.map(p=>p.eval('({side:myStone,seq:moveSeq,view:rpgView})')));
    const actorIndex=states.findIndex(s=>s.side===states[0].view.turn),page=pages[actorIndex],state=states[actorIndex],v=state.view;
    if(v.canEndDraw&&!reconnected) {
      const origin=await page.eval('performance.timeOrigin');await page.send('Page.reload');
      await until(()=>page.eval('performance.timeOrigin!=='+origin+'&&typeof joinGame==="function"'),'刷新文档');
      await page.eval('document.getElementById("ws-url").value='+JSON.stringify(WS)+';joinGame()');
      await until(()=>page.eval('gameState==="PLAYING"&&moveSeq==='+state.seq+'&&rpgView.canEndDraw'),'重连恢复摸牌决定');
      check('真实刷新重连保留只能出新牌或结束的窗口',await page.eval('!document.getElementById("uno-draw-end").disabled&&[...document.querySelectorAll("#uno-hand button:enabled")].length===1'));reconnected=true;
    }
    await sleep(350);
    if(v.canCallUno) { await click(page,'#uno-call',actorIndex===1);observed.uno++; }
    else if(v.canEndDraw) { await click(page,'#uno-draw-end',actorIndex===1);observed.end++; }
    else {
      const legal=v.players.find(p=>p.side===state.side).hand.filter(c=>c.playable);
      const card=legal.find(c=>c.value===7)||legal.find(c=>c.color<0)||legal[0];
      if(!card){await click(page,'#uno-draw',actorIndex===1);observed.draw++;}
      else {
        await click(page,'[data-uno-index="'+card.index+'"]',actorIndex===1);
        if(card.color<0){await click(page,'[data-uno-target="'+((v.activeColor+1)%4)+'"]',actorIndex===1);observed.colour++;}
        else if(card.value===7&&v.players.find(p=>p.side===state.side).hand.length>1){const target=v.players.filter(p=>p.side!==state.side&&p.active).sort((a,b)=>a.handCount-b.handCount)[0];await click(page,'[data-uno-target="'+target.side+'"]',actorIndex===1);observed.exchange++;}
      }
    }
    await until(async()=>(await Promise.all(pages.map(p=>p.eval('moveSeq==='+ (state.seq+1))))).every(Boolean),'双端逐手同步');
  }
  await until(async()=>(await Promise.all(pages.map(p=>p.eval('gameState==="OVER"')))).every(Boolean),'自然终局');
  check('真实鼠标与触摸可自然完成整局且双方结果一致',await desktop.eval('rpgView.over')&&await mobile.eval('rpgView.over')&&await desktop.eval('rpgView.winner')===await mobile.eval('rpgView.winner'));
  if(observed.end>0) check('遇到的真实摸牌决定均可结束且已验证窗口重连',reconnected&&observed.end>0);
  const records=await(await fetch(REST+'/api/games?gameType=UNO&limit=10')).json(),record=records.find(r=>r.roomId===room);assert.ok(record);
  const detail=await(await fetch(REST+'/api/games/'+record.id)).json();
  check('浏览器自然对局保存全部有效行动',detail.moveHistory.length===await desktop.eval('moveSeq')&&detail.moveHistory.filter(m=>m[1]===102).length===observed.end);
  check('新旧UNO战报均能解释出牌、换手与结束摸牌',await mobile.eval('replayActionText("UNO",[1,0,7,0,2,0]).includes("2 号玩家")&&replayActionText("UNO",[1,0,7,0]).includes("交换手牌")&&replayActionText("UNO",[1,102,0]).includes("结束摸牌")&&replayActionText("UNO",[1,0,0,0,0,1]).includes("最后一张")'));
  await mobile.eval('document.getElementById("gameover-overlay").classList.remove("show");document.getElementById("uno-panel").scrollIntoView({block:"start",behavior:"instant"})');await mobile.shot('uno-mobile-finished');
  await mobile.eval('openRecordReplay('+JSON.stringify(record.id)+',"UNO")');
  await until(()=>mobile.eval('document.getElementById("replay-modal").classList.contains("show")&&rmMoves.length==='+detail.moveHistory.length),'持久化战报打开');
  check('手机复盘弹窗与完整底栏无横向裁切且按钮至少44px',await mobile.eval('(()=>{const box=document.querySelector(".replay-modal-box"),r=box.getBoundingClientRect(),ctrl=document.querySelector(".replay-modal-ctrl");return box.scrollWidth<=box.clientWidth+1&&r.left>=0&&r.right<=innerWidth&&[...ctrl.children,...document.querySelectorAll(".replay-modal-head .leave-btn")].filter(e=>getComputedStyle(e).display!=="none").every(e=>{const b=e.getBoundingClientRect();return b.left>=r.left&&b.right<=r.right+1&&(e.tagName!=="BUTTON"||(b.width>=44&&b.height>=44))})})()')) ;
  check('实际持久化战报弹窗逐手解释UNO行动',await mobile.eval('document.querySelectorAll("#rm-rpg-log > div").length==='+detail.moveHistory.length+'&&!document.getElementById("rm-rpg-log").textContent.includes("行动 100")'+(observed.end>0?'&&document.getElementById("rm-rpg-log").textContent.includes("结束摸牌")':'')));
  await mobile.eval('rmSeek(0)');
  check('实际战报可回到起点并恢复完整记录',await mobile.eval('rmIndex===0&&document.getElementById("rm-rpg-log").textContent.includes("还没有")'));
  await mobile.eval('rmSeek(rmMoves.length)');await mobile.shot('uno-mobile-replay');
  check('所有页面没有脚本和控制台错误',chrome.errors.length===0,chrome.errors);
  console.log('[ui-uno] observed '+JSON.stringify(observed));console.log('[ui-uno] PASS '+passed+'/'+passed);
} catch(error) {console.error('[ui-uno] FAIL',error.stack);for(const [i,page] of (chrome?.pages||[]).entries()){try{console.error('state',i,await page.eval('({gameState,myStone,moveSeq,currentTurn,view:rpgView})'));await page.shot('failure-'+i)}catch{}}process.exitCode=1;}
finally{if(chrome)await chrome.close();}
