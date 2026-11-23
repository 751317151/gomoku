// 账号会话安全真实链路：改密轮换令牌、撤销其他设备、当前退出、跨 HTTP/WS 失效。
// 默认 http://127.0.0.1:19000；可覆盖 API_BASE / WS_URL。
const API = process.env.API_BASE || 'http://127.0.0.1:19000';
const WS = process.env.WS_URL || 'ws://127.0.0.1:19001';
const suffix = Date.now().toString(36);
const username = ('secure' + suffix).slice(0, 20);
const oldPassword = 'SmokeOld!123456';
const newPassword = 'SmokeNew!654321';
let passed = 0;
let failed = 0;

function check(name, condition, detail) {
  if (condition) {
    passed++;
    console.log('[account-security] PASS ' + name);
  } else {
    failed++;
    console.error('[account-security] FAIL ' + name + (detail ? ' :: ' + detail : ''));
  }
}

async function request(path, { method = 'GET', token, body } = {}) {
  const headers = {};
  if (token) headers.Authorization = 'Bearer ' + token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(API + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(10000)
  });
  let data = null;
  try { data = await response.json(); } catch (_) {}
  return { status: response.status, data };
}

async function wsJoin(token) {
  const socket = new WebSocket(WS);
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('WS 握手超时')), 8000);
    socket.onopen = () => { clearTimeout(timer); resolve(); };
    socket.onerror = () => { clearTimeout(timer); reject(new Error('WS 握手失败')); };
  });
  socket.send(JSON.stringify({
    type: 'JOIN',
    playerName: 'secretsmoke',
    gameType: 'GOMOKU',
    guestToken: token
  }));
  const result = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('WS JOIN 超时')), 8000);
    socket.onmessage = event => {
      clearTimeout(timer);
      resolve(JSON.parse(event.data));
    };
    socket.onerror = () => { clearTimeout(timer); reject(new Error('WS 消息失败')); };
  });
  socket.close();
  return result;
}

