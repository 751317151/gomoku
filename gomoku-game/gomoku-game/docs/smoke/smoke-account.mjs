// 账号体系 REST 冒烟：游客升级、注册/登录、密码错误、改密、当前账号、重名冲突。
// 通过 HTTP 真实链路验证，默认 http://127.0.0.1:19000，可用 API_BASE 覆盖。
const base = process.env.API_BASE || 'http://127.0.0.1:19000';
const suffix = Date.now().toString(36);
const username = 'smoke' + suffix;
const password = 'secret123';
let passed = 0;
let failed = 0;

function check(name, condition, detail) {
  if (condition) { passed++; console.log('[account] PASS ' + name); }
  else { failed++; console.error('[account] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}

async function request(path, options = {}) {
  const response = await fetch(base + path, options);
  let body = null;
  try { body = await response.json(); } catch (_) {}
  return { status: response.status, body };
}

function json(body, token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = 'Bearer ' + token;
  return { method: 'POST', headers, body: JSON.stringify(body) };
}

function auth(token) { return { headers: token ? { Authorization: 'Bearer ' + token } : {} }; }

try {
  // 1. 先拿一个游客身份
  const guest = await request('/api/guest', { method: 'POST' });
  check('游客身份签发', guest.status === 200 && !!guest.body.token, JSON.stringify(guest.body));
  const guestToken = guest.body.token;

  // 2. 用游客 token 注册 → 身份不变（战绩无损升级）
  const registered = await request('/api/account/register',
    json({ username, password, nickname: '冒烟玩家' }, guestToken));
  check('注册成功', registered.status === 200 && !!registered.body.token, JSON.stringify(registered.body));
  const accountToken = registered.body.token;
  check('游客升级沿用同一身份', registered.body.id === guest.body.id,
    registered.body.id + ' vs ' + guest.body.id);

  // 3. 当前账号可反查
  const me = await request('/api/account/me', auth(accountToken));
  check('当前账号反查', me.status === 200 && me.body.username === username, JSON.stringify(me.body));

  // 4. 原游客凭证现在也属于账号
  const meByGuest = await request('/api/account/me', auth(guestToken));
  check('升级后旧凭证仍识别为账号', meByGuest.status === 200, JSON.stringify(meByGuest.body));

  // 5. 重名冲突
  const dup = await request('/api/account/register', json({ username: username.toUpperCase(), password }, guestToken));
  check('重名注册被拒(409)', dup.status === 409, 'status=' + dup.status);

  // 6. 错误密码
  const wrong = await request('/api/account/login', json({ username, password: 'wrongpass' }));
  check('错误密码被拒(401)', wrong.status === 401, 'status=' + wrong.status);

  // 7. 正确登录回到同一身份
  const logged = await request('/api/account/login', json({ username, password }));
  check('登录成功且身份一致', logged.status === 200 && logged.body.id === registered.body.id, JSON.stringify(logged.body));
  check('登录签发新凭证', logged.body.token !== accountToken);

  // 8. 改密后旧密码失效、新密码可用
  const changed = await request('/api/account/password',
    json({ oldPassword: password, newPassword: 'newsecret456' }, logged.body.token));
  check('改密成功', changed.status === 200, JSON.stringify(changed.body));
  const oldLogin = await request('/api/account/login', json({ username, password }));
  check('改密后旧密码失效', oldLogin.status === 401, 'status=' + oldLogin.status);
  const newLogin = await request('/api/account/login', json({ username, password: 'newsecret456' }));
  check('改密后新密码可用且身份不变', newLogin.status === 200 && newLogin.body.id === registered.body.id);

  // 9. 未登录账号时 me 返回 401
  const freshGuest = await request('/api/guest', { method: 'POST' });
  const anonMe = await request('/api/account/me', auth(freshGuest.body.token));
  check('游客访问账号接口返回 401', anonMe.status === 401, 'status=' + anonMe.status);
} catch (error) {
  failed++;
  console.error('[account] FAIL 异常 :: ' + (error && error.message));
}

console.log(failed === 0
  ? '[account] PASS (' + passed + '/' + passed + ')'
  : '[account] FAIL (' + failed + ' failed, ' + passed + ' passed)');
process.exitCode = failed === 0 ? 0 : 1;
