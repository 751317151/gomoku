// 运营公告端到端冒烟（真实浏览器 + 真实管理接口）：
// 1) 管理端无令牌被拒，带令牌可发布「重要」「维护」两条公告；
// 2) 大厅顶部出现两条横幅，级别标签/配色正确，布局不溢出；
// 3) 关闭一条后立即消失，刷新后仍保持关闭（按公告 id 记在本机），服务端其余玩家不受影响；
// 4) 管理后台公告页可再发布一条，大厅即时出现；
// 5) 后台下线后大厅消失，删除后记录不再存在。
// 需要服务端带 ADMIN_TOKEN（默认 smoke-admin-token）与 headless Chrome（CDP_PORT 默认 9341）。
const CDP_PORT = process.env.CDP_PORT || 9341;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'F:/tmp/shot';
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || 'smoke-admin-token';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[ui-announce] PASS ' + name); }
  else { failed++; console.error('[ui-announce] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}
async function waitUntil(fn, label, timeout = 12000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await fn()) return true; await sleep(150); }
  console.error('[ui-announce] 等待超时: ' + label);
  return false;
}

const NAME = '公告UI' + Math.floor(Math.random() * 10000);
const RUN = Date.now().toString(36);
const TITLE_PREFIX = '冒烟·';
const createdIds = [];

async function api(path, method = 'GET', body, token = ADMIN_TOKEN) {
  const headers = {};
  if (token) headers['X-Admin-Token'] = token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const r = await fetch(REST + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  let json = null;
  try { json = await r.json(); } catch (_) {}
  return { status: r.status, body: json };
}

async function publicActive() {
  const r = await api('/api/announcements');
  return r.body ? (r.body.announcements || []) : [];
}

async function cleanupCreated() {
  for (const id of createdIds) {
    try { await api('/api/admin/announcements/' + encodeURIComponent(id), 'DELETE'); } catch (_) {}
  }
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
    const fs = await import('node:fs');
    fs.mkdirSync(OUT, { recursive: true });
    const r = await this.send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(file, Buffer.from(r.data, 'base64'));
  }
}

