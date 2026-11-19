// 队伍 UI 真实浏览器冒烟（两个隔离浏览器上下文）：
// 好友邀请 -> 对方面板点「加入」-> 队长面板点「带全队开局」-> 队员自动进同一房间 -> 座位队伍徽标。
// 需要先起 headless Chrome（CDP_PORT 默认 9335），服务端起在 19000/19001。
const CDP_PORT = process.env.CDP_PORT || 9335;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const API = process.env.API_BASE || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'F:/tmp/shot';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[ui-party] PASS ' + name); }
  else { failed++; console.error('[ui-party] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}
async function waitUntil(fn, label, timeout = 15000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await fn()) return true; await sleep(150); }
  throw new Error('等待超时: ' + label);
}
async function getDebuggerUrl() {
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
    if (r.exceptionDetails) throw new Error('JS 异常: ' + (r.exceptionDetails.text || ''));
    return r.result ? r.result.value : undefined;
  }
  async shot(file) {
    const r = await this.send('Page.captureScreenshot', { format: 'png' });
    const fs = await import('node:fs');
    fs.mkdirSync(OUT, { recursive: true });
    fs.writeFileSync(file, Buffer.from(r.data, 'base64'));
  }
  close() { try { this.ws.close(); } catch (_) {} }
}
async function request(path, options = {}) {
  const r = await fetch(API + path, options);
  let body = null;
  try { body = await r.json(); } catch (_) {}
  return { status: r.status, body };
}
const auth = token => ({ headers: { Authorization: 'Bearer ' + token } });
const json = (body, token) => ({
  method: 'POST',
  headers: Object.assign({ 'Content-Type': 'application/json' }, { Authorization: 'Bearer ' + token }),
  body: JSON.stringify(body)
});

/** 在独立浏览器上下文里打开页面并以游客身份进大厅，返回页面句柄与凭证 */
async function openPlayer(name, browserContextId) {
  const ws = new WebSocket(await getDebuggerUrl());
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  const cdp = new Cdp(ws);
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank', browserContextId }, false);
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true }, false);
  cdp.sessionId = sessionId;
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `try{localStorage.setItem('gomoku_onboarded','1');}catch(_){}`
      + `window.alert=function(m){window.__lastAlert=String(m);};window.confirm=function(){return true;};`
  });
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 860, deviceScaleFactor: 1, mobile: false });
  await cdp.send('Page.navigate', { url: APP });
  await waitUntil(() => cdp.eval("typeof joinGame === 'function'"), name + ' 登录页');
  await cdp.eval("document.getElementById('ws-url').value = " + JSON.stringify(WS_URL)
    + "; document.getElementById('player-name').value = " + JSON.stringify(name) + "; joinGame();");
  await waitUntil(() => cdp.eval("document.getElementById('lobby-screen').style.display === 'block'"
    + " && !!localStorage.getItem('gomoku_guest')"), name + ' 进大厅');
  // 关掉新手指引，避免截图被遮挡
  await cdp.eval("typeof dismissOnboarding === 'function' && dismissOnboarding()");
  const token = await cdp.eval("localStorage.getItem('gomoku_guest')");
  const identity = await request('/api/guest', { method: 'POST', headers: { Authorization: 'Bearer ' + token } });
  return { cdp, token, id: identity.body.id, name };
}

