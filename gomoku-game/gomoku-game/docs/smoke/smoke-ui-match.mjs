// 匹配体验 UI 冒烟（真实浏览器 + 真实 WS 对手）：
// 1) 大厅玩法卡显示「N 人等待」徽标；
// 2) 快速匹配把两个真实玩家送进同一间等待房并直接开局；
// 3) 无人可匹配时等待房助手条出现：已等待计时递增、提示暂无别人、可一键补齐电脑开局；
// 4) 补电脑后游戏开始、助手条隐藏，服务端总览从「等待」转为「进行中」。
// 需要先起 headless Chrome（CDP_PORT 默认 9340）与测试服务。
const CDP_PORT = process.env.CDP_PORT || 9340;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'F:/tmp/shot';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[ui-match] PASS ' + name); }
  else { failed++; console.error('[ui-match] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}
async function waitUntil(fn, label, timeout = 12000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await fn()) return true; await sleep(150); }
  console.error('[ui-match] 等待超时: ' + label);
  return false;
}

const NAME = '匹配UI' + Math.floor(Math.random() * 10000);

async function overview() {
  return await (await fetch(REST + '/api/match/overview')).json();
}
function typeStats(o, type) {
  return (o.games || []).find(g => g.gameType === type) || {};
}
async function waitOverview(pred, label, timeout = 12000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    const o = await overview();
    if (pred(o)) return o;
    await sleep(200);
  }
  throw new Error('等待总览超时: ' + label);
}

