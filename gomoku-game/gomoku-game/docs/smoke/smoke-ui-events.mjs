// 限时活动端到端：管理端创建 → 大厅加成条 → 活动内对局经验 +50% → 暂停后新局无加成。
// 需要先起 headless Chrome（CDP_PORT 默认 9342）与测试服务（ADMIN_TOKEN 与 .env 一致）。
const CDP_PORT = process.env.CDP_PORT || 9342;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || 'smoke-admin-token';
const OUT = process.env.OUT_DIR || 'F:/tmp/shot';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[ui-events] PASS ' + name); }
  else { failed++; console.error('[ui-events] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}
async function waitUntil(fn, label, timeout = 12000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await fn()) return; await sleep(150); }
  throw new Error('等待超时: ' + label);
}

const NAME = '活动UI' + Math.floor(Math.random() * 10000);
const TITLE = '冒烟·五子棋加成' + Date.now();
const createdIds = [];

async function adminCall(path, options) {
  const opts = Object.assign({}, options || {});
  opts.headers = Object.assign({ 'X-Admin-Token': ADMIN_TOKEN }, opts.headers || {});
  const r = await fetch(REST + path, opts);
  let body = null;
  try { body = await r.json(); } catch (_) {}
  return { ok: r.ok, status: r.status, body };
}

async function cleanup() {
  for (const id of createdIds.splice(0)) {
    try { await adminCall('/api/admin/events/' + encodeURIComponent(id), { method: 'DELETE' }); } catch (_) {}
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
    const r = await this.send('Page.captureScreenshot', { format: 'png' });
    const fs = await import('node:fs');
    fs.writeFileSync(file, Buffer.from(r.data, 'base64'));
  }
}

function guestHeaders(token) { return { Authorization: 'Bearer ' + token }; }