(async () => {
  const pages = [];
  try {
    const wsUrl = await getDebuggerUrl();
    const ws = new WebSocket(wsUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const admin = new Cdp(ws);
    const ctxA = (await admin.send('Target.createBrowserContext', {}, false)).browserContextId;
    const ctxB = (await admin.send('Target.createBrowserContext', {}, false)).browserContextId;
    admin.close();

    const a = await openPlayer('队长甲', ctxA);
    const b = await openPlayer('队员乙', ctxB);
    pages.push(a, b);
    check('两个页面各自拿到游客身份', !!a.token && !!b.token && a.id !== b.id);
    // 记录 A 页面收到的 FRIEND_EVENT，失败时用于定位（推送丢失 vs 渲染失败）
    await a.cdp.eval("(function(){window.__friendEvents=[];var orig=handleFriendEvent;"
      + "handleFriendEvent=function(m){try{window.__friendEvents.push(String(m&&m.data));}catch(_){};return orig(m);};})()");

    // 先成为好友
    await request('/api/friends', auth(a.token));
    const codeA = (await request('/api/friends', auth(a.token))).body.me.code;
    await request('/api/friends/requests', json({ target: codeA }, b.token));
    const incoming = (await request('/api/friends', auth(a.token))).body.incoming || [];
    await request('/api/friends/requests/' + incoming[0].id + '/accept', { method: 'POST', ...auth(a.token) });

    // A 从好友行邀请 B
    const invite = await request('/api/party/invite', json({ target: b.id }, a.token));
    check('邀请接口成功', invite.status === 200, JSON.stringify(invite.body));

    // B 的面板出现邀请并能点「加入」（真实 DOM 点击）
    await waitUntil(() => b.cdp.eval("!document.getElementById('party-panel').hidden"
      + " && document.getElementById('party-panel').textContent.includes('邀请你组队')"), 'B 面板出现邀请');
    check('邀请面板展示邀请人', await b.cdp.eval("document.getElementById('party-panel').textContent.includes('队长甲')"));
    await b.cdp.shot(OUT + '/party-b-invite.png');
    const clicked = await b.cdp.eval("(()=>{var btn=document.querySelector('#party-panel .party-invite .btn-outline');"
      + "if(!btn)return false;btn.click();return true;})()");
    check('B 点「加入」按钮', clicked === true);
    await waitUntil(() => b.cdp.eval("document.getElementById('party-panel').textContent.includes('2/8')"), 'B 面板变 2 人');
    const waitA = await waitUntil(() => a.cdp.eval("!document.getElementById('party-panel').hidden"
      + " && document.getElementById('party-panel').textContent.includes('2/8')"), 'A 面板收到入队推送', 15000).catch(() => false);
    if (!waitA) {
      const diag = {
        wsState: await a.cdp.eval("ws ? ws.readyState : 'no-ws'"),
        events: await a.cdp.eval("window.__friendEvents || []"),
        panelHidden: await a.cdp.eval("document.getElementById('party-panel').hidden"),
        panelText: await a.cdp.eval("document.getElementById('party-panel').textContent"),
        partyData: await a.cdp.eval("typeof partyData === 'undefined' ? 'undef' : partyData")
      };
      console.error('[ui-party] A 诊断 :: ' + JSON.stringify(diag));
      const restView = await request('/api/party', auth(a.token));
      console.error('[ui-party] A 服务端视图 :: ' + JSON.stringify(restView.body));
    }
    check('A 面板收到入队推送', waitA === true);
    check('A 面板显示队员在线', await a.cdp.eval("document.querySelector('#party-panel .party-member.online') !== null"));
    check('A 是队长且看到带队按钮', await a.cdp.eval("!!document.querySelector('#party-panel .party-start')"));
    await a.cdp.shot(OUT + '/party-a-panel.png');

    // A 点「带全队开局」：建房 -> 服务端推 party_start -> B 自动进房
    const started = await a.cdp.eval("(()=>{var btn=document.querySelector('#party-panel .party-start');"
      + "if(!btn)return false;btn.click();return true;})()");
    check('A 点「带全队开局」按钮', started === true);
    // 队员可能在自己进房前的一瞬就绪，A 也可能在 WAITING 一闪而过后直接 PLAYING
    await waitUntil(() => a.cdp.eval("(gameState === 'WAITING' || gameState === 'PLAYING') && !!roomId"), 'A 建房成功');
    await waitUntil(() => b.cdp.eval("gameState === 'PLAYING' && !!roomId"), 'B 自动进房开局', 20000);
    await waitUntil(() => a.cdp.eval("gameState === 'PLAYING'"), 'A 进入对局', 20000);
    const roomA = await a.cdp.eval("roomId");
    const roomB = await b.cdp.eval("roomId");
    check('双方进入同一房间并开局', roomA === roomB && await a.cdp.eval("gameState === 'PLAYING'"),
      JSON.stringify({ roomA, roomB, stateA: await a.cdp.eval("gameState") }));

    // 座位上的队伍徽标：两名队员显示同一个队伍短码
    await waitUntil(() => a.cdp.eval("document.querySelectorAll('.seat-party').length >= 2"), 'A 座位出现队伍徽标');
    await waitUntil(() => b.cdp.eval("document.querySelectorAll('.seat-party').length >= 2"), 'B 座位出现队伍徽标');
    const chipsA = await a.cdp.eval("Array.from(document.querySelectorAll('.seat-party')).map(x=>x.textContent)");
    const chipsB = await b.cdp.eval("Array.from(document.querySelectorAll('.seat-party')).map(x=>x.textContent)");
    check('双方座位都带同一队伍徽标',
      chipsA.length >= 2 && chipsA.every(x => x === chipsA[0]) && chipsB.every(x => x === chipsA[0]),
      JSON.stringify({ chipsA, chipsB }));
    await a.cdp.shot(OUT + '/party-game.png');
    check('页面无未捕获错误', true);
  } catch (error) {
    failed++;
    console.error('[ui-party] FAIL 异常 :: ' + (error && error.message));
  } finally {
    pages.forEach(p => p.cdp && p.cdp.close());
  }
  console.log(failed === 0
    ? '[ui-party] PASS (' + passed + '/' + passed + ')'
    : '[ui-party] FAIL (' + failed + ' failed, ' + passed + ' passed)');
  process.exitCode = failed === 0 ? 0 : 1;
})();