/** 用裸 WS 开一间公开等待房，作为浏览器玩家的真实匹配对手 */
function openWaitingRoom(name, gameType) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL);
    const st = { ws, roomId: null, gameStarted: false, error: null, types: [] };
    let settled = false;
    ws.onopen = () => ws.send(JSON.stringify({ type: 'JOIN', playerName: name, gameType, createRoom: false }));
    ws.onmessage = (ev) => {
      let m; try { m = JSON.parse(ev.data); } catch { return; }
      st.types.push(m.type);
      if (m.type === 'ERROR') st.error = m.message;
      if (m.type === 'GAME_START') st.gameStarted = true;
      if (m.type === 'WAITING' && m.roomId && !settled) {
        settled = true;
        st.roomId = m.roomId;
        resolve(st);
      }
    };
    ws.onerror = () => reject(new Error('对手 WS 连接失败'));
    setTimeout(() => { if (!settled) reject(new Error('等待房创建超时')); }, 9000);
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
  console.log('== 匹配体验（真实浏览器）==');

  // 1. 真实对手先在五子棋公开房等待
  const rival = await openWaitingRoom('对手' + Math.floor(Math.random() * 1000), 'GOMOKU');
  check('对手已建公开等待房', !!rival.roomId, JSON.stringify({ error: rival.error }));
  const before = await waitOverview(o => typeStats(o, 'GOMOKU').waitingRooms === 1, '五子棋等待房入库');
  check('总览记录五子棋 1 间等待房 / 1 人', typeStats(before, 'GOMOKU').waitingRooms === 1
    && typeStats(before, 'GOMOKU').waitingPlayers === 1, JSON.stringify(typeStats(before, 'GOMOKU')));

  // 2. 浏览器玩家进入大厅，玩法卡出现等待徽标
  const guest = await (await fetch(REST + '/api/guest', { method: 'POST' })).json();
  const token = guest.token;
  check('游客身份签发', !!token);

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

  const badgeReady = await waitUntil(() => cdp.eval(
    "(() => { const el = document.querySelector('#game-selector .game-card[data-game-type=\"GOMOKU\"] .game-card-activity');"
    + " return !!el && el.offsetParent !== null && el.textContent.includes('1 人等待'); })()"), '五子棋等待徽标', 15000);
  check('大厅玩法卡显示等待人数', badgeReady);
  check('无等待的其他玩法不显示徽标', await cdp.eval(
    "(() => { const el = document.querySelector('#game-selector .game-card[data-game-type=\"REVERSI\"] .game-card-activity');"
    + " return !!el && el.style.display === 'none'; })()"));
  await cdp.shot(OUT + '/match-badge.png');

  // 3. 快速匹配 → 两个真人进同一间房并开局
  await cdp.eval("document.querySelector('#game-selector .game-card[data-game-type=\"GOMOKU\"]').click()");
  await cdp.eval("document.querySelector('.lobby-actions .btn-match').click()");
  const started = await waitUntil(() => cdp.eval("gameState === 'PLAYING'"), '快速匹配开局', 12000);
  check('快速匹配直接开局', started);
  check('浏览器玩家坐进对手同一间房', await cdp.eval("roomId") === rival.roomId,
    String(await cdp.eval("roomId")) + ' vs ' + rival.roomId);
  check('开局速度足够快，对手收到 GAME_START', await waitUntil(() => rival.gameStarted, '对手开局', 5000));
  check('开局后等待助手条隐藏', await cdp.eval(
    "document.getElementById('match-helper').style.display === 'none'"));
  await waitUntil(async () => {
    const o = await overview();
    return typeStats(o, 'GOMOKU').playingRooms === 1 && typeStats(o, 'GOMOKU').waitingRooms === 0;
  }, '总览转为进行中');
  const playing = await overview();
  check('总览：五子棋转为 1 桌进行中、等待清零',
    typeStats(playing, 'GOMOKU').playingRooms === 1 && typeStats(playing, 'GOMOKU').waitingRooms === 0);

  // 4. 回大厅，换一个没人玩的玩法验证等待助手
  await cdp.eval(
    "Array.from(document.querySelectorAll('.leave-btn')).find(b => b.textContent.trim() === '离开').click()");
  await waitUntil(() => cdp.eval("document.getElementById('lobby-screen').style.display === 'block'"), '回到大厅');
  try { rival.ws.close(); } catch (_) {}

  await cdp.eval("document.querySelector('#game-selector .game-card[data-game-type=\"UNO\"]').click()");
  await cdp.eval("document.querySelector('.lobby-actions .btn-match').click()");
  check('无人可配时进入等待房', await waitUntil(() => cdp.eval("gameState === 'WAITING'"), 'UNO 等待', 10000));
  check('等待助手条出现', await waitUntil(() => cdp.eval(
    "document.getElementById('match-helper').style.display !== 'none'"), '助手条显示', 5000));
  const elapsedBefore = await cdp.eval("document.getElementById('match-elapsed').textContent");
  check('助手条显示已等待时长', /已等待 \d+:\d{2}/.test(elapsedBefore || ''), elapsedBefore);
  await sleep(1600);
  const elapsedAfter = await cdp.eval("document.getElementById('match-elapsed').textContent");
  check('已等待时长真实递增', elapsedAfter !== elapsedBefore, elapsedBefore + ' -> ' + elapsedAfter);
  const hintShown = await waitUntil(() => cdp.eval(
    "document.getElementById('match-hint').textContent.includes('暂时没有别人在等这个玩法')"), '暂无人提示', 10000);
  check('提示暂无其他等待玩家', hintShown);
  check('等待房提供补电脑/邀请/返回三个动作', await cdp.eval(
    "!!document.getElementById('match-fill-ai')"
    + " && Array.from(document.querySelectorAll('#match-helper button')).some(b => b.textContent.includes('复制邀请'))"
    + " && Array.from(document.querySelectorAll('#match-helper button')).some(b => b.textContent.includes('返回大厅'))"));
  check('助手条几何正常且不横向溢出', await cdp.eval(
    "(() => { const el = document.getElementById('match-helper'); const r = el.getBoundingClientRect();"
    + " return r.width > 150 && r.height > 70 && r.height < 260"
    + " && el.scrollWidth <= el.clientWidth + 1; })()"));
  const waitingOverview = await overview();
  check('总览：UNO 1 间等待房 / 1 人',
    typeStats(waitingOverview, 'UNO').waitingRooms === 1
    && typeStats(waitingOverview, 'UNO').waitingPlayers === 1,
    JSON.stringify(typeStats(waitingOverview, 'UNO')));
  await cdp.eval("document.getElementById('match-helper').scrollIntoView({ block: 'center' })");
  await sleep(300);
  await cdp.shot(OUT + '/match-helper.png');

  // 5. 一键补齐电脑 → 立即开局
  await cdp.eval("document.getElementById('match-fill-ai').click()");
  check('补电脑后立刻开局', await waitUntil(() => cdp.eval("gameState === 'PLAYING'"), '补电脑开局', 12000));
  check('补满后助手条隐藏', await cdp.eval(
    "document.getElementById('match-helper').style.display === 'none'"));
  check('四个座位全部坐满', await waitUntil(async () =>
    (await cdp.eval("document.querySelectorAll('#player-seats .player-card').length")) === 4, '四座位', 5000));
  check('三个空座由电脑补齐', await cdp.eval(
    "Object.values(players).filter(p => /电脑/.test(p.name)).length === 3"),
    String(await cdp.eval("Object.values(players).map(p => p.name).join(',')")));
  await waitUntil(async () => {
    const o = await overview();
    return typeStats(o, 'UNO').waitingRooms === 0 && typeStats(o, 'UNO').playingRooms === 1;
  }, 'UNO 总览转为进行中');
  const after = await overview();
  check('总览：UNO 等待清零、1 桌进行中',
    typeStats(after, 'UNO').waitingRooms === 0 && typeStats(after, 'UNO').playingRooms === 1);

  console.log(failed === 0
    ? '[ui-match] PASS (' + passed + '/' + passed + ')'
    : '[ui-match] FAIL (' + failed + ' failed, ' + passed + ' passed)');
  try { ws.close(); } catch (_) {}
  process.exit(failed === 0 ? 0 : 1);
})().catch((e) => {
  failed++;
  console.error('[ui-match] FAIL 异常 :: ' + (e && e.message));
  process.exit(1);
});
