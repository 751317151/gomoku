// 签到 UI 真实浏览器冒烟：进入大厅 → 签到卡片渲染 → 点签到 → 状态与经验变化 → 刷新仍已签。
// 需要先起 headless Chrome（CDP_PORT 默认 9334）。
const CDP_PORT = process.env.CDP_PORT || 9334;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
// 服务用 --netty.port=19001 起，前端 getWsUrl() 只认 9000/localhost，必须显式写 #ws-url
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'F:/tmp';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[ui-checkin] PASS ' + name); }
  else { failed++; console.error('[ui-checkin] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}
async function waitUntil(fn, label, timeout = 12000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await fn()) return; await sleep(150); }
  throw new Error('等待超时: ' + label);
}
async function getWsUrl() {
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
    fs.writeFileSync(file, Buffer.from(r.data, 'base64'));
  }
}
(async () => {
  const ws = new WebSocket(await getWsUrl());
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  const cdp = new Cdp(ws);
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' }, false);
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true }, false);
  cdp.sessionId = sessionId;
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `window.alert=function(m){window.__lastAlert=String(m);};window.confirm=function(){return true;};`
  });

  await cdp.send('Page.navigate', { url: APP });
  await waitUntil(() => cdp.eval("typeof joinGame === 'function'"), '登录页就绪');
  // 清掉旧游客凭证并重载，让内存里的 guestToken 也回到 null，保证全新身份
  await cdp.eval("try{localStorage.removeItem('gomoku_guest');}catch(_){ }");
  await cdp.send('Page.reload', { ignoreCache: true });
  await waitUntil(() => cdp.eval("typeof joinGame === 'function'"), '重载后登录页');

  // 以游客身份进入大厅
  await cdp.eval("document.getElementById('ws-url').value = " + JSON.stringify(WS_URL) + "; document.getElementById('player-name').value = '签到测试'; joinGame();");
  await waitUntil(() => cdp.eval("document.getElementById('lobby-screen').style.display === 'block' && !!localStorage.getItem('gomoku_guest')"), '进入大厅');

  // 1. 签到卡片存在且未签
  await waitUntil(() => cdp.eval("!!document.getElementById('checkin-btn') && !document.getElementById('checkin-btn').disabled"), '签到卡片就绪');
  check('签到卡片渲染', await cdp.eval("document.getElementById('checkin-title').textContent.includes('签到')"));
  check('初始按钮可点', await cdp.eval("document.getElementById('checkin-btn').textContent === '签到' && !document.getElementById('checkin-btn').disabled"));

  // 记录签到前的等级经验
  const xpBefore = await cdp.eval("document.getElementById('level-xp').textContent");
  await cdp.shot(OUT + '/checkin-before.png');

  // 2. 点签到（doCheckIn 异步 fetch，必须等文案真正变成「已签到」，不能只等 disabled——
  //    它在请求发出时就会 disabled 显示「签到中…」）
  await cdp.eval("doCheckIn()");
  await waitUntil(() => cdp.eval("document.getElementById('checkin-btn').textContent === '已签到'"), '签到完成', 10000);
  check('签到后按钮变已签到', await cdp.eval("document.getElementById('checkin-btn').textContent === '已签到'"));
  check('签到后标题显示连续天数', await cdp.eval("document.getElementById('checkin-title').textContent.includes('已签到')"));
  check('卡片标记 signed', await cdp.eval("document.getElementById('checkin-card').classList.contains('signed')"));

  // 3. 经验应增加（等级面板刷新）
  await waitUntil(() => cdp.eval("document.getElementById('level-xp').textContent !== '" + (xpBefore || '') + "'"), '经验刷新', 8000).catch(() => {});
  const xpAfter = await cdp.eval("document.getElementById('level-xp').textContent");
  check('签到后经验变化', xpAfter !== xpBefore, xpBefore + ' -> ' + xpAfter);
  await cdp.shot(OUT + '/checkin-after.png');

  // 4. 刷新页面仍为已签到
  await cdp.send('Page.reload', { ignoreCache: true });
  await waitUntil(() => cdp.eval("typeof joinGame === 'function'"), '刷新后登录页');
  await cdp.eval("document.getElementById('ws-url').value = " + JSON.stringify(WS_URL) + "; joinGame()");
  await waitUntil(() => cdp.eval("document.getElementById('lobby-screen').style.display === 'block'"), '刷新后进大厅');
  await waitUntil(() => cdp.eval("document.getElementById('checkin-btn').textContent === '已签到'"), '刷新后仍已签', 12000);
  check('刷新后仍显示已签到', await cdp.eval("document.getElementById('checkin-btn').textContent === '已签到'"));

  console.log(failed === 0
    ? '[ui-checkin] PASS (' + passed + '/' + passed + ')'
    : '[ui-checkin] FAIL (' + failed + ' failed, ' + passed + ' passed)');
  process.exitCode = failed === 0 ? 0 : 1;
  ws.close();
})().catch((e) => {
  failed++;
  console.error('[ui-checkin] FAIL 异常 :: ' + (e && e.message));
  process.exitCode = 1;
});
