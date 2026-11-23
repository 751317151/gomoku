// 成就墙（真实浏览器）：全新游客 → 打开成就墙 → 筛选 → 打一局人机 → 刷新后再看。
// 数据全部来自 /api/me/progress（对局记录派生），脚本不做任何测试专用写接口。
const CDP_PORT = process.env.CDP_PORT || 9338;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'F:/tmp/shot';
const TOTAL = 31; // 基础 11 + 全模式制霸 + 19 种玩法首胜
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[ui-achv] PASS ' + name); }
  else { failed++; console.error('[ui-achv] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}
async function waitUntil(fn, label, timeout = 12000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await fn()) return; await sleep(150); }
  throw new Error('等待超时: ' + label);
}

const NAME = '成就UI' + Math.floor(Math.random() * 10000);
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
    if (r.exceptionDetails) {
      const ex = r.exceptionDetails.exception || {};
      throw new Error('JS 异常: ' + (ex.description || ex.value || r.exceptionDetails.text || ''));
    }
    return r.result ? r.result.value : undefined;
  }
  async shot(file) {
    const r = await this.send('Page.captureScreenshot', { format: 'png' });
    const fs = await import('node:fs');
    fs.writeFileSync(file, Buffer.from(r.data, 'base64'));
  }
}

const wallState = () => `(function(){
  var modal = document.getElementById('achv-modal');
  var cards = Array.prototype.map.call(document.querySelectorAll('#achv-wall .achv-card'), function(c){
    var r = c.getBoundingClientRect();
    return { name: (c.querySelector('.achv-card-name') || {}).textContent || '',
             unlocked: c.classList.contains('unlocked'),
             text: c.textContent, x: r.x, y: r.y, w: r.width, h: r.height };
  });
  return { show: !!(modal && modal.classList.contains('show')),
           sub: (document.getElementById('achv-sub') || {}).textContent || '',
           cards: cards,
           empty: (document.querySelector('#achv-wall .achv-empty') || {}).textContent || '' };
})()`;

async function evalJson(cdp, expr) {
  return JSON.parse(await cdp.eval('JSON.stringify(' + expr + ')'));
}

async function findCard(cdp, name) {
  return evalJson(cdp, `(function(){
    var cards = document.querySelectorAll('#achv-wall .achv-card');
    for (var i = 0; i < cards.length; i++) {
      var n = cards[i].querySelector('.achv-card-name');
      if (n && n.textContent === ${JSON.stringify(name)}) {
        return { unlocked: cards[i].classList.contains('unlocked'), text: cards[i].textContent };
      }
    }
    return null;
  })()`);
}

async function clickTab(cdp, filter) {
  await cdp.eval("document.querySelector('.achv-tab[data-filter=\"" + filter + "\"]').click()");
}

async function serverProgress() {
  return (await fetch(REST + '/api/me/progress', { headers: { Authorization: 'Bearer ' + token } })).json();
}

