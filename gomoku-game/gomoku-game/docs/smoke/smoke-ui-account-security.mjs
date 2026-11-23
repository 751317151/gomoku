// 账号安全 UI 真实浏览器验收：注册、改密、旧凭证失效、当前设备换发、手机布局。
// Chrome 调试端口默认 9347；可覆盖 CDP_PORT / APP_URL / API_BASE / OUT_DIR。
import { mkdirSync, writeFileSync } from 'node:fs';
const CDP_PORT = process.env.CDP_PORT || 9347;
const APP = process.env.APP_URL || 'http://127.0.0.1:19000/';
const API = process.env.API_BASE || 'http://127.0.0.1:19000';
const OUT = process.env.OUT_DIR || 'target/account-security-validation/shots';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let passed = 0, failed = 0;

function check(name, condition, detail) {
  if (condition) {
    passed++;
    console.log('[ui-account-security] PASS ' + name);
  } else {
    failed++;
    console.error('[ui-account-security] FAIL ' + name + (detail ? ' :: ' + detail : ''));
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

async function debuggerUrl() {
  for (let i = 0; i < 40; i++) {
    try {
      const response = await fetch('http://127.0.0.1:' + CDP_PORT + '/json/version');
      const data = await response.json();
      if (data.webSocketDebuggerUrl) return data.webSocketDebuggerUrl;
    } catch (_) {}
    await sleep(250);
  }
  throw new Error('Chrome 调试端口未就绪');
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
  }

  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Chrome handshake timeout')), 10000);
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
      }, 20000);
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
let page;
let targetId;
try {
  browser = await Page.connect(await debuggerUrl());
  const target = await browser.send('Target.createTarget', { url: 'about:blank' }, false);
  targetId = target.targetId;
  const attached = await browser.send(
    'Target.attachToTarget', { targetId: target.targetId, flatten: true }, false);
  page = browser;
  page.sessionId = attached.sessionId;
  await page.send('Page.enable');
  await page.send('Runtime.enable');
  await page.send('Network.enable');
  await page.send('Emulation.setDeviceMetricsOverride', {
    width: 1280, height: 900, deviceScaleFactor: 1, mobile: false
  });
  await page.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `window.alert=function(m){window.__lastAlert=String(m);};window.confirm=function(){return true;};`
  });

  const username = ('uisecure' + Date.now().toString(36)).slice(0, 20);
  const oldPassword = 'OldSecure123';
  const newPassword = 'NewSecure456';
  await page.send('Page.navigate', { url: APP });
  await until(() => page.eval(
    "typeof switchAuthTab === 'function' && !!document.getElementById('tab-register')"), '页面就绪');

  await page.eval(`switchAuthTab('register');
    document.getElementById('player-name').value = '安全界面测试';
    document.getElementById('ws-url').value = '${process.env.WS_URL || 'ws://127.0.0.1:19001'}';
    document.getElementById('account-username').value = '${username}';
    document.getElementById('account-password').value = '${oldPassword}';
    authMode = 'register';`);
  await page.eval('joinGame()');
  await until(() => page.eval(
    "document.getElementById('account-state').textContent.includes('已登录')"), '注册登录态');
  check('注册后出现账号安全入口',
    await page.eval("!document.getElementById('account-security').hidden"));
  check('正常连接后没有误报断线重连',
    await page.eval("!document.getElementById('reconnect-bar').classList.contains('show')"));

  const oldToken = await page.eval("localStorage.getItem('gomoku_guest')");
  check('注册凭证已持久化', typeof oldToken === 'string' && oldToken.length === 43);
  check('注册后弹出一次性恢复码', await page.eval(
    "document.getElementById('recovery-code-modal').classList.contains('show')"
    + " && document.getElementById('recovery-code-value').textContent.replace(/-/g,'').length === 20"));
  const recoveryCode = await page.eval(
    "document.getElementById('recovery-code-value').textContent.replace(/-/g,'')");
  check('恢复码按 4 位分组展示', await page.eval(
    "document.getElementById('recovery-code-value').textContent.startsWith("
    + "document.getElementById('recovery-code-value').textContent.slice(0,4) + '-')"));
  await page.eval('closeRecoveryCode()');
  check('关闭恢复码弹窗后不再留在 DOM', await page.eval(
    "!document.getElementById('recovery-code-modal').classList.contains('show')"
    + " && document.getElementById('recovery-code-value').textContent === ''"));
  const recoveryDisk = await fetch(API + '/api/account/me', {
    headers: { Authorization: 'Bearer ' + oldToken }
  });
  check('恢复码不作为会话字段下发', recoveryDisk.status === 200
    && (await recoveryDisk.json()).recoveryCode === undefined);

  await page.eval('openAccountSecurity()');
  check('账号安全面板可打开',
    await page.eval("document.getElementById('account-security-modal').classList.contains('show')"));
  const visible = await page.eval(`(() => {
    const box = document.querySelector('.account-security-box');
    const rect = box.getBoundingClientRect();
    const center = document.elementFromPoint(
      rect.left + rect.width / 2, rect.top + Math.min(80, rect.height / 2));
    return {
      width: rect.width,
      height: rect.height,
      topmost: document.getElementById('account-security-modal').contains(center)
    };
  })()`);
  check('弹层在登录页遮罩之上且实际可见',
    visible.width > 300 && visible.height > 300 && visible.topmost, JSON.stringify(visible));
  check('面板显示账号与昵称',
    await page.eval("document.getElementById('account-security-identity').textContent.includes('" + username + "')"
      + " && document.getElementById('account-security-identity').textContent.includes('安全界面测试')"));

  await page.eval(`document.getElementById('account-old-password').value = '${oldPassword}';
    document.getElementById('account-new-password').value = 'short';
    document.getElementById('account-confirm-password').value = 'short';`);
  await page.eval('changeAccountPassword()');
  check('弱新密码在客户端被拦截',
    await page.eval("document.getElementById('account-security-status').textContent.includes('至少 8 位')"));

  await page.eval(`document.getElementById('account-new-password').value = '${newPassword}';
    document.getElementById('account-confirm-password').value = '${newPassword}';`);
  await page.eval('changeAccountPassword()');
  await until(() => page.eval(
    "document.getElementById('account-security-status').textContent.includes('其他设备已退出')"), '改密完成');
  const newToken = await page.eval("localStorage.getItem('gomoku_guest')");
  check('改密后当前设备换发新凭证', typeof newToken === 'string'
    && newToken.length === 43 && newToken !== oldToken);

  const oldMe = await fetch(API + '/api/account/me', {
    headers: { Authorization: 'Bearer ' + oldToken }
  });
  const newMe = await fetch(API + '/api/account/me', {
    headers: { Authorization: 'Bearer ' + newToken }
  });
  check('改密后旧凭证 REST 失效', oldMe.status === 401, 'status=' + oldMe.status);
  check('新凭证继续有效', newMe.status === 200, 'status=' + newMe.status);

  await page.eval(`document.getElementById('account-old-password').value = '${oldPassword}';
    document.getElementById('account-new-password').value = 'AnotherSecure789';
    document.getElementById('account-confirm-password').value = 'AnotherSecure789';`);
  await page.eval('changeAccountPassword()');
  await until(() => page.eval(
    "document.getElementById('account-security-status').textContent.includes('旧密码错误')"), '旧密码被拒');
  check('改密失败时保留当前会话', await page.eval(
    "localStorage.getItem('gomoku_guest') === '" + newToken + "'"));
  await page.screenshot('account-security-desktop');

  // 恢复码找回：改密后恢复码已轮换，旧码必须失效；新码重置密码并撤销全部会话。
  const rotatedCode = await page.eval(
    "document.getElementById('recovery-code-value').textContent.replace(/-/g,'')");
  check('改密后自动轮换恢复码', rotatedCode.length === 20 && rotatedCode !== recoveryCode);
  await page.eval('closeRecoveryCode(); closeAccountSecurity()');
  await page.eval('openPasswordRecovery()');
  check('忘记密码入口可打开', await page.eval(
    "document.getElementById('account-recovery-modal').classList.contains('show')"));
  await page.eval(`document.getElementById('account-recovery-username').value = '${username}';
    document.getElementById('account-recovery-code').value = '${recoveryCode}';
    document.getElementById('account-recovery-password').value = 'RecoveredPass789';
    document.getElementById('account-recovery-confirm').value = 'RecoveredPass789';`);
  await page.eval('recoverAccountWithCode()');
  await until(() => page.eval(
    "document.getElementById('account-recovery-status').textContent.includes('恢复码错误')"), '旧恢复码被拒');
  check('旧恢复码在改密后失效', await page.eval(
    "document.getElementById('account-recovery-modal').classList.contains('show')"));

  await page.eval(`document.getElementById('account-recovery-code').value = '${rotatedCode}';`);
  await page.eval('recoverAccountWithCode()');
  await until(() => page.eval(
    "document.getElementById('recovery-code-modal').classList.contains('show')"), '恢复成功展示新恢复码');
  const recoveredToken = await page.eval("localStorage.getItem('gomoku_guest')");
  check('恢复码重置后换发新会话', typeof recoveredToken === 'string'
    && recoveredToken.length === 43 && recoveredToken !== newToken);
  check('恢复成功提示所有旧设备退出', await page.eval(
    "document.getElementById('login-sub-status').textContent.includes('所有旧设备已退出')"));
  const revokedByRecovery = await fetch(API + '/api/account/me', {
    headers: { Authorization: 'Bearer ' + newToken }
  });
  check('恢复码重置撤销了原会话', revokedByRecovery.status === 401,
    'status=' + revokedByRecovery.status);
  const recoveredMe = await fetch(API + '/api/account/me', {
    headers: { Authorization: 'Bearer ' + recoveredToken }
  });
  check('新恢复会话可读取账号', recoveredMe.status === 200, 'status=' + recoveredMe.status);
  const recoveredCode = await page.eval(
    "document.getElementById('recovery-code-value').textContent.replace(/-/g,'')");
  check('恢复成功换发新恢复码', recoveredCode.length === 20 && recoveredCode !== rotatedCode);
  await page.eval('closeRecoveryCode()');

  await page.eval('closeAccountSecurity()');
  await page.send('Emulation.setDeviceMetricsOverride', {
    width: 390, height: 844, deviceScaleFactor: 2, mobile: true
  });
  await page.eval('openAccountSecurity()');
  // 新手引导与账号弹层曾是同一 z-index，引导在 DOM 中靠后会遮住账号安全。
  check('弹层不被新手引导遮挡', await page.eval(`(() => {
    const modal = document.getElementById('account-security-modal');
    const box = modal.querySelector('.account-security-box');
    const rect = box.getBoundingClientRect();
    const hit = document.elementFromPoint(
      rect.left + rect.width / 2, rect.top + Math.min(80, rect.height / 2));
    const onboarding = document.getElementById('onboard-overlay');
    // 打开账号弹层时应收起新手引导；两者不能同时覆盖登录页。
    return modal.contains(hit) && !onboarding.classList.contains('show');
  })()`));
  const mobile = await page.eval(`(() => {
    const modal = [...document.querySelectorAll('.account-security-modal')]
      .find(el => el.classList.contains('show'));
    const box = modal.querySelector('.account-security-box');
    const buttons = [...modal.querySelectorAll('button')];
    return {
      overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      boxRight: box.getBoundingClientRect().right,
      viewport: innerWidth,
      small: buttons.filter(b => b.getBoundingClientRect().height < 44).map(b => b.textContent)
    };
  })()`);
  check('390px 页面无横向溢出', !mobile.overflow, JSON.stringify(mobile));
  check('390px 弹层不出视口', mobile.boxRight <= mobile.viewport + 0.5, JSON.stringify(mobile));
  check('手机操作按钮均达到 44px', mobile.small.length === 0, JSON.stringify(mobile.small));
  await page.screenshot('account-security-mobile');

  check('页面无脚本异常或控制台错误', page.errors.length === 0, page.errors.join(' | '));
} catch (error) {
  failed++;
  console.error('[ui-account-security] FAIL 异常 :: ' + (error && error.message));
} finally {
  if (page && page.sessionId) {
    try { await page.send('Target.closeTarget', { targetId }, false); } catch (_) {}
  }
  if (browser) browser.ws.close();
}

console.log(failed === 0
  ? '[ui-account-security] PASS (' + passed + '/' + passed + ')'
  : '[ui-account-security] FAIL (' + failed + ' failed, ' + passed + ' passed)');
process.exitCode = failed === 0 ? 0 : 1;