(async () => {
  console.log('== 限时活动 UI（真实浏览器）==');
  console.log('   APP=' + APP + ' WS=' + WS_URL + ' CDP=' + CDP_PORT);

  // 0. 先清掉上一次失败运行残留的冒烟活动，避免干扰「大厅只有本活动」的判断
  const stale = await adminCall('/api/admin/events');
  for (const e of (stale.body && stale.body.events) || []) {
    if (e.title && e.title.startsWith('冒烟·')) {
      await adminCall('/api/admin/events/' + encodeURIComponent(e.id), { method: 'DELETE' });
    }
  }

  check('管理接口拒绝无令牌', (await fetch(REST + '/api/admin/events')).status === 403);
  const before = await (await fetch(REST + '/api/events')).json();
  check('玩家侧活动接口公开可读', Array.isArray(before.events) && typeof before.serverTime === 'number');

  // 1. 全新游客
  const guest = await (await fetch(REST + '/api/guest', { method: 'POST' })).json();
  const token = guest.token;
  check('游客身份签发', !!token);
  const progress0 = await (await fetch(REST + '/api/me/progress', { headers: guestHeaders(token) })).json();
  check('新身份经验为 0', progress0.xp === 0, 'xp=' + progress0.xp);

  // 2. 浏览器：带管理令牌进入大厅
  const ws = new WebSocket(await getWsUrl());
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  const cdp = new Cdp(ws);
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' }, false);
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true }, false);
  cdp.sessionId = sessionId;
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1024, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `try{localStorage.setItem('gomoku_guest', ${JSON.stringify(token)});`
      + `localStorage.setItem('gomoku_onboarded','1');}catch(_){ };`
      + `window.alert=function(m){window.__lastAlert=String(m);};`
      + `window.confirm=function(){return true;};`
      + `window.prompt=function(){return ${JSON.stringify(ADMIN_TOKEN)};};`
  });
  await cdp.send('Page.navigate', { url: APP + '?admin=1' });
  await waitUntil(() => cdp.eval("typeof joinGame === 'function'"), '登录页就绪');
  await cdp.eval("document.getElementById('ws-url').value = " + JSON.stringify(WS_URL)
    + "; document.getElementById('player-name').value = " + JSON.stringify(NAME) + "; joinGame();");
  await waitUntil(() => cdp.eval("document.getElementById('lobby-screen').style.display === 'block'"), '进入大厅');

  // 3. 管理端通过界面创建活动
  check('管理入口可见', await cdp.eval("document.getElementById('admin-entry').style.display !== 'none'"));
  await cdp.eval("openAdmin(); true");
  await waitUntil(() => cdp.eval("document.getElementById('admin-modal').classList.contains('show')"), '管理后台打开');
  await cdp.eval("document.getElementById('admin-tab-events').click(); true");
  await waitUntil(() => cdp.eval("!!document.querySelector('#event-game option[value=\"GOMOKU\"]')"), '玩法下拉加载');
  await cdp.eval("document.getElementById('event-title').value = " + JSON.stringify(TITLE) + ";"
    + "document.getElementById('event-game').value = 'GOMOKU';"
    + "document.getElementById('event-percent').value = '50';"
    + "document.getElementById('event-duration').value = '24h';"
    + "document.querySelector('#admin-pane-events .btn-match').click(); true");
  const rowId = await (async () => {
    let id = null;
    await waitUntil(async () => {
      id = await cdp.eval(`(function(){
        var rows = document.querySelectorAll('[data-event-admin-id]');
        for (var i = 0; i < rows.length; i++) {
          if (rows[i].textContent.indexOf(${JSON.stringify(TITLE)}) >= 0) return rows[i].getAttribute('data-event-admin-id');
        }
        return null;
      })()`);
      return !!id;
    }, '活动列表出现新活动');
    return id;
  })();
  createdIds.push(rowId);
  check('活动已创建并获得 ID', !!rowId && rowId.indexOf('EVT-') === 0, String(rowId));
  const rowText = await cdp.eval(`document.querySelector('[data-event-admin-id="${rowId}"]').textContent`);
  check('活动列表显示生效中', rowText.includes('生效中'), rowText);
  check('活动列表显示玩法与倍率', rowText.includes('五子棋') && rowText.includes('+50%'), rowText);

  // 4. 大厅加成条生效
  await waitUntil(() => cdp.eval("document.getElementById('event-strip').style.display === 'flex'"), '大厅活动条出现');
  const stripText = await cdp.eval("document.getElementById('event-strip').textContent");
  check('大厅活动条显示五子棋 +50%', stripText.includes('五子棋') && stripText.includes('经验 +50%'), stripText);
  check('大厅活动条显示剩余时长', stripText.includes('剩 '), stripText);
  const stripGeom = await cdp.eval(`(function(){
    var chip = document.querySelector('#event-strip .event-chip');
    if (!chip) return null;
    var r = chip.getBoundingClientRect();
    return { w: Math.round(r.width), h: Math.round(r.height), overflow: chip.scrollWidth > chip.clientWidth + 1,
             display: getComputedStyle(document.getElementById('event-strip')).display };
  })()`);
  check('活动条为横向胶囊且文字不溢出',
    stripGeom && stripGeom.display === 'flex' && stripGeom.w > stripGeom.h && stripGeom.h < 40 && !stripGeom.overflow,
    JSON.stringify(stripGeom));
  await cdp.shot(OUT + '/events-lobby.png');

  // 5. 活动内打一局人机并认输：基础 45 + 50% = 68
  await cdp.eval("closeAdmin(); initGameModule('GOMOKU'); showGame();"
    + "send({type:'JOIN', playerName:" + JSON.stringify(NAME) + ", gameType:'GOMOKU', createRoom:true});"
    + "setTimeout(function(){ send({type:'ADD_AI', data:'1'}); }, 500); true");
  await waitUntil(() => cdp.eval("gameState === 'PLAYING'"), '人机开局', 15000);
  await cdp.eval("send({type:'SURRENDER'}); true");
  await waitUntil(() => cdp.eval("document.getElementById('gameover-overlay').classList.contains('show')"), '终局浮层', 12000);
  await waitUntil(() => cdp.eval("document.querySelectorAll('#gameover-rewards .reward-chip').length > 0"), '奖励徽标', 12000);
  const rewards1 = await cdp.eval("document.getElementById('gameover-rewards').textContent");
  check('结算显示本局 +68 经验（含活动加成）', rewards1.includes('本局 +68 经验'), rewards1);
  check('结算显示活动名称与 +50%', rewards1.includes('活动「' + TITLE + '」+50% 经验'), rewards1);
  const eventChipColor = await cdp.eval(`(function(){
    var chip = document.querySelector('#gameover-rewards .reward-event');
    return chip ? getComputedStyle(chip).color : null;
  })()`);
  check('活动徽标使用独立强调色', eventChipColor === 'rgb(243, 217, 139)', String(eventChipColor));
  await cdp.shot(OUT + '/events-result.png');

  const progress1 = await (await fetch(REST + '/api/me/progress', { headers: guestHeaders(token) })).json();
  check('服务端经验为 68', progress1.xp === 68, 'xp=' + progress1.xp);
  const games1 = await (await fetch(REST + '/api/player/' + encodeURIComponent(NAME) + '/games?limit=5')).json();
  check('对局记录刻入活动倍率', games1.length > 0 && games1[0].eventXpPercent === 50
    && games1[0].eventTitle === TITLE, JSON.stringify(games1[0] || {}));

  // 6. 管理端暂停活动：大厅活动条消失
  await cdp.eval(`(function(){
    var row = document.querySelector('[data-event-admin-id="${rowId}"]');
    var btn = row && row.querySelector('.admin-actions button');
    if (btn) btn.click();
  })(); true`);
  await waitUntil(async () => {
    const body = await (await fetch(REST + '/api/events')).json();
    return !(body.events || []).some(e => e.id === rowId);
  }, '活动从玩家侧消失', 10000);
  await waitUntil(() => cdp.eval("document.getElementById('event-strip').style.display === 'none'"), '大厅活动条隐藏');
  check('暂停后大厅活动条隐藏', await cdp.eval("document.getElementById('event-strip').style.display === 'none'"));
  const pausedText = await cdp.eval(`document.querySelector('[data-event-admin-id="${rowId}"]').textContent`);
  check('管理列表显示已暂停', pausedText.includes('已暂停'), pausedText);

  // 7. 活动结束后再打一局：只有基础 20 经验，历史 68 不缩水
  await cdp.eval("closeAdmin(); leaveRoom(); true");
  await waitUntil(() => cdp.eval("document.getElementById('lobby-screen').style.display === 'block'"), '回到大厅');
  await cdp.eval("initGameModule('GOMOKU'); showGame();"
    + "send({type:'JOIN', playerName:" + JSON.stringify(NAME) + ", gameType:'GOMOKU', createRoom:true});"
    + "setTimeout(function(){ send({type:'ADD_AI', data:'1'}); }, 500); true");
  await waitUntil(() => cdp.eval("gameState === 'PLAYING'"), '第二局开局', 15000);
  await cdp.eval("send({type:'SURRENDER'}); true");
  await waitUntil(() => cdp.eval("document.getElementById('gameover-overlay').classList.contains('show')"), '第二局终局', 12000);
  await waitUntil(() => cdp.eval("document.getElementById('gameover-rewards').textContent.indexOf('本局 +20 经验') >= 0"), '第二局奖励', 12000);
  const rewards2 = await cdp.eval("document.getElementById('gameover-rewards').textContent");
  check('暂停后本局只有基础 20 经验', rewards2.includes('本局 +20 经验'), rewards2);
  check('暂停后不再显示活动徽标', await cdp.eval("document.querySelectorAll('#gameover-rewards .reward-event').length === 0"));

  const progress2 = await (await fetch(REST + '/api/me/progress', { headers: guestHeaders(token) })).json();
  check('服务端累计经验为 88（历史 68 不缩水）', progress2.xp === 88, 'xp=' + progress2.xp);
  const games2 = await (await fetch(REST + '/api/player/' + encodeURIComponent(NAME) + '/games?limit=5')).json();
  const latest = games2.find(g => g.roomId !== games1[0].roomId) || games2[0];
  check('暂停后的新记录不带活动字段', latest && !latest.eventXpPercent && !latest.eventId,
    JSON.stringify(latest || {}));

  // 8. 删除活动，玩家侧与管理端都查不到
  const del = await adminCall('/api/admin/events/' + encodeURIComponent(rowId), { method: 'DELETE' });
  check('删除活动成功', del.ok && del.body && del.body.deleted === true);
  createdIds.length = 0;
  const afterDelete = await (await fetch(REST + '/api/events')).json();
  check('删除后玩家侧不再出现该活动', !(afterDelete.events || []).some(e => e.id === rowId));

  console.log(failed === 0
    ? '[ui-events] PASS (' + passed + '/' + passed + ')'
    : '[ui-events] FAIL (' + failed + ' failed, ' + passed + ' passed)');
  await cleanup();
  try { ws.close(); } catch (_) {}
  process.exit(failed === 0 ? 0 : 1);
})().catch(async (e) => {
  failed++;
  console.error('[ui-events] FAIL 异常 :: ' + (e && e.stack || e));
  await cleanup();
  process.exit(1);
});
