// 双浏览器上下文验收多人再来一局：准备进度、撤回、底部常驻反馈与手机布局。
// 启动独立 Chrome --remote-debugging-port=9345；可覆盖 CDP_PORT/APP_URL/API_BASE/WS_URL/OUT_DIR。
import { mkdirSync, writeFileSync } from 'node:fs';
const CDP_PORT = process.env.CDP_PORT || 9345;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const API = process.env.API_BASE || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'target/rematch-validation/shots';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let passed = 0, failed = 0;

function check(name, condition, detail) {
  if (condition) {
    passed++;
    console.log('[ui-rematch] PASS ' + name);
  } else {
    failed++;
    console.error('[ui-rematch] FAIL ' + name + (detail ? ' :: ' + detail : ''));
  }
}

async function until(predicate, label, timeout = 15000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await sleep(100);
  }
  throw new Error('等待超时: ' + label);
}

async function request(path, token, method = 'GET', body) {
  const response = await fetch(API + path, {
    method,
    headers: { Authorization: 'Bearer ' + token, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(10000)
  });
  const result = await response.json();
  if (!response.ok) throw new Error(path + ': ' + JSON.stringify(result));
  return result;
}

class Page {
  constructor(ws) {
    this.ws = ws;
    this.sequence = 0;
    this.pending = new Map();
    this.errors = [];
    ws.onmessage = event => {
      const message = JSON.parse(event.data);
      if (message.method === 'Runtime.exceptionThrown') {
        const details = message.params.exceptionDetails;
        this.errors.push(details.exception?.description || details.text);
      }
      if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
        this.errors.push(message.params.args.map(arg => arg.value || arg.description).join(' '));
      }
      const pending = this.pending.get(message.id);
      if (!pending) return;
      clearTimeout(pending.timer);
      this.pending.delete(message.id);
      if (message.error) pending.reject(new Error(JSON.stringify(message.error)));
      else pending.resolve(message.result);
    };
    ws.onclose = () => {
      for (const pending of this.pending.values()) {
        clearTimeout(pending.timer);
        pending.reject(new Error('Chrome connection closed'));
      }
      this.pending.clear();
    };
  }

  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        ws.close();
        reject(new Error('Chrome handshake timed out'));
      }, 10000);
      ws.onopen = () => {
        clearTimeout(timer);
        resolve();
      };
      ws.onerror = () => {
        clearTimeout(timer);
        reject(new Error('Chrome handshake failed'));
      };
    });
    return new Page(ws);
  }

  send(method, params = {}, useSession = true) {
    const id = ++this.sequence;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error('CDP timeout: ' + method));
      }, 15000);
      this.pending.set(id, { resolve, reject, timer });
      this.ws.send(JSON.stringify({
        id, method, params, ...(useSession && this.sessionId ? { sessionId: this.sessionId } : {})
      }));
    });
  }

  async eval(expression) {
    const result = await this.send('Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise: true
    });
    if (result.exceptionDetails) {
      throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    }
    return result.result.value;
  }

  async screenshot(name) {
    mkdirSync(OUT, { recursive: true });
    const result = await this.send('Page.captureScreenshot', { format: 'png' });
    writeFileSync(OUT + '/' + name + '.png', Buffer.from(result.data, 'base64'));
  }
}

