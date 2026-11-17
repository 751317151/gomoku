// 赛季 UI 真实浏览器冒烟：进入大厅 → 排行榜三态切换 → 赛季模式显示横幅与榜单。
// 需要先起 headless Chrome（CDP_PORT 默认 9335）。
const CDP_PORT = process.env.CDP_PORT || 9335;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'F:/tmp';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[ui-season] PASS ' + name); }
  else { failed++; console.error('[ui-season] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
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
      setTimeout(() => { if (this.pending.has(id)) { this.pending.delete(id); reject(new Error('CDP 超时: ' + method)); } }, 30000);
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
  await cdp.eval("try{localStorage.removeItem('gomoku_guest');localStorage.setItem('gomoku_onboarded','1');}catch(_){ }");
  await cdp.send('Page.reload', { ignoreCache: true });
  await waitUntil(() => cdp.eval("typeof joinGame === 'function'"), '重载后登录页');
  await cdp.eval("document.getElementById('ws-url').value = " + JSON.stringify(WS_URL) + "; document.getElementById('player-name').value = '赛季UI'; joinGame();");
  await waitUntil(() => cdp.eval("document.getElementById('lobby-screen').style.display === 'block'"), '进入大厅');

  // 1. 初始为 ELO 总榜，赛季横幅隐藏
  await waitUntil(() => cdp.eval("leaderboardMode === 'elo'"), '初始 ELO 模式');
  check('初始为 ELO 总榜', await cdp.eval("leaderboardMode === 'elo' && document.getElementById('season-banner').hidden"));

  // 2. 切到本周活跃
  await cdp.eval("toggleLeaderboardMode()");
  check('切到本周活跃', await cdp.eval("leaderboardMode === 'weekly' && document.getElementById('lb-mode-toggle').textContent === '赛季段位'"));

  // 3. 再切到赛季，横幅出现、榜单渲染
  await cdp.eval("toggleLeaderboardMode()");
  await waitUntil(() => cdp.eval("leaderboardMode === 'season' && !document.getElementById('season-banner').hidden"), '进入赛季模式');
  // 横幅是异步 fetch 填充，必须等它真正有文案，而不是仅不隐藏
  await waitUntil(() => cdp.eval("(document.getElementById('season-banner').textContent||'').includes('青铜')"), '赛季横幅填充完成', 10000);
  check('切到赛季模式', await cdp.eval("leaderboardMode === 'season'"));
  check('赛季横幅显示', await cdp.eval("!document.getElementById('season-banner').hidden"));
  check('横幅含剩余天数', await cdp.eval("document.getElementById('season-banner').textContent.includes('剩余') && document.getElementById('season-banner').textContent.includes('天')"));
  check('横幅含我的段位', await cdp.eval("document.getElementById('season-banner').textContent.includes('青铜')"));
  check('赛季榜有内容(空态或列表)', await cdp.eval("(document.getElementById('leaderboard-list').textContent||'').trim().length > 0"));
  await cdp.shot(OUT + '/season-ui.png');

  // 4. 再切回 ELO，横幅隐藏
  await cdp.eval("toggleLeaderboardMode()");
  check('切回 ELO 横幅隐藏', await cdp.eval("leaderboardMode === 'elo' && document.getElementById('season-banner').hidden"));

  console.log(failed === 0
    ? '[ui-season] PASS (' + passed + '/' + passed + ')'
    : '[ui-season] FAIL (' + failed + ' failed, ' + passed + ' passed)');
  process.exitCode = failed === 0 ? 0 : 1;
  ws.close();
})().catch((e) => {
  failed++;
  console.error('[ui-season] FAIL 异常 :: ' + (e && e.message));
  process.exitCode = 1;
});
