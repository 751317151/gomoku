import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';

const CDP = process.env.CDP_PORT || 9355;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'target/gem-collection-validation/shots';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let passed = 0;
function check(label, condition, detail) {
  assert.ok(condition, label + (detail ? ' :: ' + JSON.stringify(detail) : ''));
  passed++;
  console.log('[ui-gem-collection] PASS ' + label);
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
  const desktop=await chrome.page('收藏桌面',1280,1000,false);
  const mobile=await chrome.page('收藏手机',390,844,true);
  const pages=[desktop,mobile];
  await desktop.eval('document.querySelector("[data-game-type=GEM_AUCTION] [data-rules]").click()');
  check('教程覆盖三色收藏暗投与弃拍回收',await desktop.eval('(()=>{const t=document.getElementById("rules-body").textContent;return t.includes("三色")&&t.includes("回收")&&t.includes("流拍")&&t.includes("叠加")})()'));
  await desktop.eval('document.getElementById("rules-close").click();document.querySelector("[data-game-type=GEM_AUCTION]").click();document.getElementById("room-player-count").value="2";createRoom()');
  await until(()=>desktop.eval('gameState==="WAITING" && !!roomId'),'创建收藏房间');
  const room=await desktop.eval('roomId');await mobile.eval('joinSpecificRoom('+JSON.stringify(room)+',"GEM_AUCTION")');
  await until(async()=>await desktop.eval('gameState==="PLAYING" && rpgView?.lots?.length===5')&&await mobile.eval('gameState==="PLAYING" && rpgView?.lots?.length===5'),'双人开局');
  const catalog=await desktop.eval('rpgView.lots');
  check('整份图录公开且两端一致',JSON.stringify(catalog)===await mobile.eval('JSON.stringify(rpgView.lots)')&&new Set(catalog.map(l=>l.color)).size===3);
  check('初始提供弃拍和十档合法出价',await mobile.eval('document.querySelectorAll("#gem-bids button:not(:disabled)").length===11 && document.getElementById("gem-bids").textContent.includes("弃拍")'));
  const layout=await mobile.eval('(()=>{const p=document.getElementById("gem-auction-panel").getBoundingClientRect();const c=document.getElementById("gem-catalog").getBoundingClientRect();return {width:innerWidth,scroll:document.documentElement.scrollWidth,left:p.left,right:p.right,catalogRight:c.right,buttons:[...document.querySelectorAll("#gem-bids button")].map(b=>b.getBoundingClientRect().height)}})()');
  check('390px 面板和五轮图录均不横向溢出',layout.scroll<=layout.width+1&&layout.left>=0&&layout.right<=layout.width+1&&layout.catalogRight<=layout.width+1,layout);
  check('手机全部竞价按钮达到44px触控高度',layout.buttons.length===11&&layout.buttons.every(h=>h>=44),layout);
  let seq=0,base=0,collection=[0,0,0];
  const synced=async()=>await desktop.eval('moveCount==='+seq)&&await mobile.eval('moveCount==='+seq);
  for(let round=1;round<=5;round++){
    const coins=await desktop.eval('rpgView.players.find(p=>p.side===myStone).coins');
    await sleep(350);await click(desktop,'#gem-bids [data-bid="5"]');seq++;await until(synced,'桌面暗投');
    check('第 '+round+' 轮提交后锁定且不泄露余额与出价',await desktop.eval('document.querySelectorAll("#gem-bids button:not(:disabled)").length===0 && rpgView.players.find(p=>p.side===myStone).coins==='+coins)
      &&await mobile.eval('!("bid" in rpgView.players.find(p=>p.side!==myStone))'));
    if(round===2){
      const before=await mobile.eval('JSON.stringify(rpgView)'),origin=await mobile.eval('performance.timeOrigin');
      await mobile.send('Page.reload');
      await until(()=>mobile.eval('performance.timeOrigin!=='+origin+' && typeof joinGame==="function"'),'刷新');
      await mobile.eval('document.getElementById("ws-url").value='+JSON.stringify(WS)+';joinGame()');
      await until(()=>mobile.eval('gameState==="PLAYING" && moveCount==='+seq),'重入');
      check('手机刷新重入恢复图录收藏与合法出价',await mobile.eval('JSON.stringify(rpgView)')===before);
    }
    const mobileBid=[5,0,1,0,1][round-1];
    await sleep(350);await click(mobile,'#gem-bids [data-bid="'+mobileBid+'"]',true);seq++;await until(synced,'手机出价后揭晓');
    if(round===1)check('一次触屏只发送一次出价',await mobile.eval('window.__sentMoves===1'));
    if(round===2)check('弃拍实际回收金币并展示上轮结果',await mobile.eval('rpgView.players.find(p=>p.side===myStone).coins===27 && document.getElementById("gem-players").textContent.includes("弃拍 / 回收 2")'));
    collection[catalog[round-1].color]++;base+=catalog[round-1].value;
    const bonus=collection.reduce((sum,n)=>sum+Math.floor(n/2)*3,0)+Math.min(...collection)*8;
    check('第 '+round+' 轮收藏与增量组合奖励一致',await desktop.eval('JSON.stringify(rpgView.players.find(p=>p.side===myStone).collection)')===JSON.stringify(collection)
      &&await desktop.eval('rpgView.players.find(p=>p.side===myStone).gems')===base+bonus);
    if(round===3){
      const contrast=await mobile.eval('(()=>{const e=document.querySelector("#gem-log div:last-child"),p=document.getElementById("gem-log");const lum=c=>{const v=c.match(/[0-9.]+/g).slice(0,3).map(x=>{const n=Number(x)/255;return n<=0.04045?n/12.92:((n+0.055)/1.055)**2.4});return v[0]*.2126+v[1]*.7152+v[2]*.0722};const a=lum(getComputedStyle(e).color),b=lum(getComputedStyle(p).backgroundColor);return (Math.max(a,b)+.05)/(Math.min(a,b)+.05)})()');
      check('最新拍卖日志在浅底上清晰可读',contrast>=4.5,contrast);
      for(const page of pages)await page.eval('document.getElementById("gem-auction-panel").scrollIntoView({block:"start",behavior:"instant"})');
      await desktop.shot('gem-desktop');await mobile.shot('gem-mobile-catalog');
      await mobile.eval('document.getElementById("gem-players").scrollIntoView({block:"center",behavior:"instant"})');
      await mobile.shot('gem-mobile-collection');
    }
  }
  await until(async()=>await desktop.eval('gameState==="OVER"')&&await mobile.eval('gameState==="OVER"'),'自然终局');
  check('双方总分一致且结算解释包含收藏建议',await desktop.eval('JSON.stringify(rpgView.players.map(p=>p.gems))')===await mobile.eval('JSON.stringify(rpgView.players.map(p=>p.gems))')
    &&await desktop.eval('document.getElementById("gameover-insight").textContent.includes("三色")'));
  const records=await(await fetch(REST+'/api/games?gameType=GEM_AUCTION&limit=10')).json();
  const record=records.find(r=>r.roomId===room);assert.ok(record);
  await mobile.eval('document.getElementById("gameover-overlay").classList.remove("show");openRecordReplay('+JSON.stringify(record.id)+',"GEM_AUCTION")');
  await until(()=>mobile.eval('document.getElementById("replay-modal").classList.contains("show") && rmMoves.length==='+seq),'战报回放');
  await mobile.eval('rmSeek(rmMoves.length)');
  check('回放解释宝石颜色组合奖励与弃拍回收',await mobile.eval('(()=>{const t=document.getElementById("rm-rpg-log").textContent;return t.includes("组合 +")&&t.includes("弃拍，回收")&&t.includes("祖母绿")})()'));
  check('旧五列记录保留原出价语义',await mobile.eval('replayActionText("GEM_AUCTION",[1,0,3,30,3]).includes("出价 0")'));
  check('所有页面无运行时与控制台错误',chrome.errors.length===0,chrome.errors);
  console.log('[ui-gem-collection] PASS '+passed+'/'+passed);
}catch(error){
  console.error('[ui-gem-collection] FAIL',error.stack);
  for(const [i,page] of (chrome?.pages||[]).entries())try{console.error('[ui-gem-collection] state',i,await page.eval('({gameState,moveCount,actionVersion,view:rpgView})'));await page.shot('gem-failure-'+i);}catch{}
  process.exitCode=1;
}finally{if(chrome)await chrome.close();}
