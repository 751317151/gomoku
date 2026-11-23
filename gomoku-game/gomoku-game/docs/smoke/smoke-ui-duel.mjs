// 双浏览器上下文验收：桌面发邀请、手机接收，拒绝/撤回/忙碌重试与真实开局切屏。
// 启动独立 Chrome --remote-debugging-port=9343；可覆盖 CDP_PORT/APP_URL/API_BASE/WS_URL/OUT_DIR。
import { mkdirSync, writeFileSync } from 'node:fs';
const CDP_PORT = process.env.CDP_PORT || 9343;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const API = process.env.API_BASE || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'target/duel-validation/shots';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let passed = 0, failed = 0;
function check(name, condition, detail) {
  if (condition) { passed++; console.log('[ui-duel] PASS ' + name); }
  else { failed++; console.error('[ui-duel] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}
async function until(predicate, label, timeout = 12000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { if (await predicate()) return; await sleep(100); }
  throw new Error('等待超时: ' + label);
}
async function request(path, token, method = 'GET', body) {
  const response = await fetch(API + path, {
    method, headers: { Authorization: 'Bearer ' + token, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(10000)
  });
  const result = await response.json();
  if (!response.ok) throw new Error(path + ': ' + JSON.stringify(result));
  return result;
}

class Page {
  constructor(ws) {
    this.ws = ws; this.sequence = 0; this.pending = new Map(); this.errors = [];
    ws.onmessage = event => {
      const message = JSON.parse(event.data);
      if (message.method === 'Runtime.exceptionThrown') {
        const details = message.params.exceptionDetails;
        this.errors.push(details.exception?.description || details.text);
      }
      if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
        this.errors.push(message.params.args.map(arg => arg.value || arg.description).join(' '));
      }
      const pending = this.pending.get(message.id);
      if (!pending) return;
      clearTimeout(pending.timer); this.pending.delete(message.id);
      if (message.error) pending.reject(new Error(JSON.stringify(message.error)));
      else pending.resolve(message.result);
    };
    ws.onclose = () => {
      for (const pending of this.pending.values()) {
        clearTimeout(pending.timer); pending.reject(new Error('Chrome connection closed'));
      }
      this.pending.clear();
    };
  }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { ws.close(); reject(new Error('Chrome handshake timed out')); }, 10000);
      ws.onopen = () => { clearTimeout(timer); resolve(); };
      ws.onerror = () => { clearTimeout(timer); reject(new Error('Chrome handshake failed')); };
    });
    return new Page(ws);
  }
  send(method, params = {}, useSession = true) {
    const id = ++this.sequence;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('CDP timeout: ' + method)); }, 15000);
      this.pending.set(id, { resolve, reject, timer });
      this.ws.send(JSON.stringify({ id, method, params,
        ...(useSession && this.sessionId ? { sessionId: this.sessionId } : {}) }));
    });
  }
  async eval(expression) {
    const result = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    return result.result.value;
  }
  async screenshot(name) {
    mkdirSync(OUT, { recursive: true });
    const result = await this.send('Page.captureScreenshot', { format: 'png' });
    writeFileSync(OUT + '/' + name + '.png', Buffer.from(result.data, 'base64'));
  }
}

