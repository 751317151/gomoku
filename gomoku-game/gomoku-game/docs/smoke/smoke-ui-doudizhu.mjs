// 斗地主 UI 冒烟（真实浏览器）：
// 1) 大厅卡牌分类包含斗地主，规则可打开；
// 2) 人机局有三席、17 张手牌、底牌保密；
// 3) 点击叫分后地主获得底牌，手牌可多选；
// 4) 提示按钮只会选牌，不自动出牌；公开记牌器可见；
// 5) 390px 无横向溢出、按钮满足触控高度、页面无运行时异常。
// 默认服务 19000/19001，Chrome CDP 9351。
const CDP_PORT = process.env.CDP_PORT || 9351;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'target/doudizhu-validation/shots';
const sleep = ms => new Promise(r => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[ui-doudizhu] PASS ' + name); }
  else { failed++; console.error('[ui-doudizhu] FAIL ' + name + (detail ? ' :: ' + JSON.stringify(detail) : '')); }
}
async function until(fn, label, timeout = 12000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await fn()) return true; await sleep(120); }
  throw new Error('等待超时: ' + label);
}
class Cdp {
  constructor(ws) {
    this.ws = ws; this.id = 0; this.pending = new Map(); this.errors = [];
    ws.onmessage = ev => {
      const m = JSON.parse(ev.data);
      if (m.method === 'Runtime.exceptionThrown') this.errors.push(m.params.exceptionDetails.text);
      if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
        this.errors.push(m.params.args.map(a => a.value || a.description).join(' '));
      }
      const p = this.pending.get(m.id);
      if (!p) return;
      clearTimeout(p.timer); this.pending.delete(m.id);
      m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result);
    };
  }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = () => reject(new Error('Chrome CDP 连接失败')); });
    return new Cdp(ws);
  }
  send(method, params = {}, session = true) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('CDP 超时: ' + method)); }, 30000);
      this.pending.set(id, { resolve, reject, timer });
      this.ws.send(JSON.stringify({ id, method, params, ...(session && this.sessionId ? { sessionId: this.sessionId } : {}) }));
    });
  }
  async eval(expr) {
    const r = await this.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    return r.result.value;
  }
  async shot(name) {
    const fs = await import('node:fs');
    fs.mkdirSync(OUT, { recursive: true });
    const r = await this.send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(OUT + '/' + name + '.png', Buffer.from(r.data, 'base64'));
  }
}

