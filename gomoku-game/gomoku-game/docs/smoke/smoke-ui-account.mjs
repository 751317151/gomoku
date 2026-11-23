// 账号 UI 真实浏览器冒烟：三态切换 → 注册（游客升级）→ 登录态显示 → 刷新恢复 → 退出 → 登录。
// 需要先起 headless Chrome（见 README），CDP_PORT 默认 9333。
const CDP_PORT = process.env.CDP_PORT || 9333;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const OUT = process.env.OUT_DIR || 'F:/tmp';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[ui-account] PASS ' + name); }
  else { failed++; console.error('[ui-account] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}
async function waitUntil(checkFn, label, timeout = 10000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await checkFn()) return; await sleep(150); }
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
    const r = await this.send('Runtime.evaluate', {
      expression: expr, returnByValue: true, awaitPromise: true
    });
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

  const name = 'ui' + Date.now().toString(36);
  await cdp.send('Page.navigate', { url: APP });
  await waitUntil(() => cdp.eval("typeof switchAuthTab === 'function' && !!document.getElementById('tab-register')"), '登录页脚本就绪');
  // 等首屏 restoreAccount 的异步请求结束后再切页签，避免它晚到把注册表单重置成游客态。
  await waitUntil(() => cdp.eval("typeof restoreAccountDone !== 'undefined' && restoreAccountDone === true"), '登录态恢复请求结束');

  // 1. 初始为游客态，账号字段隐藏
  check('初始游客态', await cdp.eval("document.getElementById('tab-guest').classList.contains('active') && document.getElementById('account-fields').hidden"));

  // 2. 切到注册，字段出现、按钮文案变化
  await cdp.eval("switchAuthTab('register')");
  check('切换到注册态', await cdp.eval("!document.getElementById('account-fields').hidden && document.getElementById('join-button').textContent.includes('注册')"),
    await cdp.eval("JSON.stringify({hidden:document.getElementById('account-fields').hidden,button:document.getElementById('join-button').textContent,tab:document.getElementById('tab-register').className})"));

  // 3. 填用户名密码，点注册
  await cdp.eval(`document.getElementById('player-name').value = '账号UI测试';
    document.getElementById('account-username').value = '${name}';
    document.getElementById('account-password').value = 'secret123';
    authMode = 'register';`);
  await cdp.eval("joinGame()");
  await waitUntil(() => cdp.eval("document.getElementById('account-state').textContent.includes('已登录')"), '注册后显示已登录');
  check('注册后显示已登录', await cdp.eval("document.getElementById('account-state').textContent.includes('已登录')"));
  check('注册后切回游客态(避免重复注册卡死)', await cdp.eval("document.getElementById('tab-guest').classList.contains('active') && document.getElementById('join-button').textContent === '进入大厅'"));
  check('注册后 token 已持久化', await cdp.eval("!!localStorage.getItem('gomoku_guest')"));
  check('退出按钮出现', await cdp.eval("!document.getElementById('account-logout').hidden"));
  await cdp.shot(OUT + '/account-ui-registered.png');

  // 4. 刷新页面，登录态自动恢复
  await cdp.send('Page.reload', { ignoreCache: true });
  await waitUntil(() => cdp.eval("typeof restoreAccount === 'function' && document.getElementById('account-state').textContent.includes('已登录')"), '刷新后恢复登录态', 15000);
  check('刷新后登录态恢复', await cdp.eval("document.getElementById('account-state').textContent.includes('已登录')"));
  check('刷新后昵称回填', await cdp.eval("document.getElementById('player-name').value === '账号UI测试'"));

  // 5. 退出账号
  await cdp.eval("accountLogout()");
  check('退出后回到游客态', await cdp.eval("document.getElementById('account-state').textContent.includes('游客') && document.getElementById('account-logout').hidden"));
  check('退出后 token 清空', await cdp.eval("!localStorage.getItem('gomoku_guest')"));

  // 6. 用刚才注册的账号重新登录
  await cdp.eval("switchAuthTab('login')");
  check('切换到登录态', await cdp.eval("document.getElementById('join-button').textContent.includes('登录')"));
  await cdp.eval(`document.getElementById('account-username').value = '${name}';
    document.getElementById('account-password').value = 'secret123';
    authMode = 'login';`);
  await cdp.eval("joinGame()");
  await waitUntil(() => cdp.eval("document.getElementById('account-state').textContent.includes('已登录')"), '登录成功后显示已登录');
  check('重新登录成功', await cdp.eval("document.getElementById('account-state').textContent.includes('已登录')"));
  check('登录后切回游客态', await cdp.eval("document.getElementById('tab-guest').classList.contains('active')"));

  console.log(failed === 0
    ? '[ui-account] PASS (' + passed + '/' + passed + ')'
    : '[ui-account] FAIL (' + failed + ' failed, ' + passed + ' passed)');
  process.exitCode = failed === 0 ? 0 : 1;
  ws.close();
})().catch((e) => {
  failed++;
  console.error('[ui-account] FAIL 异常 :: ' + (e && e.message));
  process.exitCode = 1;
});
