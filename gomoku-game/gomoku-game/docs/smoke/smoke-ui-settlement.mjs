// 结算页玩法解释与「匹配下一局」验收：真实 Chrome 双上下文 + 真实房间。
// 启动独立 Chrome --remote-debugging-port=9346；可覆盖 CDP_PORT/APP_URL/API_BASE/WS_URL/OUT_DIR。
import { mkdirSync, writeFileSync } from 'node:fs';
const CDP_PORT = process.env.CDP_PORT || 9346;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const API = process.env.API_BASE || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const OUT = process.env.OUT_DIR || 'target/settlement-validation/shots';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let passed = 0, failed = 0;

function check(name, condition, detail) {
  if (condition) {
    passed++;
    console.log('[ui-settlement] PASS ' + name);
  } else {
    failed++;
    console.error('[ui-settlement] FAIL ' + name + (detail ? ' :: ' + detail : ''));
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
      ws.onopen = () => { clearTimeout(timer); resolve(); };
      ws.onerror = () => { clearTimeout(timer); reject(new Error('Chrome handshake failed')); };
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

  const desktop = await openPlayer('结算桌面甲', 1280);
  const mobile = await openPlayer('结算手机乙', 390);
  check('两个页面持有独立身份', desktop.identity !== mobile.identity);

  // 私人房：结算页显示玩法解释，但不能把玩家引导到公开匹配。
  await desktop.eval("selectedGameType='GOMOKU';createRoom();");
  await until(() => desktop.eval("gameState==='WAITING' && !!roomId"), '桌面建私人房');
  const room = await desktop.eval('roomId');
  await mobile.eval("selectedGameType='GOMOKU';document.getElementById('join-code').value="
    + JSON.stringify(room.replace(/^ROOM-/, '')) + ';joinByCode();');
  await until(() => desktop.eval("gameState==='PLAYING'") && mobile.eval("gameState==='PLAYING'"), '双方开局');
  await desktop.eval("send({type:'SURRENDER'});");
  await until(() => desktop.eval("gameState==='OVER'") && mobile.eval("gameState==='OVER'"), '双方结算');
  await until(() => desktop.eval("document.getElementById('gameover-overlay').classList.contains('show')"),
    '结算浮层');

  const privateState = await desktop.eval(`(() => {
    const insight = document.getElementById('gameover-insight');
    return {
      insightHidden: insight.hidden,
      insightText: insight.textContent,
      privateRoom: currentRoomPrivate,
      aiSeats: currentRoomAiSeats,
      nextMatch: document.getElementById('gameover-nextmatch').style.display,
      barNextMatch: document.getElementById('restart-bar-nextmatch').style.display
    };
  })()`);
  check('结算页展示五子棋玩法解释', !privateState.insightHidden
    && privateState.insightText.includes('本局解释')
    && privateState.insightText.includes('五子'), JSON.stringify(privateState));
  check('失败局给出下次可执行的提示', privateState.insightText.includes('下次可以：'),
    privateState.insightText);
  check('认输局不再误报五连原因', privateState.insightText.includes('主动认输')
    && !privateState.insightText.includes('对手先连成五子'), privateState.insightText);
  check('私人房被服务端标记', privateState.privateRoom === true, JSON.stringify(privateState));
  check('私人房结算不出现「匹配下一局」', privateState.nextMatch === 'none'
    && privateState.barNextMatch === 'none', JSON.stringify(privateState));

  await desktop.eval("document.querySelector('#gameover-overlay .btn-review').click();");
  await until(() => desktop.eval("document.getElementById('restart-bar').style.display==='flex'"), '底部操作条');
  check('底部操作条同样隐藏匹配入口', await desktop.eval(
    "document.getElementById('restart-bar-nextmatch').style.display === 'none'"));

  // 手机：解释区不产生横向溢出，按钮仍可点到。
  await desktop.eval("leaveRoom();");
  await until(() => desktop.eval("gameState==='LOBBY'"), '桌面返回大厅');
  await mobile.eval("leaveRoom();");
  await until(() => mobile.eval("gameState==='LOBBY'"), '手机返回大厅');

  await desktop.eval("selectedGameType='GOMOKU';quickMatch();");
  await until(() => desktop.eval("gameState==='WAITING' && !!roomId"), '桌面进入公开匹配');
  const publicRoom = await desktop.eval('roomId');
  await mobile.eval("selectedGameType='GOMOKU';document.getElementById('join-code').value="
    + JSON.stringify(publicRoom.replace(/^ROOM-/, '')) + ';joinByCode();');
  await until(() => desktop.eval("gameState==='PLAYING'") && mobile.eval("gameState==='PLAYING'"), '公开房开局');
  await mobile.eval("send({type:'SURRENDER'});");
  await until(() => desktop.eval("gameState==='OVER'") && mobile.eval("gameState==='OVER'"), '公开房结算');
  await until(() => desktop.eval("document.getElementById('gameover-overlay').classList.contains('show')"),
    '公开房结算浮层');

  const publicState = await desktop.eval(`(() => {
    const insight = document.getElementById('gameover-insight');
    const next = document.getElementById('gameover-nextmatch');
    const rect = insight.getBoundingClientRect();
    return {
      privateRoom: currentRoomPrivate,
      nextMatch: next.style.display,
      nextMatchText: next.textContent,
      insightWidth: Math.round(rect.width),
      overflow: document.documentElement.scrollWidth > window.innerWidth + 1
    };
  })()`);
  check('公开匹配房识别正确', publicState.privateRoom === false, JSON.stringify(publicState));
  check('公开房结算显示「匹配下一局」', publicState.nextMatch === 'inline-block'
    && publicState.nextMatchText === '匹配下一局', JSON.stringify(publicState));
  check('桌面结算页无横向溢出', !publicState.overflow, JSON.stringify(publicState));

  const mobileState = await mobile.eval(`(() => {
    const overlay = document.getElementById('gameover-overlay');
    const insight = document.getElementById('gameover-insight');
    const buttons = Array.from(overlay.querySelectorAll('.gameover-btns button'))
      .filter(b => b.offsetParent !== null)
      .map(b => { const r = b.getBoundingClientRect(); return { text: b.textContent.trim(), height: Math.round(r.height), left: r.left, right: r.right }; });
    return {
      insightText: insight.textContent,
      insightRect: { left: Math.round(insight.getBoundingClientRect().left),
        right: Math.round(insight.getBoundingClientRect().right) },
      buttons,
      pageOverflow: document.documentElement.scrollWidth > window.innerWidth + 1,
      overlayOverflow: overlay.scrollWidth > overlay.clientWidth + 1,
      scrollable: overlay.scrollHeight > overlay.clientHeight + 1
    };
  })()`);
  check('手机结算页展示玩法解释', mobileState.insightText.includes('本局解释'), mobileState.insightText);
  check('手机解释区在视口内', mobileState.insightRect.left >= 0
    && mobileState.insightRect.right <= 390, JSON.stringify(mobileState.insightRect));
  check('手机无横向溢出', !mobileState.pageOverflow && !mobileState.overlayOverflow,
    JSON.stringify(mobileState));
  check('手机按钮达到 44px 触控高度', mobileState.buttons.length >= 4
    && mobileState.buttons.every(b => b.height >= 44), JSON.stringify(mobileState.buttons));
  await mobile.screenshot('settlement-mobile');

  // 真点一次「匹配下一局」：离开结算房并进入公开匹配。
  await desktop.eval("document.getElementById('gameover-nextmatch').click();");
  await until(() => desktop.eval("gameState==='WAITING' && !!roomId"), '点击后进入新一轮匹配');
  const rejoined = await desktop.eval(`(() => ({
    state: gameState,
    roomId,
    publicRoom: !currentRoomPrivate,
    nextMatchHidden: document.getElementById('gameover-nextmatch').style.display === 'none',
    sameRoom: roomId === ${JSON.stringify(publicRoom)}
  }))()`);
  check('匹配下一局创建新的公开等待房', rejoined.state === 'WAITING' && rejoined.publicRoom
    && !rejoined.sameRoom, JSON.stringify(rejoined));
  check('进入新匹配后结算入口已收起', rejoined.nextMatchHidden, JSON.stringify(rejoined));

  // 低人口兜底：单人公开匹配 20 秒后给出补电脑入口，取消后仍留在房间继续等待。
  await until(() => desktop.eval("document.getElementById('confirm-dialog')?.classList.contains('show')"),
    '低人口匹配提示', 22000);
  const lowPopulation = await desktop.eval(`(() => ({
    text: document.getElementById('confirm-msg').textContent,
    state: gameState,
    roomId,
    cancelVisible: document.getElementById('confirm-cancel').style.display !== 'none'
  }))()`);
  check('低人口匹配提示文案正确',
    lowPopulation.text.includes('暂时没有匹配到其他玩家')
      && lowPopulation.text.includes('补入电脑'),
    JSON.stringify(lowPopulation));
  check('低人口提示发生在仍等待的公开房',
    lowPopulation.state === 'WAITING' && lowPopulation.roomId === rejoined.roomId
      && lowPopulation.cancelVisible,
    JSON.stringify(lowPopulation));
  await desktop.eval("document.getElementById('confirm-cancel').click();");
  check('取消补电脑后继续等待，不会误开人机局',
    await desktop.eval("gameState === 'WAITING' && !currentRoomAiSeats"),
    await desktop.eval("JSON.stringify({state:gameState, aiSeats:currentRoomAiSeats})"));

  // 离场收尾：双方都回到大厅，测试房间不再残留。
  await desktop.eval("leaveRoom();");
  await mobile.eval("leaveRoom();");
  await until(() => desktop.eval("gameState === 'LOBBY'")
    && mobile.eval("gameState === 'LOBBY'"), '双方离场');

  const errors = pages.flatMap(page => page.errors);
  check('双方页面无脚本异常或控制台错误', errors.length === 0, JSON.stringify(errors));
  check('双方未弹出错误对话框', !await desktop.eval('window.__lastAlert')
    && !await mobile.eval('window.__lastAlert'));
} catch (error) {
  failed++;
  console.error('[ui-settlement] FAIL 异常 :: ' + (error && error.stack || error));
  if (pages.length) {
    for (let i = 0; i < pages.length; i++) {
      try {
        const state = await pages[i].eval(`(() => ({
          state: typeof gameState === 'undefined' ? null : gameState,
          roomId: typeof roomId === 'undefined' ? null : roomId,
          publicRoom: typeof currentRoomPrivate === 'undefined' ? null : !currentRoomPrivate,
          socket: typeof ws === 'undefined' || !ws ? null : ws.readyState,
          alert: window.__lastAlert || null,
          confirm: document.getElementById('confirm-dialog')?.classList.contains('show') || false,
          confirmText: document.getElementById('confirm-dialog')?.textContent?.trim() || ''
        }))()`);
        state.errors = pages[i].errors;
        console.error('[ui-settlement] PAGE ' + i + ' :: ' + JSON.stringify(state));
      } catch (diagnosticError) {
        console.error('[ui-settlement] PAGE ' + i + ' 诊断失败 :: ' + diagnosticError.message);
      }
    }
  }
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
  ? '[ui-settlement] PASS (' + passed + '/' + passed + ')'
  : '[ui-settlement] FAIL (' + failed + ' failed, ' + passed + ' passed)');
process.exitCode = failed === 0 ? 0 : 1;