(async () => {
  console.log('== 成就墙 UI（真实浏览器）==');
  console.log('   APP=' + APP + ' WS=' + WS_URL + ' CDP=' + CDP_PORT);

  // 1. 全新游客：31 项成就，全部未解锁
  const guest = await (await fetch(REST + '/api/guest', { method: 'POST' })).json();
  token = guest.token;
  check('游客身份签发', !!token);
  const p0 = await serverProgress();
  check('新身份成就总数为 ' + TOTAL, p0.achievementsTotal === TOTAL, 'total=' + p0.achievementsTotal);
  check('新身份没有已解锁成就', p0.achievementsUnlocked === 0, 'unlocked=' + p0.achievementsUnlocked);
  check('包含玩法首胜成就', (p0.achievements || []).some(a => a.id === 'mode-win-gomoku'));
  check('包含全模式制霸', (p0.achievements || []).some(a => a.id === 'all-modes' && a.goal === TOTAL - 12));

  // 2. 打开页面进大厅
  const ws = new WebSocket(await getWsUrl());
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  const cdp = new Cdp(ws);
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' }, false);
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true }, false);
  cdp.sessionId = sessionId;
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 1024, height: 900, deviceScaleFactor: 1, mobile: false
  });
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
  await waitUntil(() => cdp.eval("document.querySelectorAll('#achievement-grid .achievement').length > 0"), '成长面板就绪');

  // 3. 侧栏预览只展示 6 项，入口按钮打开成就墙
  check('侧栏预览保持 6 项', await cdp.eval("document.querySelectorAll('#achievement-grid .achievement').length") === 6,
    await cdp.eval("document.querySelectorAll('#achievement-grid .achievement').length"));
  await cdp.eval("document.querySelector('.achv-open-btn').click()");
  await waitUntil(() => cdp.eval("document.getElementById('achv-modal').classList.contains('show')"), '成就墙打开');
  let wall = await evalJson(cdp, wallState());
  check('成就墙已打开', wall.show);
  check('成就墙展示 31 张卡片', wall.cards.length === TOTAL, 'cards=' + wall.cards.length);
  check('新账号副标题为 已解锁 0 / 31', wall.sub.indexOf('已解锁 0 / ' + TOTAL) === 0, wall.sub);
  check('新账号全部卡片未解锁', wall.cards.every(c => !c.unlocked));

  // 4. 筛选：已解锁 → 空态；未解锁 → 31；回到全部 → 31
  await clickTab(cdp, 'unlocked');
  wall = await evalJson(cdp, wallState());
  check('已解锁筛选显示空态', wall.cards.length === 0 && wall.empty.indexOf('还没有已解锁成就') >= 0, JSON.stringify(wall));
  await clickTab(cdp, 'locked');
  wall = await evalJson(cdp, wallState());
  check('未解锁筛选显示 31 张', wall.cards.length === TOTAL, 'cards=' + wall.cards.length);
  await clickTab(cdp, 'all');
  wall = await evalJson(cdp, wallState());
  check('全部筛选恢复 31 张', wall.cards.length === TOTAL, 'cards=' + wall.cards.length);

  // 5. 布局几何：多列排布、卡片有足够高度但不成竖条
  const c0 = wall.cards[0], c1 = wall.cards[1];
  check('成就卡片多列排布', Math.abs(c0.y - c1.y) < 2 && c1.x > c0.x + 50,
    JSON.stringify({ c0: { x: c0.x, y: c0.y }, c1: { x: c1.x, y: c1.y } }));
  check('卡片宽高合理（非竖条）', c0.w > 150 && c0.w < 400 && c0.h > 40 && c0.h < 160,
    JSON.stringify({ w: Math.round(c0.w), h: Math.round(c0.h) }));

  // 6. 关闭按钮
  await cdp.eval("document.querySelector('#achv-modal .leave-btn').click()");
  wall = await evalJson(cdp, wallState());
  check('关闭按钮收起成就墙', !wall.show);

  // 7. 打一局人机并认输（只应解锁「初出茅庐」这类基础成就）
  const game = await playSoloAndSurrender('GOMOKU');
  check('人机对局完成', game.ok && game.over, JSON.stringify({ error: game.error }));
  let p1 = null;
  for (let i = 0; i < 20; i++) {
    p1 = await serverProgress();
    if (p1.totalGames === 1) break;
    await sleep(250);
  }
  check('服务端记录本局', p1.totalGames === 1, 'games=' + p1.totalGames);
  const firstGame = (p1.achievements || []).find(a => a.id === 'first-game');
  check('服务端已解锁初出茅庐', firstGame && firstGame.unlocked === true);
  const modeWin = (p1.achievements || []).find(a => a.id === 'mode-win-gomoku');
  check('认输不解锁五子棋首胜', modeWin && modeWin.unlocked === false && modeWin.progress === 0);
  const unlockedCount = p1.achievementsUnlocked;

  // 8. 刷新后重新进大厅（等 timeOrigin 变化，避免断言跑在旧文档上）
  const reloadOrigin = await cdp.eval("performance.timeOrigin");
  await cdp.send('Page.reload', { ignoreCache: true });
  await waitUntil(async () => (await cdp.eval("performance.timeOrigin")) !== reloadOrigin
    && (await cdp.eval("typeof joinGame === 'function'")), '刷新后登录页');
  await cdp.eval("document.getElementById('ws-url').value = " + JSON.stringify(WS_URL) + "; joinGame();");
  await waitUntil(() => cdp.eval("document.getElementById('lobby-screen').style.display === 'block'"), '刷新后进大厅');
  await waitUntil(() => cdp.eval("document.querySelectorAll('#achievement-grid .achievement').length > 0"), '刷新后成长面板');

  // 9. 刷新后的成就墙：已解锁数与服务端一致，首胜成就仍锁着且进度 0/1
  await cdp.eval("document.querySelector('.achv-open-btn').click()");
  await waitUntil(() => cdp.eval("document.getElementById('achv-modal').classList.contains('show')"), '刷新后成就墙打开');
  await waitUntil(async () => {
    const w = await evalJson(cdp, wallState());
    return w.sub.indexOf('已解锁 ' + unlockedCount + ' / ' + TOTAL) === 0;
  }, '成就墙数据与服务器同步', 8000);
  wall = await evalJson(cdp, wallState());
  check('刷新后副标题与服务器一致', wall.sub.indexOf('已解锁 ' + unlockedCount + ' / ' + TOTAL) === 0, wall.sub);
  check('刷新后卡片仍为 31 张', wall.cards.length === TOTAL, 'cards=' + wall.cards.length);
  const firstGameCard = await findCard(cdp, '初出茅庐');
  check('初出茅庐显示为已解锁', !!firstGameCard && firstGameCard.unlocked, JSON.stringify(firstGameCard));
  const modeWinCard = await findCard(cdp, '五子棋·首胜');
  check('五子棋·首胜仍锁定且显示 0/1', !!modeWinCard && !modeWinCard.unlocked
    && modeWinCard.text.indexOf('0/1') >= 0, JSON.stringify(modeWinCard));

  await clickTab(cdp, 'unlocked');
  wall = await evalJson(cdp, wallState());
  check('已解锁筛选数量与服务器一致', wall.cards.length === unlockedCount,
    'cards=' + wall.cards.length + ' server=' + unlockedCount);
  await clickTab(cdp, 'locked');
  wall = await evalJson(cdp, wallState());
  check('未解锁筛选数量与服务器一致', wall.cards.length === TOTAL - unlockedCount,
    'cards=' + wall.cards.length + ' expect=' + (TOTAL - unlockedCount));
  await cdp.shot(OUT + '/achievements-wall.png');

  // 10. 服务端口径：全模式制霸未解锁
  const allModes = (p1.achievements || []).find(a => a.id === 'all-modes');
  check('全模式制霸未解锁', allModes && allModes.unlocked === false && allModes.progress === 0);

  console.log(failed === 0
    ? '[ui-achv] PASS (' + passed + '/' + passed + ')'
    : '[ui-achv] FAIL (' + failed + ' failed, ' + passed + ' passed)');
  try { ws.close(); } catch (_) {}
  process.exit(failed === 0 ? 0 : 1);
})().catch((e) => {
  failed++;
  console.error('[ui-achv] FAIL 异常 :: ' + (e && e.message));
  process.exit(1);
});