let browser;
const pages = [], contexts = [];
try {
  const info = await (await fetch('http://127.0.0.1:' + CDP_PORT + '/json/version')).json();
  browser = await Page.connect(info.webSocketDebuggerUrl);
  async function openPlayer(name, width) {
    const context = (await browser.send('Target.createBrowserContext', {}, false)).browserContextId;
    contexts.push(context);
    const page = await Page.connect(info.webSocketDebuggerUrl);
    pages.push(page);
    const { targetId } = await page.send('Target.createTarget', { url: 'about:blank', browserContextId: context }, false);
    page.sessionId = (await page.send('Target.attachToTarget', { targetId, flatten: true }, false)).sessionId;
    await page.send('Page.enable'); await page.send('Runtime.enable');
    await page.send('Page.addScriptToEvaluateOnNewDocument', {
      source: "localStorage.setItem('gomoku_onboarded','1');window.alert=m=>{window.__lastAlert=String(m);};window.confirm=()=>true;"
    });
    await page.send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: width < 600 });
    await page.send('Page.navigate', { url: APP });
    await until(() => page.eval("typeof joinGame==='function'"), name + ' 登录页');
    await page.eval("document.getElementById('ws-url').value=" + JSON.stringify(WS_URL)
      + ";document.getElementById('player-name').value=" + JSON.stringify(name) + ';joinGame();');
    await until(() => page.eval("gameState==='LOBBY' && !!guestToken && ws.readyState===1"), name + ' 进入大厅');
    page.token = await page.eval('guestToken');
    page.identity = (await request('/api/guest', page.token, 'POST')).id;
    return page;
  }
  const a = await openPlayer('约战桌面甲', 1280);
  const b = await openPlayer('约战手机乙', 390);
  check('两个隔离页面持有独立身份', a.identity !== b.identity);
  const friendCode = (await request('/api/friends', a.token)).me.code;
  await request('/api/friends/requests', b.token, 'POST', { target: friendCode });
  await request('/api/friends/requests/' + b.identity + '/accept', a.token, 'POST');
  await a.eval('loadFriends()'); await b.eval('loadFriends()');
  await until(() => a.eval("!!document.querySelector('.friend-duel:not(:disabled)')"), '好友行约战按钮');
  check('约战选择包含16种现有玩法', await a.eval("document.getElementById('duel-game').options.length===16"));

  async function invite(type) {
    await a.eval("document.getElementById('duel-game').value=" + JSON.stringify(type)
      + ";duelGameChanged();document.querySelector('.friend-duel').click();");
    await until(() => b.eval("!!document.querySelector('#duel-invites .duel-accept')"), '手机收到邀请');
  }
  async function idle() {
    await until(async () => await a.eval("!document.querySelector('#duel-invites .duel-row') && !duelActions.size")
      && await b.eval("!document.querySelector('#duel-invites .duel-row') && !duelActions.size"), '双方清理邀请');
  }

  // 模拟只有约战 POST 长时间无响应；浏览器仍能正常拉取列表，超时后必须可再次操作。
  await a.eval("window.__duelOriginalFetch=window.fetch;window.fetch=function(url,options){"
    + "if(url==='/api/duels' && options?.method==='POST')return new Promise((resolve,reject)=>{"
    + "options.signal?.addEventListener('abort',()=>reject(new DOMException('Timeout','AbortError')),{once:true});});"
    + "return window.__duelOriginalFetch.apply(this,arguments);};document.querySelector('.friend-duel').click();");
  check('弱网请求处理中禁用重复邀请', await a.eval("document.querySelector('.friend-duel').disabled"));
  await until(() => a.eval("!duelActions.size && document.getElementById('duel-status').textContent.includes('超时')"), '弱网超时释放操作', 14000);
  await a.eval('window.fetch=window.__duelOriginalFetch;delete window.__duelOriginalFetch;');
  check('弱网超时后恢复按钮并提示确认状态', await a.eval("!document.querySelector('.friend-duel').disabled"
    + " && document.getElementById('duel-status').classList.contains('error')"));

  await invite('GOMOKU');
  check('手机收到邀请人姓名与玩法', await b.eval("document.getElementById('duel-invites').textContent.includes('约战桌面甲')"
    + " && document.getElementById('duel-invites').textContent.includes('五子棋')"));
  check('邀请有效期实时显示', await b.eval("/剩余 [012]:[0-5][0-9]/.test(document.getElementById('duel-invites').textContent)"));
  check('发起方进入等待回应状态', await a.eval("document.querySelector('.friend-duel').disabled"
    + " && document.querySelector('.friend-duel').textContent==='等待回应'"));
  check('发邀请后双方仍在大厅', await a.eval("gameState==='LOBBY'") && await b.eval("gameState==='LOBBY'"));
  const geometry = await b.eval("(()=>{const p=document.getElementById('duel-panel'),r=p.getBoundingClientRect();"
    + "const buttons=Array.from(p.querySelectorAll('button')).map(x=>{const b=x.getBoundingClientRect();return {left:b.left,right:b.right,height:b.height};});"
    + "return {width:innerWidth,scroll:document.documentElement.scrollWidth,left:r.left,right:r.right,buttons};})()");
  check('390px手机约战面板无横向溢出', geometry.scroll <= geometry.width + 1 && geometry.left >= 0
    && geometry.right <= geometry.width + 1, JSON.stringify(geometry));
  check('手机接受拒绝按钮达到44px触控高度且在屏内', geometry.buttons.length === 2
    && geometry.buttons.every(button => button.height >= 44 && button.left >= 0 && button.right <= geometry.width + 1), JSON.stringify(geometry));
  await b.eval("document.getElementById('duel-panel').scrollIntoView({block:'center'})");
  await b.screenshot('duel-mobile-invite');
  await b.eval("Array.from(document.querySelectorAll('#duel-invites button')).find(x=>x.textContent==='拒绝').click()");
  await idle();
  check('拒绝后双方邀请移除并可再邀请', await a.eval("!document.querySelector('.friend-duel').disabled"));
  check('发起方收到拒绝提示', await a.eval("document.getElementById('duel-status').textContent.includes('拒绝')"));

  await invite('UNO');
  await a.eval("document.querySelector('#duel-invites button').click()");
  await idle();
  check('撤回即时移除手机邀请', await b.eval("document.getElementById('duel-status').textContent.includes('撤回')"));

  await invite('GOMOKU');
  await a.eval("send({type:'JOIN',playerName:'约战桌面甲',gameType:'GOMOKU',createRoom:true,privateRoom:true,playerCount:2})");
  await until(() => a.eval("gameState==='WAITING'"), '发起人在其他房间等待');
  await b.eval("document.querySelector('#duel-invites .duel-accept').click()");
  await until(() => b.eval("!duelActions.size && document.getElementById('duel-status').classList.contains('error')"), '手机看到跨房间错误');
  check('好友忙碌时保留邀请并展示可理解错误', await b.eval("!!document.querySelector('#duel-invites .duel-accept')"
    + " && document.getElementById('duel-status').textContent.includes('房间')"));
  check('失败不会将发起人拽出等待房', await a.eval("gameState==='WAITING'"));
  await a.eval('leaveRoom()');
  await until(async () => {
    const friends = (await request('/api/friends', b.token)).friends || [];
    return friends.some(friend => friend.id === a.identity && !friend.roomId);
  }, '发起人返回大厅');
  await b.eval("document.getElementById('duel-panel').scrollIntoView({block:'center'})");
  await b.eval("document.querySelector('#duel-invites .duel-accept').click()");
  const visibleGame = "(()=>{const g=document.getElementById('game-screen'),l=document.getElementById('lobby-screen'),r=g.getBoundingClientRect();"
    + "return gameState==='PLAYING' && getComputedStyle(g).display!=='none' && getComputedStyle(l).display==='none' && r.width>0 && r.height>0;})()";
  await until(() => a.eval(visibleGame), '桌面切入对局');
  await until(() => b.eval(visibleGame), '手机切入对局');
  check('桌面与手机都切入可见的对局页面', await a.eval(visibleGame) && await b.eval(visibleGame));
  check('手机从页面下方接受后回到对局顶部', await b.eval('window.scrollY <= 1'),
    'scrollY=' + await b.eval('window.scrollY'));
  const gameA = await a.eval('({roomId, myStone, targetPlayers, count:Object.keys(players).length, game:currentGameType, session:!!mySessionId})');
  const gameB = await b.eval('({roomId, myStone, targetPlayers, count:Object.keys(players).length, game:currentGameType, session:!!mySessionId})');
  check('双方同房双人席位且分配不同棋色', gameA.roomId === gameB.roomId && gameA.myStone !== gameB.myStone
    && gameA.targetPlayers === 2 && gameB.targetPlayers === 2 && gameA.count === 2 && gameB.count === 2, JSON.stringify({ gameA, gameB }));
  check('双方收到可恢复的重连会话', gameA.session && gameB.session);
  const first = gameA.myStone === 1 ? a : b;
  const other = first === a ? b : a;
  await first.eval('sendMove(7,7)');
  await until(() => other.eval('board[7][7]===1'), '对手收到首手落子');
  check('约战后可正常落子并实时同步', await first.eval('board[7][7]===1') && await other.eval('board[7][7]===1'));
  await a.screenshot('duel-desktop-game');
  await b.screenshot('duel-mobile-game');
  const errors = pages.flatMap(page => page.errors);
  check('双方页面无脚本异常或控制台错误', errors.length === 0, JSON.stringify(errors));
  check('双方未弹出错误对话框', !await a.eval('window.__lastAlert') && !await b.eval('window.__lastAlert'));
} catch (error) {
  failed++; console.error('[ui-duel] FAIL 异常 :: ' + error.stack);
} finally {
  for (const page of pages) {
    try { await page.eval("if(typeof ws!=='undefined'&&ws?.readyState===1)send({type:'LEAVE'})"); } catch (_) {}
    page.ws.close();
  }
  if (browser) {
    for (const browserContextId of contexts) {
      try { await browser.send('Target.disposeBrowserContext', { browserContextId }, false); }
      catch (error) { failed++; console.error('[ui-duel] FAIL 清理 :: ' + error.message); }
    }
    browser.ws.close();
  }
}
console.log(failed ? '[ui-duel] FAIL (' + failed + ' failed, ' + passed + ' passed)' : '[ui-duel] PASS (' + passed + '/' + passed + ')');
process.exitCode = failed ? 1 : 0;