let browser;
const pages = [], contexts = [];
try {
  const info = await (await fetch('http://127.0.0.1:' + CDP_PORT + '/json/version')).json();
  browser = await Page.connect(info.webSocketDebuggerUrl);

  async function openPlayer(name, width) {
    const context = (await browser.send('Target.createBrowserContext', {}, false)).browserContextId;
    contexts.push(context);
    const page = await Page.connect(info.webSocketDebuggerUrl);
    pages.push(page);
    const { targetId } = await page.send('Target.createTarget',
      { url: 'about:blank', browserContextId: context }, false);
    page.sessionId = (await page.send('Target.attachToTarget', { targetId, flatten: true }, false)).sessionId;
    await page.send('Page.enable');
    await page.send('Runtime.enable');
    await page.send('Page.addScriptToEvaluateOnNewDocument', {
      source: "localStorage.setItem('gomoku_onboarded','1');"
        + "window.alert=m=>{window.__lastAlert=String(m);};window.confirm=()=>true;"
    });
    await page.send('Emulation.setDeviceMetricsOverride',
      { width, height: 900, deviceScaleFactor: 1, mobile: width < 600 });
    await page.send('Page.navigate', { url: APP });
    await until(() => page.eval("typeof joinGame==='function'"), name + ' 登录页');
    await page.eval("document.getElementById('ws-url').value=" + JSON.stringify(WS_URL)
      + ";document.getElementById('player-name').value=" + JSON.stringify(name) + ';joinGame();');
    await until(() => page.eval("gameState==='LOBBY' && !!guestToken && ws.readyState===1"), name + ' 进入大厅');
    page.token = await page.eval('guestToken');
    page.identity = (await request('/api/guest', page.token, 'POST')).id;
    return page;
  }

  const desktop = await openPlayer('重开桌面甲', 1280);
  const mobile = await openPlayer('重开手机乙', 390);
  check('两个页面持有独立身份', desktop.identity !== mobile.identity);

  await desktop.eval("selectedGameType='GOMOKU';createRoom();");
  await until(() => desktop.eval("gameState==='WAITING' && !!roomId"), '桌面建等待房');
  const room = await desktop.eval('roomId');
  await mobile.eval("selectedGameType='GOMOKU';document.getElementById('join-code').value="
    + JSON.stringify(room.replace(/^ROOM-/, '')) + ';joinByCode();');
  await until(() => desktop.eval("gameState==='PLAYING'") && mobile.eval("gameState==='PLAYING'"), '双方开局');
  check('双人真实开局', await desktop.eval("gameState==='PLAYING'") && await mobile.eval("gameState==='PLAYING'"));

  await desktop.eval("send({type:'SURRENDER'});");
  await until(() => desktop.eval("gameState==='OVER'") && mobile.eval("gameState==='OVER'"), '双方结算');
  await until(() => desktop.eval("document.getElementById('gameover-overlay').classList.contains('show')"), '桌面结算浮层');
  check('结算浮层初始无重开进度', await desktop.eval(
    "document.getElementById('rematch-status').hidden === true"));
  check('无等待时按钮保持「再来一局」', await desktop.eval(
    "document.getElementById('gameover-restart').textContent === '再来一局'"));

  await desktop.eval("document.getElementById('gameover-restart').click();");
  await until(() => mobile.eval("!!rematchState && rematchState.pending===true"), '手机收到等待状态');
  check('准备后双方收到有人发起重开', await desktop.eval("!!rematchState && rematchState.pending===true")
    && await mobile.eval("!!rematchState && rematchState.pending===true"));
  check('准备者按钮变「撤回准备」', await desktop.eval(
    "document.getElementById('gameover-restart').textContent === '撤回准备'"));
  check('未准备者按钮变「接受再来一局」', await mobile.eval(
    "document.getElementById('restart-bar-restart').textContent === '接受再来一局'"
    + " || document.getElementById('gameover-restart').textContent === '接受再来一局'"));
  const detail = await mobile.eval("document.getElementById('rematch-status').textContent");
  check('浮层展示准备人数与等待者', detail.includes('已准备 1/2') && detail.includes('重开手机乙'), detail);
  check('浮层倒计时可见', /倒计时 (1:00|0:5\d)/.test(detail), detail);

  await mobile.eval("document.querySelector('#gameover-overlay .btn-review').click();");
  await until(() => mobile.eval("document.getElementById('restart-bar').style.display==='flex'"), '手机底部操作条');
  const barState = await mobile.eval(`(() => {
    const bar = document.getElementById('restart-bar');
    const status = document.getElementById('restart-rematch-status');
    const buttons = Array.from(bar.querySelectorAll('button')).map(b => {
      const r = b.getBoundingClientRect();
      return { text: b.textContent.trim(), height: Math.round(r.height), left: r.left, right: r.right };
    });
    const rect = status.getBoundingClientRect();
    return {
      visible: bar.style.display === 'flex' && bar.offsetParent !== null,
      statusHidden: status.hidden,
      statusText: status.textContent,
      statusWidth: Math.round(rect.width),
      buttons,
      overflow: bar.scrollWidth > bar.clientWidth + 1,
      pageOverflow: document.documentElement.scrollWidth > window.innerWidth + 1
    };
  })()`);
  check('关闭浮层后底部仍展示重开进度', barState.visible && !barState.statusHidden
    && barState.statusText.includes('已准备 1/2'), JSON.stringify(barState));
  check('底部倒计时持续更新', /倒计时 (1:00|0:5\d)/.test(barState.statusText), barState.statusText);
  check('手机按钮达到 44px 且无横向溢出', barState.buttons.length === 2
    && barState.buttons.every(b => b.height >= 44)
    && !barState.overflow && !barState.pageOverflow,
    JSON.stringify({ buttons: barState.buttons, overflow: barState.overflow, pageOverflow: barState.pageOverflow }));
  await mobile.screenshot('rematch-mobile-waiting');

  await mobile.eval("document.getElementById('restart-bar-restart').click();");
  await until(() => desktop.eval("gameState==='PLAYING'") && mobile.eval("gameState==='PLAYING'"), '两人准备后开局');
  check('双方准备后自动进入第二局', await desktop.eval("gameState==='PLAYING'")
    && await mobile.eval("gameState==='PLAYING'"));
  check('开局后重开状态清空', await desktop.eval("!rematchState || rematchState.pending === false")
    && await mobile.eval("!rematchState || rematchState.pending === false"));
  check('第二局棋盘重新清空', await desktop.eval("board.every(row => row.every(cell => cell === 0))")
    && await mobile.eval("board.every(row => row.every(cell => cell === 0))"));
  await desktop.screenshot('rematch-desktop-second-game');

  await desktop.eval("send({type:'SURRENDER'});");
  await until(() => desktop.eval("gameState==='OVER'") && mobile.eval("gameState==='OVER'"), '第二局结算');
  await until(() => desktop.eval("document.getElementById('gameover-overlay').classList.contains('show')"),
    '第二局结算浮层');
  await desktop.eval("document.getElementById('gameover-restart').click();");
  await until(() => desktop.eval("!!rematchState && rematchState.pending===true"), '再次等待');
  await desktop.eval("document.getElementById('gameover-restart').click();");
  await until(() => desktop.eval("!rematchState || rematchState.pending===false"), '撤回准备');
  const cancelled = await mobile.eval("document.getElementById('restart-rematch-status').textContent");
  check('撤回后双方等待状态取消', await mobile.eval("!rematchState || rematchState.pending===false")
    && await desktop.eval("document.getElementById('gameover-restart').textContent === '再来一局'"));
  check('撤回后系统消息可见', await desktop.eval(
    "document.getElementById('chat-messages').textContent.includes('撤回')")
    || await mobile.eval("document.getElementById('chat-messages').textContent.includes('撤回')"),
    JSON.stringify({ mobileStatus: cancelled }));

  const errors = pages.flatMap(page => page.errors);
  check('双方页面无脚本异常或控制台错误', errors.length === 0, JSON.stringify(errors));
  check('双方未弹出错误对话框', !await desktop.eval('window.__lastAlert')
    && !await mobile.eval('window.__lastAlert'));
} catch (error) {
  failed++;
  console.error('[ui-rematch] FAIL 异常 :: ' + (error && error.stack || error));
} finally {
  for (const context of contexts) {
    try { await browser.send('Target.disposeBrowserContext', { browserContextId: context }, false); } catch (_) {}
  }
  for (const page of pages) {
    try { page.ws.close(); } catch (_) {}
  }
  if (browser) {
    try { browser.ws.close(); } catch (_) {}
  }
}

console.log(failed === 0
  ? '[ui-rematch] PASS (' + passed + '/' + passed + ')'
  : '[ui-rematch] FAIL (' + failed + ' failed, ' + passed + ' passed)');
process.exitCode = failed === 0 ? 0 : 1;
