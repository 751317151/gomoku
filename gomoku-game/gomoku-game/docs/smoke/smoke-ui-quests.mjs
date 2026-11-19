// 每日任务 UI 真实浏览器冒烟：预置 3 局进度 → 进入大厅 → 任务卡片渲染 → 点领取 →
// 状态变「已领取」、经验增加、服务端记录已领 → 刷新后仍是已领取。
// 需要先起 headless Chrome（CDP_PORT 默认 9336）与测试服务。
const CDP_PORT = process.env.CDP_PORT || 9336;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'F:/tmp/shot';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[ui-quests] PASS ' + name); }
  else { failed++; console.error('[ui-quests] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}
async function waitUntil(fn, label, timeout = 12000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await fn()) return; await sleep(150); }
  throw new Error('等待超时: ' + label);
}

const NAME = '任务UI' + Math.floor(Math.random() * 10000);
let token = null;

function playSoloAndSurrender(gameType) {
  return new Promise((resolve) => {
    const st = { started: false, over: false, error: null };
    const ws = new WebSocket(WS_URL);
    let settled = false;
    const finish = (ok) => {
      if (settled) return;
      settled = true;
      try { ws.close(); } catch (_) {}
      resolve(Object.assign({ ok }, st));
    };
    ws.onopen = () => {
      ws.send(JSON.stringify({
        type: 'JOIN', playerName: NAME, gameType, createRoom: true, guestToken: token
      }));
      setTimeout(() => { try { ws.send(JSON.stringify({ type: 'ADD_AI', data: '1' })); } catch (_) {} }, 500);
    };
    ws.onmessage = (ev) => {
      let m; try { m = JSON.parse(ev.data); } catch { return; }
      if (m.type === 'ERROR') st.error = m.message;
      if (m.type === 'GAME_START') {
        st.started = true;
        setTimeout(() => { try { ws.send(JSON.stringify({ type: 'SURRENDER' })); } catch (_) {} }, 250);
      }
      if (m.type === 'GAME_OVER') { st.over = true; setTimeout(() => finish(true), 300); }
    };
    ws.onerror = () => finish(false);
    setTimeout(() => finish(st.over), 9000);
  });
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
  console.log('== 每日任务 UI（真实浏览器）==');

  // 1. 预置身份与 3 局对局记录（任务进度派生自记录，不走任何测试专用接口）
  const guest = await (await fetch(REST + '/api/guest', { method: 'POST' })).json();
  token = guest.token;
  check('游客身份签发', !!token);
  for (let i = 1; i <= 3; i++) {
    const game = await playSoloAndSurrender('GOMOKU');
    check('预置第 ' + i + ' 局完成', game.ok && game.over, JSON.stringify({ error: game.error }));
  }
  let claimable = false;
  for (let i = 0; i < 30 && !claimable; i++) {
    const status = await (await fetch(REST + '/api/quests', { headers: { Authorization: 'Bearer ' + token } })).json();
    claimable = (status.quests || []).some(q => q.id === 'games-3' && q.claimable);
    if (!claimable) await sleep(250);
  }
  check('预置进度已就绪（三局任务可领）', claimable);

  // 2. 打开页面，预写游客凭证与新手引导标记
  const ws = new WebSocket(await getWsUrl());
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  const cdp = new Cdp(ws);
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' }, false);
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true }, false);
  cdp.sessionId = sessionId;
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `try{localStorage.setItem('gomoku_guest', ${JSON.stringify(token)});`
      + `localStorage.setItem('gomoku_onboarded','1');}catch(_){ };`
      + `window.alert=function(m){window.__lastAlert=String(m);};window.confirm=function(){return true;};`
  });
  await cdp.send('Page.navigate', { url: APP });
  await waitUntil(() => cdp.eval("typeof joinGame === 'function'"), '登录页就绪');
  await cdp.eval("document.getElementById('ws-url').value = " + JSON.stringify(WS_URL)
    + "; document.getElementById('player-name').value = " + JSON.stringify(NAME) + "; joinGame();");
  await waitUntil(() => cdp.eval("document.getElementById('lobby-screen').style.display === 'block'"), '进入大厅');

  // 3. 任务卡片渲染
  await waitUntil(() => cdp.eval("document.querySelectorAll('#daily-progress .daily-item').length === 5"), '任务卡片渲染');
  check('任务卡片共 5 项', await cdp.eval("document.querySelectorAll('#daily-progress .daily-item').length") === 5);
  check('三局任务显示可领取', await cdp.eval(
    "Array.from(document.querySelectorAll('#daily-progress .daily-item'))"
    + ".some(el => el.textContent.includes('热身三局') && !!el.querySelector('.quest-claim'))"));
  check('未完成任务显示进度而非按钮', await cdp.eval(
    "Array.from(document.querySelectorAll('#daily-progress .daily-item'))"
    + ".some(el => el.textContent.includes('旗开得胜') && !el.querySelector('.quest-claim'))"));
  const xpBefore = await cdp.eval("document.getElementById('level-xp').textContent");
  await cdp.eval("document.getElementById('daily-progress').scrollIntoView({ block: 'center' })");
  await sleep(300);
  await cdp.shot(OUT + '/quests-before.png');

  // 4. DOM 点击领取
  await cdp.eval("document.querySelector('#daily-progress .quest-claim').click()");
  await waitUntil(() => cdp.eval(
    "Array.from(document.querySelectorAll('#daily-progress .daily-item'))"
    + ".some(el => el.textContent.includes('热身三局') && el.textContent.includes('已领取'))"), '领取完成', 10000);
  check('领取后按钮变已领取', await cdp.eval(
    "Array.from(document.querySelectorAll('#daily-progress .daily-item'))"
    + ".some(el => el.textContent.includes('热身三局') && el.textContent.includes('已领取'))"));
  await waitUntil(() => cdp.eval("document.getElementById('level-xp').textContent !== " + JSON.stringify(xpBefore)), '经验刷新', 8000).catch(() => {});
  const xpAfter = await cdp.eval("document.getElementById('level-xp').textContent");
  check('领取后经验变化', xpAfter !== xpBefore, xpBefore + ' -> ' + xpAfter);
  await cdp.eval("document.getElementById('daily-progress').scrollIntoView({ block: 'center' })");
  await sleep(300);
  await cdp.shot(OUT + '/quests-after.png');

  // 5. 服务端状态一致
  const serverStatus = await (await fetch(REST + '/api/quests', { headers: { Authorization: 'Bearer ' + token } })).json();
  const gamesQuest = (serverStatus.quests || []).find(q => q.id === 'games-3');
  check('服务端记录已领取', gamesQuest && gamesQuest.claimed === true);
  check('服务端累计任务经验为 30', serverStatus.bonusXp === 30, 'bonus=' + serverStatus.bonusXp);

  // 6. 刷新后仍为已领取（等 timeOrigin 变化，确保新文档已接管）
  const reloadOrigin = await cdp.eval("performance.timeOrigin");
  await cdp.send('Page.reload', { ignoreCache: true });
  await waitUntil(async () => (await cdp.eval("performance.timeOrigin")) !== reloadOrigin
    && (await cdp.eval("typeof joinGame === 'function'")), '刷新后登录页');
  await cdp.eval("document.getElementById('ws-url').value = " + JSON.stringify(WS_URL) + "; joinGame();");
  await waitUntil(() => cdp.eval("document.getElementById('lobby-screen').style.display === 'block'"), '刷新后进大厅');
  await waitUntil(() => cdp.eval(
    "Array.from(document.querySelectorAll('#daily-progress .daily-item'))"
    + ".some(el => el.textContent.includes('热身三局') && el.textContent.includes('已领取'))"), '刷新后仍已领取', 12000);
  check('刷新后仍显示已领取', await cdp.eval(
    "Array.from(document.querySelectorAll('#daily-progress .daily-item'))"
    + ".some(el => el.textContent.includes('热身三局') && el.textContent.includes('已领取'))"));
  check('刷新后不再出现该任务的领取按钮', await cdp.eval(
    "Array.from(document.querySelectorAll('#daily-progress .daily-item'))"
    + ".filter(el => el.textContent.includes('热身三局'))[0].querySelector('.quest-claim') === null"));

  console.log(failed === 0
    ? '[ui-quests] PASS (' + passed + '/' + passed + ')'
    : '[ui-quests] FAIL (' + failed + ' failed, ' + passed + ' passed)');
  try { ws.close(); } catch (_) {}
  process.exit(failed === 0 ? 0 : 1);
})().catch((e) => {
  failed++;
  console.error('[ui-quests] FAIL 异常 :: ' + (e && e.message));
  process.exit(1);
});
