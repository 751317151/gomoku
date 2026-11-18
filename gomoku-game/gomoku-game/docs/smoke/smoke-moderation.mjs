// 运营治理冒烟：举报 → 管理处置 → 禁言/封禁生效的真实链路。
// 前置：服务端需配置 ADMIN_TOKEN（本脚本默认用 smoke-admin-token，启动服务时要一致）。
// 默认 http://127.0.0.1:19000 / ws://127.0.0.1:19001，可用 API_BASE / WS_URL 覆盖。
const base = process.env.API_BASE || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || 'smoke-admin-token';
let passed = 0;
let failed = 0;

function check(name, condition, detail) {
  if (condition) { passed++; console.log('[moderation] PASS ' + name); }
  else { failed++; console.error('[moderation] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}

async function request(path, options = {}) {
  const response = await fetch(base + path, options);
  let body = null;
  try { body = await response.json(); } catch (_) {}
  return { status: response.status, body };
}

const auth = token => ({ headers: token ? { Authorization: 'Bearer ' + token } : {} });
const adminHeaders = extra => Object.assign({ 'X-Admin-Token': ADMIN_TOKEN }, extra || {});
const json = (body, headers) => ({
  method: 'POST',
  headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {}),
  body: JSON.stringify(body)
});

async function newGuest() {
  const guest = await request('/api/guest', { method: 'POST' });
  return { token: guest.body.token, id: guest.body.id };
}

/** WS 连接并 JOIN 双人局；返回 { ws, roomId, playerId, errors } */
function wsJoin(name, token, gameType = 'GOMOKU') {
  return new Promise(resolve => {
    const ws = new WebSocket(WS_URL);
    const st = { ws, name, roomId: null, playerId: null, state: null, errors: [], chats: [] };
    ws.onopen = () => ws.send(JSON.stringify({ type: 'JOIN', playerName: name, gameType, guestToken: token }));
    ws.onmessage = ev => {
      let m; try { m = JSON.parse(ev.data.toString()); } catch { return; }
      if (m.type === 'WAITING' || m.type === 'GAME_START') {
        st.roomId = m.roomId; st.state = m.type;
        if (m.playerId) st.playerId = m.playerId;
        if (!st.playerId) st.playerId = m.playerId || st.playerId;
      }
      if (m.type === 'ROOM_INFO' && m.playerId) st.playerId = m.playerId;
      if (m.type === 'GAME_CHAT') st.chats.push(m.message);
      if (m.type === 'ERROR') st.errors.push(m.message);
    };
    const done = () => { clearInterval(poll); resolve(st); };
    const poll = setInterval(() => {
      if (st.state || st.errors.length) done();
    }, 100);
    setTimeout(() => { clearInterval(poll); resolve(st); }, 15000);
  });
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

try {
  // 0. 管理接口在未带令牌时拒绝
  const noToken = await request('/api/admin/moderation');
  check('无令牌访问管理接口被拒', noToken.status === 403 || noToken.status === 503,
    'status=' + noToken.status);

  // 1. 两个真实玩家开一局
  const a = await newGuest();
  const b = await newGuest();
  const wsA = await wsJoin('治理甲', a.token);
  check('甲建房成功', !!wsA.roomId, JSON.stringify(wsA.errors));
  const wsB = await wsJoin('治理乙', b.token);
  check('乙按匹配开局', wsB.roomId === wsA.roomId, JSON.stringify({ a: wsA.roomId, b: wsB.roomId }));
  await sleep(300);

  // 2. 甲举报乙（原因：辱骂）
  wsA.ws.send(JSON.stringify({ type: 'REPORT', data: wsB.playerId, action: 0, message: '一直辱骂' }));
  await sleep(800);
  check('举报后收到提交确认', wsA.chats.some(c => c.includes('举报已提交')), JSON.stringify(wsA.chats));

  // 3. 重复举报被去重
  wsA.ws.send(JSON.stringify({ type: 'REPORT', data: wsB.playerId, action: 0, message: '再举报一次' }));
  await sleep(700);
  check('重复举报被去重提示', wsA.errors.some(e => e.includes('请勿重复提交')), JSON.stringify(wsA.errors));

  // 4. 举报自己 / 举报电脑 / 举报不存在的人
  wsA.ws.send(JSON.stringify({ type: 'REPORT', data: wsA.playerId, action: 0, message: '' }));
  await sleep(600);
  check('举报自己被拒', wsA.errors.some(e => e.includes('不能举报自己')), JSON.stringify(wsA.errors.slice(-3)));

  // 5. 管理端能看到举报，含取证字段
  const overview = await request('/api/admin/moderation?onlyPending=true', { headers: adminHeaders() });
  check('管理端读取举报队列', overview.status === 200 && Array.isArray(overview.body.reports),
    JSON.stringify(overview.body));
  const report = (overview.body.reports || []).find(r => r.targetName === '治理乙');
  check('举报带被举报人/原因/房间证据', !!report && report.reason === 'abuse'
    && report.gameType === 'GOMOKU' && !!report.roomId && !!report.targetId, JSON.stringify(report));
  check('处置时长可选值下发', (overview.body.durations || []).includes('24h')
    && (overview.body.durations || []).includes('forever'), JSON.stringify(overview.body.durations));

  // 6. 先禁言乙 → 乙发不出聊天、但仍能继续对局
  const mute = await request('/api/admin/moderation/reports/' + report.id + '/sanction',
    json({ action: 'mute', duration: '24h', reason: '辱骂' }, adminHeaders()));
  check('管理员禁言成功', mute.status === 200 && mute.body.action === 'mute', JSON.stringify(mute.body));
  await sleep(200);
  const statusB = await request('/api/moderation/me', auth(b.token));
  check('乙侧看到自己被禁言', statusB.body.muted === true && statusB.body.banned === false,
    JSON.stringify(statusB.body));
  wsB.ws.send(JSON.stringify({ type: 'CHAT', message: '我被禁言了吗' }));
  await sleep(700);
  check('禁言后聊天被拦截', wsB.errors.some(e => e.includes('已被禁言')), JSON.stringify(wsB.errors.slice(-3)));

  // 7. 升级为封禁 → 乙新连接被拒绝进入
  const ban = await request('/api/admin/moderation/sanctions',
    json({ identityId: b.id, action: 'ban', duration: '7d', reason: '屡次辱骂' }, adminHeaders()));
  check('管理员封禁成功', ban.status === 200 && ban.body.action === 'ban', JSON.stringify(ban.body));
  const statusB2 = await request('/api/moderation/me', auth(b.token));
  check('乙侧看到自己被封禁', statusB2.body.banned === true, JSON.stringify(statusB2.body));
  const bannedWs = await wsJoin('治理乙', b.token);
  check('封禁身份无法重新进入大厅/房间', bannedWs.errors.some(e => e.includes('已被封禁')),
    JSON.stringify(bannedWs.errors));

  // 8. 撤销处置 → 恢复
  const lift = await request('/api/admin/moderation/sanctions/' + encodeURIComponent(b.id),
    { method: 'DELETE', headers: adminHeaders() });
  check('撤销处置成功', lift.status === 200, JSON.stringify(lift.body));
  const statusB3 = await request('/api/moderation/me', auth(b.token));
  check('撤销后恢复自由', statusB3.body.banned === false && statusB3.body.muted === false,
    JSON.stringify(statusB3.body));

  // 9. 已处置的举报从待处理列表消失
  const pendingAfter = await request('/api/admin/moderation?onlyPending=true', { headers: adminHeaders() });
  check('已处置举报不再待处理',
    !(pendingAfter.body.reports || []).some(r => r.id === report.id), JSON.stringify(pendingAfter.body.reports));

  // 10. 玩家能查自己的举报记录
  const mine = await request('/api/moderation/reports/mine', auth(a.token));
  check('玩家可查自己的举报记录', mine.status === 200 && (mine.body.reports || []).some(r => r.id === report.id),
    JSON.stringify(mine.body));
  check('玩家视图不泄露被举报人身份 ID',
    (mine.body.reports || []).every(r => r.targetId === undefined), JSON.stringify(mine.body.reports));

  wsA.ws.close();
  try { wsB.ws.close(); } catch (_) {}
  try { bannedWs.ws.close(); } catch (_) {}
} catch (error) {
  failed++;
  console.error('[moderation] FAIL 异常 :: ' + (error && error.message));
}

console.log(failed === 0
  ? '[moderation] PASS (' + passed + '/' + passed + ')'
  : '[moderation] FAIL (' + failed + ' failed, ' + passed + ' passed)');
process.exitCode = failed === 0 ? 0 : 1;