try {
  const first = await request('/api/account/register', {
    method: 'POST',
    body: { username, password: oldPassword, nickname: '安全冒烟' }
  });
  check('注册使用 PBKDF2-HMAC-SHA256', first.status === 200
    && first.data.passwordScheme === 'pbkdf2-sha256', JSON.stringify(first.data));
  const tokenA = first.data.token;
  const recoveryCode = first.data.recoveryCode;
  check('注册下发一次性恢复码', typeof recoveryCode === 'string'
    && /^[A-HJ-NP-Z2-9]{20}$/.test(recoveryCode), String(recoveryCode));

  const second = await request('/api/account/login', {
    method: 'POST',
    body: { username, password: oldPassword }
  });
  check('第二台设备登录签发独立会话', second.status === 200
    && second.data.token !== tokenA
    && second.data.id === first.data.id, JSON.stringify(second.data));
  check('登录不会重复下发恢复码', second.data.recoveryCode === undefined);
  const tokenB = second.data.token;

  const meA = await request('/api/account/me', { token: tokenA });
  const meB = await request('/api/account/me', { token: tokenB });
  check('两个会话都可以读取账号', meA.status === 200 && meB.status === 200);

  const revokedOthers = await request('/api/account/logout-others', { method: 'POST', token: tokenA });
  check('当前设备保留并撤销其他会话', revokedOthers.status === 200
    && revokedOthers.data.revoked === '1', JSON.stringify(revokedOthers.data));
  check('被撤销设备 REST 立即失效',
    (await request('/api/account/me', { token: tokenB })).status === 401);

  let revokedWs = null;
  try {
    revokedWs = await wsJoin(tokenB);
  } catch (error) {
    revokedWs = { error: error && error.message };
  }
  check('被撤销设备 WS 身份也失效', /登录|身份|token|凭证|无效|会话/i.test(
    String(revokedWs.reason || revokedWs.message || revokedWs.error || '')), JSON.stringify(revokedWs));

  const tokenC = (await request('/api/account/login', {
    method: 'POST', body: { username, password: oldPassword }
  })).data.token;
  const rotated = await request('/api/account/password', {
    method: 'POST',
    token: tokenA,
    body: { oldPassword, newPassword }
  });
  check('改密返回当前设备新凭证', rotated.status === 200
    && !!rotated.data.token
    && rotated.data.token !== tokenA, JSON.stringify(rotated.data));
  check('改密后旧会话失效',
    (await request('/api/account/me', { token: tokenA })).status === 401);
  check('改密后其他设备会话失效',
    (await request('/api/account/me', { token: tokenC })).status === 401);
  check('新凭证可继续使用',
    (await request('/api/account/me', { token: rotated.data.token })).status === 200);

  const oldLogin = await request('/api/account/login', {
    method: 'POST', body: { username, password: oldPassword }
  });
  const newLogin = await request('/api/account/login', {
    method: 'POST', body: { username, password: newPassword }
  });
  check('改密后旧密码失效、新密码有效',
    oldLogin.status === 401 && newLogin.status === 200);

  const logout = await request('/api/account/logout', {
    method: 'POST', token: rotated.data.token
  });
  check('主动退出成功', logout.status === 200);
  check('主动退出后当前凭证失效',
    (await request('/api/account/me', { token: rotated.data.token })).status === 401);
  check('主动退出不签发下一张凭证', logout.data && !logout.data.token);

  const loggedWs = await wsJoin(newLogin.data.token);
  check('未撤销账号 WS 仍能以账号身份入房',
    loggedWs.type === 'WAITING' || loggedWs.type === 'ROOM_INFO', JSON.stringify(loggedWs));

  // 恢复码链路：旧码在改密后已轮换失效；新码可重置密码并撤销全部会话。
  const staleRecovery = await request('/api/account/recover-password', {
    method: 'POST',
    body: { username, recoveryCode, newPassword: 'RecoveredPass789' }
  });
  check('改密后旧恢复码失效', staleRecovery.status === 401, JSON.stringify(staleRecovery.data));
  const currentRecoveryCode = rotated.data.recoveryCode;
  check('改密后换发新恢复码', typeof currentRecoveryCode === 'string'
    && currentRecoveryCode !== recoveryCode);

  // 再开一个会话，确认恢复码重置会把它一并撤销
  const preRecovery = await request('/api/account/login', {
    method: 'POST', body: { username, password: newPassword }
  });
  const recovered = await request('/api/account/recover-password', {
    method: 'POST',
    body: { username, recoveryCode: currentRecoveryCode, newPassword: 'RecoveredPass789' }
  });
  check('恢复码可重置忘记的密码', recovered.status === 200
    && typeof recovered.data.token === 'string'
    && recovered.data.id === first.data.id, JSON.stringify(recovered.data));
  check('恢复码使用后换发新码', typeof recovered.data.recoveryCode === 'string'
    && recovered.data.recoveryCode !== currentRecoveryCode);
  check('恢复码重置撤销全部旧会话',
    (await request('/api/account/me', { token: preRecovery.data.token })).status === 401);
  check('恢复码重置后新会话有效',
    (await request('/api/account/me', { token: recovered.data.token })).status === 200);
  check('恢复码只能使用一次',
    (await request('/api/account/recover-password', {
      method: 'POST',
      body: { username, recoveryCode: currentRecoveryCode, newPassword: 'AnotherPass456' }
    })).status === 401);
  const recoveredLogin = await request('/api/account/login', {
    method: 'POST', body: { username, password: 'RecoveredPass789' }
  });
  check('恢复后的新密码可登录且身份不变',
    recoveredLogin.status === 200 && recoveredLogin.data.id === first.data.id);

  const rotatedCodeResponse = await request('/api/account/rotate-recovery-code', {
    method: 'POST', token: recoveredLogin.data.token, body: { password: 'wrongpass' }
  });
  check('轮换恢复码必须验证当前密码', rotatedCodeResponse.status === 401);
  const rotatedCode = await request('/api/account/rotate-recovery-code', {
    method: 'POST', token: recoveredLogin.data.token, body: { password: 'RecoveredPass789' }
  });
  check('已登录可主动轮换恢复码', rotatedCode.status === 200
    && typeof rotatedCode.data.recoveryCode === 'string'
    && rotatedCode.data.recoveryCode !== recovered.data.recoveryCode);
} catch (error) {
  failed++;
  console.error('[account-security] FAIL 异常 :: ' + (error && error.message));
}

console.log(failed === 0
  ? '[account-security] PASS (' + passed + '/' + passed + ')'
  : '[account-security] FAIL (' + failed + ' failed, ' + passed + ' passed)');
process.exitCode = failed === 0 ? 0 : 1;