(async () => {
  console.log('== 斗地主 UI ==');
  const guest = await (await fetch(REST + '/api/guest', { method: 'POST' })).json();
  const info = await (await fetch('http://127.0.0.1:' + CDP_PORT + '/json/version')).json();
  const cdp = await Cdp.connect(info.webSocketDebuggerUrl);
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' }, false);
  cdp.sessionId = (await cdp.send('Target.attachToTarget', { targetId, flatten: true }, false)).sessionId;
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Network.enable');
  await cdp.send('Network.clearBrowserCache');
  await cdp.send('Storage.clearDataForOrigin', { origin: new URL(APP).origin, storageTypes: 'service_workers,cache_storage' });
  await cdp.eval("navigator.serviceWorker && navigator.serviceWorker.getRegistrations().then(rs=>Promise.all(rs.map(r=>r.unregister())))");
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `localStorage.setItem('gomoku_guest',${JSON.stringify(guest.token)});`
      + `localStorage.setItem('gomoku_onboarded','1');window.alert=m=>{window.__alert=String(m);};`
      + `window.confirm=()=>true;window.__errors=[];`
      + `window.addEventListener('error',e=>window.__errors.push(String(e.message)));`
      + `window.addEventListener('unhandledrejection',e=>window.__errors.push(String(e.reason)));`
  });
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp.send('Page.navigate', { url: APP });
  await until(() => cdp.eval("typeof joinGame==='function'"), '页面加载');
  await until(() => cdp.eval("[...document.styleSheets].some(s=>String(s.href).includes('style.css?v=100'))"), '最新样式加载', 15000);
  await cdp.eval("document.getElementById('ws-url').value=" + JSON.stringify(WS_URL)
    + ";document.getElementById('player-name').value='斗地主UI';joinGame();");
  await until(() => cdp.eval("gameState==='LOBBY' && !!guestToken && ws.readyState===1"), '进入大厅');
  await until(() => cdp.eval("!!Array.from(document.querySelectorAll('#game-categories .game-category')).find(b=>b.textContent==='卡牌')"), '玩分类渲染', 10000);

  await cdp.eval("Array.from(document.querySelectorAll('#game-categories .game-category')).find(b=>b.textContent==='卡牌').click()");
  const cards = await cdp.eval("Array.from(document.querySelectorAll('#game-selector .game-card')).map(c=>c.dataset.gameType)");
  check('卡牌分类包含斗地主', cards.includes('DOUDIZHU'), cards);
  check('斗地主有规则入口', await cdp.eval("!!document.querySelector('.game-card[data-game-type=\"DOUDIZHU\"] [data-rules]')"));

  await cdp.eval("document.querySelector('.game-card[data-game-type=\"DOUDIZHU\"] [data-rules]').click()");
  await until(() => cdp.eval("document.getElementById('rules-modal').classList.contains('show')"), '规则弹层');
  const rules = await cdp.eval("({title:document.getElementById('rules-title').textContent,text:document.getElementById('rules-body').textContent,meta:document.getElementById('rules-meta').textContent})");
  check('规则包含地主、底牌与牌型', rules.title.includes('斗地主') && rules.text.includes('底牌') && rules.text.includes('炸弹'), rules);
  check('规则显示三人与计时', rules.meta.includes('3 人') && rules.meta.includes('30 秒'), rules.meta);
  await cdp.eval("document.getElementById('rules-close').click()");

  await cdp.eval("document.querySelector('.game-card[data-game-type=\"DOUDIZHU\"]').click()");
  await cdp.eval("document.querySelector('.game-card[data-game-type=\"DOUDIZHU\"] [data-solo]').click()");
  await until(() => cdp.eval("document.getElementById('difficulty-dialog').classList.contains('show')"), '难度弹层');
  await cdp.eval("document.querySelector('#difficulty-dialog .difficulty-opt[data-diff=\"2\"]').click()");
  await until(() => cdp.eval("gameState==='PLAYING' && currentGameType==='DOUDIZHU' && rpgView"), '斗地主开局', 20000);
  const initial = await cdp.eval("({sides:rpgView.players.length,myHand:(rpgView.players.find(p=>p.side===myStone)||{}).hand?.length,bottom:(rpgView.bottom||[]).length,bidding:rpgView.bidding,panel:document.getElementById('doudizhu-panel').style.display})");
  check('斗地主三席并显示面板', initial.sides === 3 && initial.panel === 'flex', initial);
  check('我的起手 17 张', initial.myHand === 17, initial);
  check('叫地主前底牌保密', initial.bottom === 0 && initial.bidding === true, initial);
  await cdp.shot('doudizhu-bidding');

  // 轮到自己时点叫分；若 AI 先叫，则等待轮转。
  await until(() => cdp.eval("rpgView && rpgView.turn===myStone && rpgView.bidding"), '轮到叫地主', 15000);
  await cdp.eval("document.querySelector('#ddz-bids [data-ddz-bid=\"3\"]')?.click()");
  await until(() => cdp.eval("rpgView && !rpgView.bidding"), '叫地主完成', 15000);
  const afterBid = await cdp.eval("({landlord:rpgView.landlord,bottom:(rpgView.bottom||[]).length,hand:(rpgView.players.find(p=>p.side===myStone)||{}).hand?.length,counts:document.getElementById('ddz-counts').textContent})");
  check('确定地主后底牌公开', afterBid.landlord > 0 && afterBid.bottom === 3, afterBid);
  check('公开记牌器显示大牌数量', /大王\s*·\s*1/.test(afterBid.counts) && /2\s*·\s*4/.test(afterBid.counts), afterBid.counts);
  check('大小王牌面不换行', await cdp.eval("(()=>{const cards=Array.from(document.querySelectorAll('#ddz-hand .doudizhu-card'));"
    + "return cards.filter(c=>c.textContent.includes('王')||c.textContent.includes('大')).every(c=>c.getBoundingClientRect().width>=48);})()"));
  const playableAtLead = await cdp.eval("rpgView.turn===myStone && !rpgView.lastCombo");
  if (playableAtLead) {
    await cdp.eval("document.querySelector('#ddz-hand [data-ddz-index]')?.click()");
    check('手牌多选后可出牌', await cdp.eval("doudizhuSelected.size===1 && !document.getElementById('ddz-play').disabled"));
    await cdp.eval("document.getElementById('ddz-hint-btn').click()");
    check('提示只选择牌不一定自动出牌', await cdp.eval("doudizhuSelected.size>0 && gameState==='PLAYING'"));
  } else {
    check('手牌多选交互已挂在真实牌面', await cdp.eval("document.querySelectorAll('#ddz-hand [data-ddz-index]').length>0"));
  }
  await cdp.shot('doudizhu-play');

  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
  await until(() => cdp.eval("(()=>{const e=document.querySelector('.doudizhu-players');return e&&getComputedStyle(e).gridTemplateColumns.split(' ').length===3;})()"), '手机三席布局', 10000);
  const mobile = await cdp.eval("(()=>{const p=document.getElementById('doudizhu-panel').getBoundingClientRect();const b=document.getElementById('ddz-play').getBoundingClientRect();return {scroll:document.documentElement.scrollWidth,width:innerWidth,left:p.left,right:p.right,buttonH:b.height};})()");
  check('390px 无横向溢出', mobile.scroll <= mobile.width + 1, mobile);
  check('斗地主面板在视口内', mobile.left >= 0 && mobile.right <= mobile.width + 1, mobile);
  check('出牌按钮触控高度 ≥44px', mobile.buttonH >= 44, mobile);
  await cdp.shot('doudizhu-mobile');

  const errors = await cdp.eval("window.__errors || []");
  check('页面无运行时异常', errors.length === 0 && cdp.errors.length === 0, { page: errors, browser: cdp.errors });
  console.log(failed ? `\n[ui-doudizhu] FAIL (${failed} failed, ${passed} passed)` : `\n[ui-doudizhu] PASS (${passed}/${passed})`);
  cdp.ws.close();
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error('[ui-doudizhu] 异常:', e.stack); process.exit(1); });