(async () => {
  console.log('== 运营公告（真实浏览器）==');
  const fs = await import('node:fs');
  fs.mkdirSync(OUT, { recursive: true });

  // 0. 鉴权与初始盘点（只清理本脚本上次残留的「冒烟·」公告，不碰真实数据）
  const noToken = await api('/api/admin/announcements', 'GET', undefined, '');
  check('管理接口无令牌被拒', noToken.status === 403, String(noToken.status));
  const wrongToken = await api('/api/admin/announcements', 'GET', undefined, 'not-the-token');
  check('管理接口错令牌被拒', wrongToken.status === 403, String(wrongToken.status));
  check('玩家侧公告接口公开可读', (await api('/api/announcements', 'GET', undefined, '')).status === 200);
  const existing = await api('/api/admin/announcements');
  for (const item of (existing.body && existing.body.announcements) || []) {
    if (item.title && item.title.startsWith(TITLE_PREFIX)) {
      await api('/api/admin/announcements/' + encodeURIComponent(item.id), 'DELETE');
    }
  }

  // 1. 发布两条不同级别的公告
  const important = await api('/api/admin/announcements', 'POST', {
    title: TITLE_PREFIX + '周末双倍经验 ' + RUN,
    body: '周六 20:00 起，全部真人玩法经验翻倍。',
    level: 'important',
    ttl: '7d'
  });
  check('发布重要公告', important.status === 200 && important.body && important.body.announcement
    && important.body.announcement.level === 'important', JSON.stringify(important.body));
  if (important.body && important.body.announcement) createdIds.push(important.body.announcement.id);

  await sleep(30);
  const maintenance = await api('/api/admin/announcements', 'POST', {
    title: TITLE_PREFIX + '维护预告 ' + RUN,
    body: '今晚 23:00-23:30 停机维护，对局结算会保留。',
    level: 'maintenance',
    ttl: '24h'
  });
  check('发布维护公告', maintenance.status === 200 && maintenance.body && maintenance.body.announcement
    && maintenance.body.announcement.level === 'maintenance', JSON.stringify(maintenance.body));
  if (maintenance.body && maintenance.body.announcement) createdIds.push(maintenance.body.announcement.id);

  const active = await publicActive();
  check('公开接口按新旧返回两条', active.length >= 2
    && active[0].title === TITLE_PREFIX + '维护预告 ' + RUN
    && active[1].title === TITLE_PREFIX + '周末双倍经验 ' + RUN,
    JSON.stringify(active.map(a => a.title)));
  check('公开视图不泄露上下线字段', active.every(a => a.active === undefined));

  // 2. 浏览器进入大厅看横幅
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
      + `window.prompt=function(){return ${JSON.stringify(ADMIN_TOKEN)};};`
  });

  async function enterLobby(url) {
    await cdp.send('Page.navigate', { url: url });
    const ready = await waitUntil(() => cdp.eval("typeof joinGame === 'function'"), '登录页就绪');
    if (!ready) throw new Error('登录页未就绪');
    await cdp.eval("document.getElementById('ws-url').value = " + JSON.stringify(WS_URL)
      + "; document.getElementById('player-name').value = " + JSON.stringify(NAME) + "; joinGame();");
    const entered = await waitUntil(() => cdp.eval("document.getElementById('lobby-screen').style.display === 'block'"), '进入大厅');
    if (!entered) throw new Error('未能进入大厅');
  }

  await enterLobby(APP);
  const bannerReady = await waitUntil(() => cdp.eval(
    "(() => { const el = document.getElementById('announce-board');"
    + " return el && el.style.display !== 'none' && el.querySelectorAll('.announce-item').length === 2; })()"),
    '大厅两条公告横幅', 15000);
  check('大厅显示两条公告横幅', bannerReady);

  const bannerState = await cdp.eval(
    "(() => { const board = document.getElementById('announce-board');"
    + " const items = Array.from(board.querySelectorAll('.announce-item'));"
    + " return items.map(el => ({"
    + "   cls: el.className,"
    + "   title: el.querySelector('.announce-title').textContent,"
    + "   label: el.querySelector('.announce-level').textContent,"
    + "   border: getComputedStyle(el).borderLeftColor,"
    + "   width: el.getBoundingClientRect().width,"
    + "   height: el.getBoundingClientRect().height,"
    + "   overflow: el.scrollWidth > el.clientWidth + 1,"
    + "   closeVisible: !!el.querySelector('.announce-close') && el.querySelector('.announce-close').offsetParent !== null"
    + " })); })()");
  check('维护公告在最前且标签/配色正确', bannerState[0]
    && bannerState[0].title === TITLE_PREFIX + '维护预告 ' + RUN
    && bannerState[0].label === '维护'
    && bannerState[0].cls.includes('announce-maintenance')
    && bannerState[0].border === 'rgb(90, 169, 224)', JSON.stringify(bannerState[0]));
  check('重要公告标签/配色正确', bannerState[1]
    && bannerState[1].title === TITLE_PREFIX + '周末双倍经验 ' + RUN
    && bannerState[1].label === '重要'
    && bannerState[1].cls.includes('announce-important')
    && bannerState[1].border === 'rgb(224, 85, 85)', JSON.stringify(bannerState[1]));
  check('横幅几何正常、有可点的关闭按钮', bannerState.every(b =>
    b.width > 250 && b.height > 40 && b.height < 160 && !b.overflow && b.closeVisible),
    JSON.stringify(bannerState.map(b => ({ w: b.width, h: b.height, ov: b.overflow }))));
  await cdp.eval("document.getElementById('announce-board').scrollIntoView({ block: 'center' })");
  await sleep(300);
  await cdp.shot(OUT + '/announce-banner.png');

  // 3. 关闭一条并刷新验证记忆
  const initialBannerCount = bannerState.length;
  const maintenanceId = maintenance.body && maintenance.body.announcement && maintenance.body.announcement.id;
  await cdp.eval("document.querySelector('.announce-item .announce-close').click()");
  const oneLeft = await waitUntil(() => cdp.eval(
    "document.querySelectorAll('#announce-board .announce-item').length === " + (initialBannerCount - 1)),
    '关闭一条后数量减一');
  check('关闭后横幅数量减一', oneLeft);
  check('关闭只影响本机（服务端仍有该公告）', (await publicActive())
    .some(a => a.title === TITLE_PREFIX + '维护预告 ' + RUN));
  check('关闭记录写入本机', await cdp.eval(
    "JSON.parse(localStorage.getItem('gomokuDismissedAnnouncements') || '[]').includes("
    + JSON.stringify(maintenanceId) + ")"));

  const originBefore = await cdp.eval('performance.timeOrigin');
  await cdp.send('Page.reload');
  const reloaded = await waitUntil(async () => {
    const origin = await cdp.eval('performance.timeOrigin');
    return origin !== originBefore && await cdp.eval("typeof joinGame === 'function'");
  }, '刷新完成（timeOrigin 变化）', 15000);
  check('刷新后页面确实重载', reloaded);
  if (!reloaded) throw new Error('刷新未完成');

  await cdp.eval("document.getElementById('ws-url').value = " + JSON.stringify(WS_URL)
    + "; document.getElementById('player-name').value = " + JSON.stringify(NAME) + "; joinGame();");
  await waitUntil(() => cdp.eval("document.getElementById('lobby-screen').style.display === 'block'"), '刷新后进入大厅');
  const persisted = await waitUntil(() => cdp.eval(
    "(() => { const text = document.getElementById('announce-board').textContent;"
    + " return text.includes('冒烟·周末双倍经验 ' + " + JSON.stringify(RUN) + ")"
    + " && !text.includes('冒烟·维护预告 ' + " + JSON.stringify(RUN) + "); })()"),
    '刷新后已关闭公告仍隐藏', 15000);
  check('刷新后已关闭公告不再出现', persisted);

  // 4. 管理后台发布第三条 → 大厅即时出现
  await enterLobby(APP + '?admin=1');
  check('带令牌进入时显示治理后台入口', await waitUntil(() => cdp.eval(
    "document.getElementById('admin-entry').style.display !== 'none'"), '后台入口', 8000));
  await cdp.eval("document.getElementById('admin-entry').click()");
  check('管理弹窗打开', await waitUntil(() => cdp.eval(
    "document.getElementById('admin-modal').classList.contains('show')"), '管理弹窗'));
  await cdp.eval("document.getElementById('admin-tab-announce').click()");
  check('公告管理页签可用且已有两条记录', await waitUntil(() => cdp.eval(
    "document.getElementById('admin-pane-announce').style.display === 'flex'"
    + " && Array.from(document.querySelectorAll('#admin-announcements .admin-row'))"
    + "   .filter(r => r.textContent.includes(" + JSON.stringify(TITLE_PREFIX) + ")).length >= 2"),
    '公告管理列表', 10000));

  const uiTitle = TITLE_PREFIX + '界面发布公告 ' + RUN;
  await cdp.eval(
    "document.getElementById('announce-title').value = " + JSON.stringify(uiTitle)
    + "; document.getElementById('announce-body').value = '这条公告由管理后台直接发布。';"
    + "document.getElementById('announce-level').value = 'info';"
    + "document.getElementById('announce-ttl').value = '24h';"
    + "document.querySelector('.announce-form .btn-match').click();");
  const published = await waitUntil(() => cdp.eval(
    "Array.from(document.querySelectorAll('#admin-announcements .admin-row'))"
    + "  .some(r => r.textContent.includes(" + JSON.stringify(uiTitle) + "))"), '后台发布后列表新增', 10000);
  check('后台发布后管理列表新增', published);
  const uiRow = await cdp.eval(
    "(() => { const rows = Array.from(document.querySelectorAll('#admin-announcements .admin-row'));"
    + " const row = rows.find(r => r.textContent.includes(" + JSON.stringify(uiTitle) + "));"
    + " return row ? { id: row.dataset.announceAdminId, text: row.textContent,"
    + "   tag: (row.querySelector('.admin-tag') || {}).textContent || '' } : null; })()");
  check('新公告显示为生效中', !!uiRow && uiRow.tag.includes('生效中'), JSON.stringify(uiRow));
  check('大厅横幅即时出现新公告', await waitUntil(() => cdp.eval(
    "document.getElementById('announce-board').textContent.includes(" + JSON.stringify(uiTitle) + ")"), '新公告横幅', 10000));
  const activeTitles = (await publicActive()).map(a => a.title);
  check('公开接口包含三条本脚本公告',
    activeTitles.includes(TITLE_PREFIX + '维护预告 ' + RUN)
    && activeTitles.includes(TITLE_PREFIX + '周末双倍经验 ' + RUN)
    && activeTitles.includes(uiTitle), JSON.stringify(activeTitles));
  await cdp.eval("document.getElementById('admin-modal').scrollIntoView({ block: 'center' })");
  await sleep(300);
  await cdp.shot(OUT + '/announce-admin.png');

  // 5. 后台下线 → 大厅消失；删除 → 记录消失
  const uiId = uiRow && uiRow.id;
  check('管理行带公告 id', !!uiId, String(uiId));
  if (uiId) createdIds.push(uiId);
  const rowSelector = '[data-announce-admin-id="' + uiId + '"]';
  await cdp.eval(
    "(() => { const row = document.querySelector(" + JSON.stringify(rowSelector) + ");"
    + " const btn = Array.from(row.querySelectorAll('button')).find(b => b.textContent.trim() === '下线');"
    + " btn.click(); })()");
  const offlineOk = await waitUntil(async () => {
    const gone = await cdp.eval(
      "!document.getElementById('announce-board').textContent.includes(" + JSON.stringify(uiTitle) + ")");
    const server = (await publicActive()).every(a => a.title !== uiTitle);
    return gone && server;
  }, '下线后横幅消失');
  check('下线后大厅与服务端同时消失', offlineOk);
  check('下线记录仍保留在后台', await cdp.eval(
    "(() => { const row = document.querySelector(" + JSON.stringify(rowSelector) + ");"
    + " return !!row && row.textContent.includes('已下线'); })()"));

  await cdp.eval(
    "(() => { const row = document.querySelector(" + JSON.stringify(rowSelector) + ");"
    + " const btn = Array.from(row.querySelectorAll('button')).find(b => b.textContent.trim() === '删除');"
    + " btn.click(); })()");
  const deleted = await waitUntil(async () => {
    const gone = await cdp.eval(
      "!document.querySelector(" + JSON.stringify(rowSelector) + ")");
    const list = await api('/api/admin/announcements');
    const exists = (list.body && list.body.announcements || []).some(a => a.id === uiId);
    return gone && !exists;
  }, '删除后记录消失');
  check('删除后后台与服务端都不再有该公告', deleted);

  await cleanupCreated();
  console.log(failed === 0
    ? '[ui-announce] PASS (' + passed + '/' + passed + ')'
    : '[ui-announce] FAIL (' + failed + ' failed, ' + passed + ' passed)');
  try { ws.close(); } catch (_) {}
  process.exit(failed === 0 ? 0 : 1);
})().catch(async (e) => {
  failed++;
  console.error('[ui-announce] FAIL 异常 :: ' + (e && e.message));
  await cleanupCreated();
  process.exit(1);
});
