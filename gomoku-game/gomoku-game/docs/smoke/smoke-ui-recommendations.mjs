// 「下一局玩什么」推荐端到端验收：
// 1) 匿名 REST 返回 3 条可解释推荐，不要求登录；
// 2) 真人公开等待房进入总览后，该玩法成为首条推荐；
// 3) 点推荐卡会选中对应玩法、回到全部分类并刷新房间列表；
// 4) 桌面与 390px 手机无横向溢出，卡片触控高度不缩水。
// 默认自带隔离数据和动态端口；设置 APP_URL/REST_URL/WS_URL 可复用已启动服务。
// 设置 CDP_PORT 可复用已启动 Chrome，否则脚本使用独立临时 profile 启动 Chrome。
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, openSync, closeSync, readFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const project = fileURLToPath(new URL('../../', import.meta.url));
const jar = resolve(process.env.JAR_PATH || join(project, 'target/gomoku-game-1.0-SNAPSHOT.jar'));
const external = Boolean(process.env.APP_URL || process.env.REST_URL || process.env.WS_URL);
const fixedChrome = Boolean(process.env.CDP_PORT);
const output = resolve(process.env.OUT_DIR || join(project, 'target/recommendation-smoke'));
mkdirSync(output, { recursive: true });
const runDir = mkdtempSync(join(output, 'run-'));

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let passed = 0, failed = 0;
function check(name, condition, detail) {
  if (condition) { passed++; console.log('[ui-recommend] PASS ' + name); }
  else { failed++; console.error('[ui-recommend] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}
async function waitUntil(fn, label, timeout = 15000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    try { if (await fn()) return true; } catch (_) {}
    await sleep(150);
  }
  console.error('[ui-recommend] 等待超时: ' + label);
  return false;
}
async function freePort() {
  const server = createServer();
  await new Promise((done, fail) => {
    server.once('error', fail);
    server.listen(0, '127.0.0.1', done);
  });
  const port = server.address().port;
  await new Promise(done => server.close(done));
  return port;
}

let httpPort = 0, wsPort = 0, cdpPort = fixedChrome ? Number(process.env.CDP_PORT) : 0;
if (!external) {
  httpPort = await freePort();
  do { wsPort = await freePort(); } while (wsPort === httpPort);
}
if (!fixedChrome) cdpPort = await freePort();
const APP = process.env.APP_URL || 'http://127.0.0.1:' + httpPort + '/';
const REST = process.env.REST_URL || 'http://127.0.0.1:' + httpPort;
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:' + wsPort;
let service = null, serviceFd = null, chrome = null;

async function startService() {
  if (external) return;
  if (!existsSync(jar)) throw new Error('先执行 mvn package，找不到 ' + jar);
  const log = join(runDir, 'server.log');
  serviceFd = openSync(log, 'a');
  service = spawn(process.env.JAVA_BIN || 'java', ['-jar', jar,
    '--server.address=127.0.0.1', '--server.port=' + httpPort, '--netty.port=' + wsPort,
    '--gomoku.data-dir=' + join(runDir, 'data')], {
    cwd: runDir, windowsHide: true, stdio: ['ignore', serviceFd, serviceFd]
  });
  const ready = await waitUntil(async () => {
    if (service.exitCode !== null) throw new Error('服务退出码 ' + service.exitCode);
    const response = await fetch(REST + '/actuator/health', { signal: AbortSignal.timeout(1000) });
    return response.ok && (await response.json()).status === 'UP';
  }, '隔离服务启动', 45000);
  if (!ready) throw new Error('隔离服务未启动，日志 ' + log + '\n' + readFileSync(log, 'utf8').slice(-1600));
}
async function stopService() {
  if (!service || service.exitCode !== null) return;
  service.kill();
  await Promise.race([
    new Promise(done => service.once('exit', done)),
    sleep(5000).then(() => { if (service.exitCode === null) service.kill('SIGKILL'); })
  ]);
  if (serviceFd !== null) {
    try { closeSync(serviceFd); } catch (_) {}
    serviceFd = null;
  }
}
async function startChrome() {
  if (fixedChrome) return;
  const executable = process.env.CHROME_BIN || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
  if (!existsSync(executable)) throw new Error('找不到 Chrome，可用 CHROME_BIN 指定');
  const profile = join(runDir, 'chrome-profile');
  mkdirSync(profile, { recursive: true });
  chrome = spawn(executable, ['--headless=new', '--disable-gpu',
    '--remote-debugging-port=' + cdpPort, '--user-data-dir=' + profile,
    '--no-first-run', '--no-default-browser-check', 'about:blank'], {
    windowsHide: true, stdio: 'ignore'
  });
}
async function stopChrome() {
  if (!chrome || chrome.exitCode !== null) return;
  chrome.kill();
  await Promise.race([
    new Promise(done => chrome.once('exit', done)),
    sleep(5000).then(() => { if (chrome.exitCode === null) chrome.kill('SIGKILL'); })
  ]);
}

function openWaitingRoom(name, gameType) {
  return new Promise((resolveRoom, reject) => {
    const socket = new WebSocket(WS_URL);
    const state = { socket, roomId: null };
    const timer = setTimeout(() => reject(new Error('等待房创建超时')), 10000);
    socket.onopen = () => socket.send(JSON.stringify({
      type: 'JOIN', playerName: name, gameType, createRoom: false
    }));
    socket.onmessage = event => {
      let message;
      try { message = JSON.parse(String(event.data)); } catch (_) { return; }
      if (message.type === 'ERROR') {
        clearTimeout(timer);
        reject(new Error(message.message || '等待房创建失败'));
      } else if (message.type === 'WAITING' && message.roomId && !state.roomId) {
        state.roomId = message.roomId;
        clearTimeout(timer);
        resolveRoom(state);
      }
    };
    socket.onerror = () => {
      clearTimeout(timer);
      reject(new Error('WebSocket 连接失败'));
    };
  });
}

async function getCdpUrl() {
  for (let i = 0; i < 80; i++) {
    try {
      const response = await fetch('http://127.0.0.1:' + cdpPort + '/json/version');
      if (response.ok) {
        const body = await response.json();
        if (body.webSocketDebuggerUrl) return body.webSocketDebuggerUrl;
      }
    } catch (_) {}
    await sleep(200);
  }
  throw new Error('Chrome CDP 未就绪');
}

class Cdp {
  constructor(socket) {
    this.socket = socket;
    this.nextId = 0;
    this.pending = new Map();
    this.sessionId = null;
    socket.onmessage = event => {
      const message = JSON.parse(String(event.data));
      if (message.id && this.pending.has(message.id)) {
        const pending = this.pending.get(message.id);
        this.pending.delete(message.id);
        if (message.error) pending.reject(new Error(JSON.stringify(message.error)));
        else pending.resolve(message.result);
      }
    };
  }
  send(method, params = {}, useSession = true) {
    const id = ++this.nextId;
    const payload = { id, method, params };
    if (useSession && this.sessionId) payload.sessionId = this.sessionId;
    return new Promise((resolveCall, reject) => {
      this.pending.set(id, { resolve: resolveCall, reject });
      this.socket.send(JSON.stringify(payload));
      setTimeout(() => {
        if (this.pending.delete(id)) reject(new Error('CDP 超时: ' + method));
      }, 30000);
    });
  }
  async eval(expression) {
    const result = await this.send('Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise: true
    });
    if (result.exceptionDetails) {
      const exception = result.exceptionDetails.exception || {};
      throw new Error('JS 异常: ' + (exception.description || exception.value
        || result.exceptionDetails.text));
    }
    return result.result ? result.result.value : undefined;
  }
  async screenshot(name) {
    const result = await this.send('Page.captureScreenshot', { format: 'png' });
    const { writeFileSync } = await import('node:fs');
    writeFileSync(join(output, name), Buffer.from(result.data, 'base64'));
  }
}

let rival = null, browser = null;
let cdp = null;
try {
  console.log('== 下一局推荐（真实 REST + 等待房 + 浏览器）==');
  await startService();
  await startChrome();

  const anonymous = await fetch(REST + '/api/recommendations?limit=3', {
    signal: AbortSignal.timeout(12000)
  });
  const anonymousBody = await anonymous.json();
  const anonymousRows = anonymousBody.recommendations || [];
  check('匿名接口返回 200 与 no-store',
    anonymous.ok && /no-store/i.test(anonymous.headers.get('cache-control') || ''));
  check('匿名返回 3 条且不绑定身份',
    anonymousRows.length === 3 && anonymousBody.identityBound === false,
    JSON.stringify(anonymousBody));
  check('每条推荐都有玩法、可解释原因和计分',
    anonymousRows.every(row => row.gameType && row.name && row.reason
      && Number.isInteger(row.score) && Number.isInteger(row.waitingPlayers)),
    JSON.stringify(anonymousRows));
  check('响应明确排序口径', /等待/.test(anonymousBody.basis || '')
    && /新玩法/.test(anonymousBody.basis || '') && /热门/.test(anonymousBody.basis || ''),
    anonymousBody.basis);

  // 用 REST 列表选一个当前没人等待的玩法，确保等待房真的改变推荐排序。
  const cleanType = anonymousRows.map(row => row.gameType)
    .find(type => !anonymousRows.find(row => row.gameType === type).waitingPlayers) || 'UNO';
  rival = await openWaitingRoom('推荐验收等待者' + Math.floor(Math.random() * 10000), cleanType);
  const waitingBody = await (await fetch(REST + '/api/recommendations?limit=3', {
    signal: AbortSignal.timeout(12000)
  })).json();
  const top = (waitingBody.recommendations || [])[0] || {};
  check('真人等待后该玩法成为首条推荐',
    top.gameType === cleanType && top.waitingPlayers === 1
      && /真人在等待/.test(top.reason || ''),
    JSON.stringify(top));
  check('等待房进入匹配总览',
    await waitUntil(async () => {
      const overview = await (await fetch(REST + '/api/match/overview')).json();
      const row = (overview.games || []).find(game => game.gameType === cleanType);
      return row && row.waitingRooms === 1 && row.waitingPlayers === 1;
    }, '匹配总览更新'));

  const guest = await (await fetch(REST + '/api/guest', { method: 'POST' })).json();
  check('游客身份签发用于个性化推荐', !!guest.token);
  const personalized = await fetch(REST + '/api/recommendations?limit=3', {
    headers: { Authorization: 'Bearer ' + guest.token },
    signal: AbortSignal.timeout(12000)
  }).then(response => response.json());
  check('携带游客身份返回 identityBound=true',
    personalized.identityBound === true
      && (personalized.recommendations || []).length === 3,
    JSON.stringify({ identityBound: personalized.identityBound }));

  const browserSocket = new WebSocket(await getCdpUrl());
  await new Promise((done, fail) => {
    browserSocket.onopen = done;
    browserSocket.onerror = fail;
  });
  cdp = new Cdp(browserSocket);
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' }, false);
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true }, false);
  cdp.sessionId = sessionId;
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Network.enable');
  await cdp.send('Network.clearBrowserCache');
  await cdp.send('Storage.clearDataForOrigin', {
    origin: new URL(APP).origin, storageTypes: 'service_workers,cache_storage'
  });
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 1280, height: 900, deviceScaleFactor: 1, mobile: false
  });
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `try{localStorage.setItem('gomoku_guest', ${JSON.stringify(guest.token)});`
      + `localStorage.setItem('gomoku_onboarded','1');}catch(_){};`
      + `window.alert=function(m){window.__lastAlert=String(m);};window.confirm=function(){return true;};`
      + `window.__errors=[];window.addEventListener('error',e=>window.__errors.push(String(e.message)));`
      + `window.addEventListener('unhandledrejection',e=>window.__errors.push(String(e.reason)));`
  });
  await cdp.send('Page.navigate', { url: APP });
  check('登录页脚本就绪', await waitUntil(() => cdp.eval("typeof joinGame === 'function'"), '登录页'));
  await cdp.eval("document.getElementById('ws-url').value = " + JSON.stringify(WS_URL)
    + ";document.getElementById('player-name').value = '推荐验收玩家';joinGame();");
  check('进入大厅', await waitUntil(() => cdp.eval(
    "document.getElementById('lobby-screen').style.display === 'block'"), '大厅'));
  check('大厅渲染 3 张推荐卡', await waitUntil(() => cdp.eval(
    "document.querySelectorAll('#recommendation-strip .recommendation-card').length === 3"), '推荐卡'));
  check('首张推荐卡对应正在等待的玩法', await cdp.eval(
    "document.querySelector('#recommendation-strip .recommendation-card').dataset.recommend")
    === cleanType);
  const desktop = await cdp.eval(
    "(() => { const box=document.getElementById('recommendation-strip'), cards=Array.from(box.querySelectorAll('.recommendation-card'));"
    + "const rect=box.getBoundingClientRect();return {visible:box.offsetParent!==null,width:rect.width,"
    + "scrollWidth:box.scrollWidth,clientWidth:box.clientWidth,cards:cards.length,"
    + "cardHeights:cards.map(c=>c.getBoundingClientRect().height),"
    + "placeholder:cards.some(c=>c.textContent.includes('${escHtml')),"
    + "activeName:(document.querySelector('#game-selector .game-card.selected .game-card-name')||{}).textContent};})()");
  check('推荐条桌面可见且不横向溢出',
    desktop.visible && desktop.width > 300 && desktop.scrollWidth <= desktop.clientWidth + 1,
    JSON.stringify(desktop));
  check('推荐卡显示实际文案而不是模板占位符', !desktop.placeholder, JSON.stringify(desktop));

  const target = await cdp.eval(
    "(() => { const card=document.querySelector('#recommendation-strip .recommendation-card');"
    + "return {text:card.textContent,icon:(card.querySelector('strong')||{}).textContent,"
    + "name:GameModules[" + JSON.stringify(cleanType) + "].name};})()");
  await cdp.eval(
    "document.querySelector('#recommendation-strip .recommendation-card').click();");
  check('点击推荐卡选中对应玩法', await waitUntil(() => cdp.eval(
    "selectedGameType === " + JSON.stringify(cleanType)
      + " && document.querySelector('#game-selector .game-card.selected').dataset.gameType"
      + " === " + JSON.stringify(cleanType)), '选中推荐玩法'));
  check('点击推荐卡切回「全部」分类', await cdp.eval(
    "selectedCategory === 'ALL' && document.querySelector('#game-categories .selected').textContent === '全部'"));
  check('点击推荐卡刷新为玩法对应房间',
    await waitUntil(() => cdp.eval(
      "Array.from(document.querySelectorAll('#room-list-container .room-item')).every("
      + "r=>!r.dataset.gameType||r.dataset.gameType===" + JSON.stringify(cleanType) + ")"),
      '房间列表过滤'));
  check('推荐卡显示对应玩法图标、名称与原因',
    String(target.text || '').includes(target.name) && String(target.text || '').includes('等待'),
    JSON.stringify(target));
  await cdp.screenshot('recommendations-desktop.png');

  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 390, height: 844, deviceScaleFactor: 1, mobile: true
  });
  await cdp.eval("window.scrollTo(0,0)");
  await sleep(300);
  const mobile = await cdp.eval(
    "(() => { const box=document.getElementById('recommendation-strip'), page=document.documentElement;"
    + "const cards=Array.from(box.querySelectorAll('.recommendation-card'));"
    + "const rect=box.getBoundingClientRect();return {left:rect.left,right:rect.right,vw:innerWidth,"
    + "outer:box.scrollWidth>box.clientWidth+1,page:page.scrollWidth>page.clientWidth+1,"
    + "minHeight:Math.min(...cards.map(c=>c.getBoundingClientRect().height))};})()");
  check('390px 推荐条与整页无横向溢出',
    mobile.left >= 0 && mobile.right <= mobile.vw + 1 && !mobile.outer && !mobile.page,
    JSON.stringify(mobile));
  check('390px 推荐卡触控高度至少 44px', mobile.minHeight >= 44, mobile.minHeight);
  await cdp.screenshot('recommendations-mobile.png');
  check('页面无脚本异常', await cdp.eval("(window.__errors||[]).length===0"),
    JSON.stringify(await cdp.eval("window.__errors||[]")));

  console.log(failed === 0
    ? `[ui-recommend] PASS (${passed}/${passed})`
    : `[ui-recommend] FAIL (${failed} failed, ${passed} passed)`);
} catch (error) {
  failed++;
  console.error('[ui-recommend] FAIL 异常 :: ' + (error && (error.stack || error.message)));
} finally {
  try { if (rival) rival.socket.close(); } catch (_) {}
  try { if (cdp) cdp.socket.close(); } catch (_) {}
  await stopChrome();
  await stopService();
}
process.exit(failed === 0 ? 0 : 1);
