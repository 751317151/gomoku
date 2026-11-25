import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';

const CDP = process.env.CDP_PORT || 9354;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'target/dice-press-validation/shots';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let passed = 0;
function check(label, condition, detail) {
  assert.ok(condition, label + (detail ? ' :: ' + JSON.stringify(detail) : ''));
  passed++;
  console.log('[ui-dice-press] PASS ' + label);
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

async function click(page, selector, touch=false) {
  await page.eval('document.querySelector('+JSON.stringify(selector)+').scrollIntoView({block:"center",behavior:"instant"})');
  const point=await page.eval('(()=>{const node=document.querySelector('+JSON.stringify(selector)+');if(node.disabled)throw new Error("按钮不可用");const r=node.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2};})()');
  if(touch) { await page.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[point]}); await page.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]}); }
  else { await page.send('Input.dispatchMouseEvent',{type:'mousePressed',button:'left',clickCount:1,...point}); await page.send('Input.dispatchMouseEvent',{type:'mouseReleased',button:'left',clickCount:1,...point}); }
}
let chrome;
try {
  const info=await(await fetch('http://127.0.0.1:'+CDP+'/json/version')).json();
  const socket=new WebSocket(info.webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject;});
  chrome=new Chrome(socket);
  const desktop=await chrome.page('竞速桌面',1280,1000,false);
  const mobile=await chrome.page('竞速手机',390,844,true);
  const pages=[desktop,mobile];
  await desktop.eval('document.querySelector("[data-game-type=DICE_RACE] [data-rules]").click()');
  check('教程解释收分加掷与一次护盾',await desktop.eval('(()=>{const t=document.getElementById("rules-body").textContent;return t.includes("每轮最多一次")&&t.includes("一枚护盾")&&t.includes("不保护首骰")})()'));
  await desktop.eval('document.getElementById("rules-close").click();document.querySelector("[data-game-type=DICE_RACE]").click();document.getElementById("room-player-count").value="2";createRoom()');
  await until(()=>desktop.eval('gameState==="WAITING" && !!roomId'),'创建房间');
  const room=await desktop.eval('roomId');
  await mobile.eval('joinSpecificRoom('+JSON.stringify(room)+',"DICE_RACE")');
  await until(async()=>await desktop.eval('gameState==="PLAYING" && rpgView?.phase==="CHOOSE"')&&await mobile.eval('gameState==="PLAYING" && rpgView?.phase==="CHOOSE"'),'双人开局');
  check('默认阶段显示三种策略及一次护盾',await mobile.eval('document.querySelectorAll("#dice-choices button:not(:disabled)").length===3 && document.getElementById("dice-stakes").textContent.includes("护盾剩余 1 次")'));
  let seq=0,presses=0,reconnected=false,shielded=false;
  const synced=async()=>await desktop.eval('moveCount==='+seq)&&await mobile.eval('moveCount==='+seq);
  for(let round=1;round<=6;round++) {
    await click(desktop,'#dice-choices [data-choice="1"]'); seq++; await until(synced,'首位暗选');
    check('第 '+round+' 轮提交后按钮锁定且对手不能看到策略',await desktop.eval('document.querySelectorAll("#dice-choices button:not(:disabled)").length===0')
      &&await mobile.eval('!("choice" in rpgView.players.find(p=>p.side!==myStone))'));
    await click(mobile,'#dice-choices [data-choice="1"]',true); seq++; await until(synced,'双人首骰');
    if(await desktop.eval('rpgView.phase==="PRESS"')) {
      presses++;
      if(!reconnected) {
        const before=await mobile.eval('JSON.stringify(rpgView)');
        const origin=await mobile.eval('performance.timeOrigin'); await mobile.send('Page.reload');
        await until(()=>mobile.eval('performance.timeOrigin!=='+origin+' && typeof joinGame==="function"'),'刷新');
        await mobile.eval('document.getElementById("ws-url").value='+JSON.stringify(WS)+';joinGame()');
        await until(()=>mobile.eval('gameState==="PLAYING" && rpgView?.phase==="PRESS" && moveCount==='+seq),'刷新重入');
        check('手机刷新重入恢复首骰护盾和可选动作',await mobile.eval('JSON.stringify(rpgView)')===before);
        reconnected=true;
      }
      check('第 '+round+' 轮加掷阶段明确显示待收分与风险',await mobile.eval('document.getElementById("dice-stakes").textContent.includes("本轮待收") && document.getElementById("dice-status").textContent.length>0'));
      if(presses===1) {
        const layout=await mobile.eval('(()=>{const r=document.getElementById("dice-race-panel").getBoundingClientRect();return {width:innerWidth,scroll:document.documentElement.scrollWidth,left:r.left,right:r.right,buttons:[...document.querySelectorAll("#dice-choices button")].map(b=>b.getBoundingClientRect().height)}})()');
        check('390px 手机面板无横向溢出',layout.scroll<=layout.width+1&&layout.left>=0&&layout.right<=layout.width+1,layout);
        check('手机三种行动均达到44px触控高度',layout.buttons.length===3&&layout.buttons.every(h=>h>=44),layout);
        await desktop.eval('document.getElementById("dice-race-panel").scrollIntoView({block:"start",behavior:"instant"})');
        await mobile.eval('document.getElementById("dice-race-panel").scrollIntoView({block:"start",behavior:"instant"})');
        await desktop.shot('dice-press-desktop'); await mobile.shot('dice-press-mobile');
      }
      const actors=[];
      for(const page of pages) if(await page.eval('rpgView.legalActions.length>0')) actors.push(page);
      for(const page of actors) {
        const shield=await page.eval('rpgView.legalActions.includes(5)');
        const action=shield ? 5 : presses%2 ? 3 : 4;
        await sleep(350); await click(page,'#dice-choices [data-choice="'+action+'"]',page===mobile); seq++;
        await until(synced,'真实收分或加掷'); if(shield)shielded=true;
      }
    }
    if(round<6) check('第 '+round+' 轮结束后两人均可继续选择',await desktop.eval('rpgView.phase==="CHOOSE" && rpgView.round==='+(round+1)+' && document.querySelectorAll("#dice-choices button:not(:disabled)").length===3')
      &&await mobile.eval('rpgView.phase==="CHOOSE" && rpgView.legalActions.length===3'));
  }
  await until(async()=>await desktop.eval('gameState==="OVER"')&&await mobile.eval('gameState==="OVER"'),'自然终局');
  check('完整六轮经历真实加掷护盾与重连',presses>0&&shielded&&reconnected);
  check('结算解释包含玩法并且比分一致',await mobile.eval('document.getElementById("gameover-insight").textContent.includes("骰子竞速")')
    &&await desktop.eval('JSON.stringify(rpgView.players.map(p=>p.score))')===await mobile.eval('JSON.stringify(rpgView.players.map(p=>p.score))'));
  const records=await(await fetch(REST+'/api/games?gameType=DICE_RACE&limit=10')).json();
  const record=records.find(r=>r.roomId===room); assert.ok(record);
  await mobile.eval('document.getElementById("gameover-overlay").classList.remove("show");openRecordReplay('+JSON.stringify(record.id)+',"DICE_RACE")');
  await until(()=>mobile.eval('document.getElementById("replay-modal").classList.contains("show") && rmMoves.length==='+seq),'战报回放');
  await mobile.eval('rmSeek(rmMoves.length)');
  check('存档回放显示首骰和护盾加掷记录',await mobile.eval('(()=>{const t=document.getElementById("rm-rpg-log").textContent;return t.includes("首骰")&&t.includes("护盾加掷")&&!t.includes("策略 5")})()'));
  check('旧四列棋谱仍可翻译',await mobile.eval('replayActionText("DICE_RACE",[1,0,4,10]).includes("稳健")'));
  check('页面无运行时或控制台错误',chrome.errors.length===0,chrome.errors);
  console.log('[ui-dice-press] PASS '+passed+'/'+passed);
} catch(error) {
  console.error('[ui-dice-press] FAIL',error.stack);
  for(const [index,page] of (chrome?.pages||[]).entries()) { try { console.error('[ui-dice-press] state',index,await page.eval('({gameState,moveCount,actionVersion,view:rpgView})'));await page.shot('dice-failure-'+index); } catch {} }
  process.exitCode=1;
} finally { if(chrome)await chrome.close(); }
