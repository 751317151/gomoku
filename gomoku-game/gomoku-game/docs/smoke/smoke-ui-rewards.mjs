// 结算页成长反馈：全新游客打一局人机并认输 → 终局浮层直接显示本局经验、成就与任务进度，
// 且服务端进度/任务与页面一致。需要先起 headless Chrome（CDP_PORT 默认 9337）与测试服务。
const CDP_PORT = process.env.CDP_PORT || 9337;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'F:/tmp/shot';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[ui-rewards] PASS ' + name); }
  else { failed++; console.error('[ui-rewards] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}
async function waitUntil(fn, label, timeout = 12000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await fn()) return; await sleep(150); }
  throw new Error('等待超时: ' + label);
}

const NAME = '结算UI' + Math.floor(Math.random() * 10000);
let token = null;

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
    const path = await import('node:path');
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, Buffer.from(r.data, 'base64'));
  }
}

const chipsText = () => "document.getElementById('gameover-rewards').textContent";

(async () => {
  console.log('== 结算成长反馈 UI（真实浏览器）==');
  console.log('   APP=' + APP + ' WS=' + WS_URL + ' CDP=' + CDP_PORT);

  // 1. 全新游客：0 经验、0 对局
  const guest = await (await fetch(REST + '/api/guest', { method: 'POST' })).json();
  token = guest.token;
  check('游客身份签发', !!token);
  const progress0 = await (await fetch(REST + '/api/me/progress', { headers: { Authorization: 'Bearer ' + token } })).json();
  check('新身份经验为 0', progress0.xp === 0, 'xp=' + progress0.xp);
  check('新身份没有成就', progress0.achievementsUnlocked === 0, 'unlocked=' + progress0.achievementsUnlocked);

  // 2. 打开页面并进入大厅
  const ws = new WebSocket(await getWsUrl());
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  const cdp = new Cdp(ws);
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' }, false);
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true }, false);
  cdp.sessionId = sessionId;
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  // 默认 headless 视口只有 600 高，结算内容会超出截图；加高到 900 让奖励徽标完整可见
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
  // 等成长面板首次加载完，确保终局前的快照是「0 经验」
  await waitUntil(() => cdp.eval("document.getElementById('level-xp').textContent.indexOf('0 / 100') === 0"), '成长面板就绪');
  check('大厅成长面板为 0 经验', await cdp.eval("document.getElementById('level-xp').textContent.indexOf('0 / 100') === 0"));

  // 3. 走页面自己的协议通道开一局人机，然后认输
  await cdp.eval("initGameModule('GOMOKU'); showGame();"
    + "send({type:'JOIN', playerName:" + JSON.stringify(NAME) + ", gameType:'GOMOKU', createRoom:true});"
    + "setTimeout(function(){ send({type:'ADD_AI', data:'1'}); }, 500); true");
  await waitUntil(() => cdp.eval("gameState === 'PLAYING'"), '人机开局', 15000);
  check('人机局已开始', await cdp.eval("gameState === 'PLAYING'"));
  await cdp.eval("send({type:'SURRENDER'}); true");
  await waitUntil(() => cdp.eval("document.getElementById('gameover-overlay').classList.contains('show')"), '终局浮层', 12000);
  check('终局浮层出现', await cdp.eval("document.getElementById('gameover-overlay').classList.contains('show')"));

  // 4. 结算页奖励：等异步进度/任务刷新后对比渲染
  await waitUntil(() => cdp.eval("document.querySelectorAll('#gameover-rewards .reward-chip').length > 0"), '奖励徽标', 12000);
  const rewards = await cdp.eval(chipsText());
  check('显示本局经验 +45', rewards.includes('本局 +45 经验'), rewards);
  check('显示解锁成就「初出茅庐」', rewards.includes('初出茅庐'), rewards);
  check('显示每日任务进度 1/3', rewards.includes('每日任务 1/3 局'), rewards);
  check('未完成三局时不显示可领取', !rewards.includes('可领取'), rewards);
  check('失败局结果为「失  败」', await cdp.eval("document.getElementById('gameover-result').textContent") === '失  败');
  check('结算浮层提供返回大厅', await cdp.eval(
    "!!document.getElementById('gameover-lobby') && document.getElementById('gameover-lobby').offsetParent !== null"));
  const chipCount = await cdp.eval("document.querySelectorAll('#gameover-rewards .reward-chip').length");
  check('奖励徽标数量不少于 3', chipCount >= 3, 'chips=' + chipCount);

  // 布局几何：曾因容器被压成 min-content 宽度，徽标竖排拉长成椭圆，而文字断言全绿。
  // 这里钉住「横向一排、每个徽标宽>高、单行高度正常」，让样式回归必须变红。
  const geom = await cdp.eval(`(function(){
    var box = document.getElementById('gameover-rewards');
    var cs = getComputedStyle(box);
    var chips = Array.prototype.map.call(box.querySelectorAll('.reward-chip'), function(c){
      var r = c.getBoundingClientRect();
      return { x: r.x, y: r.y, w: Math.round(r.width), h: Math.round(r.height),
               overflow: c.scrollWidth > c.clientWidth + 1 };
    });
    // 曾因通用 .achievement 规则命中奖励徽标（display:grid; width:40px），
    // 文字溢出自身盒子并压到相邻徽标上；矩形断言必须覆盖"装得下"和"不重叠"。
    var overlap = false;
    for (var i = 0; i < chips.length; i++) {
      for (var j = i + 1; j < chips.length; j++) {
        var a = chips[i], b = chips[j];
        var vx = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
        var hx = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
        if (vx > 1 && hx > 1) overlap = true;
      }
    }
    return { dir: cs.flexDirection, boxH: Math.round(box.getBoundingClientRect().height),
             chips: chips, overlap: overlap };
  })()`);
  check('奖励区为横向排列', geom.dir === 'row', JSON.stringify(geom));
  check('徽标均横向铺开（宽>高，高<40）',
    geom.chips.length > 0 && geom.chips.every(c => c.w > c.h && c.h < 40), JSON.stringify(geom.chips));
  check('徽标文字不溢出自身边界', geom.chips.every(c => !c.overflow), JSON.stringify(geom.chips));
  check('徽标彼此不重叠', !geom.overlap, JSON.stringify(geom.chips));
  check('奖励区高度不超过两行', geom.boxH < 80, 'h=' + geom.boxH);
  await cdp.eval("document.querySelector('#gameover-overlay .btn-review').click()");
  const actions = await cdp.eval(
    "(() => { const bar = document.getElementById('restart-bar'); const rect = bar.getBoundingClientRect();"
    + " const labels = Array.from(bar.querySelectorAll('button')).map(b => b.textContent.trim());"
    + " return { visible: bar.style.display === 'flex' && bar.offsetParent !== null,"
    + "  labels, height: rect.height, overflow: bar.scrollWidth > bar.clientWidth + 1 }; })()");
  check('关闭结算浮层后仍可再来一局或返回大厅', actions.visible
    && actions.labels.includes('再来一局') && actions.labels.includes('返回大厅')
    && actions.height >= 44 && !actions.overflow, JSON.stringify(actions));
  await cdp.eval("document.getElementById('gameover-overlay').classList.add('show');"
    + "document.getElementById('restart-bar').style.display='none'; true");
  await cdp.shot(OUT + '/rewards-after.png');

  // 5. 服务端与页面一致：45 经验、1 局、初出茅庐已解锁
  const progress1 = await (await fetch(REST + '/api/me/progress', { headers: { Authorization: 'Bearer ' + token } })).json();
  check('服务端经验为 45', progress1.xp === 45, 'xp=' + progress1.xp);
  check('服务端对局数为 1', progress1.totalGames === 1, 'games=' + progress1.totalGames);
  check('服务端已解锁初出茅庐', (progress1.achievements || []).some(a => a.id === 'first-game' && a.unlocked === true));
  const quests = await (await fetch(REST + '/api/quests', { headers: { Authorization: 'Bearer ' + token } })).json();
  check('服务端今日对局数为 1', quests.todayGames === 1, 'todayGames=' + quests.todayGames);

  console.log(failed === 0
    ? '[ui-rewards] PASS (' + passed + '/' + passed + ')'
    : '[ui-rewards] FAIL (' + failed + ' failed, ' + passed + ' passed)');
  try { ws.close(); } catch (_) {}
  process.exit(failed === 0 ? 0 : 1);
})().catch((e) => {
  failed++;
  console.error('[ui-rewards] FAIL 异常 :: ' + (e && e.message));
  process.exit(1);
});
