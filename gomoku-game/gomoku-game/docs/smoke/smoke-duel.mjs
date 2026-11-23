// 好友约战协议冒烟：邀请不建房；同意后双方一次性进入同一双人私房。
const base = process.env.API_BASE || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
let passed = 0;
let failed = 0;
const sockets = new Set();

function check(name, condition, detail) {
  if (condition) { passed++; console.log('[duel] PASS ' + name); }
  else { failed++; console.error('[duel] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}

async function request(path, options = {}) {
  const response = await fetch(base + path, { ...options, signal: AbortSignal.timeout(10000) });
  let body = null;
  try { body = await response.json(); } catch (_) {}
  return { status: response.status, body };
}

const auth = token => ({ headers: { Authorization: 'Bearer ' + token } });
const post = (body, token) => ({
  method: 'POST',
  headers: Object.assign({ 'Content-Type': 'application/json' }, token ? { Authorization: 'Bearer ' + token } : {}),
  body: JSON.stringify(body)
});
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function waitFor(predicate, timeoutMs = 7000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const result = predicate();
    if (result) return result;
    await sleep(100);
  }
  return null;
}

async function newGuest() {
  const response = await request('/api/guest', { method: 'POST' });
  return { token: response.body.token, id: response.body.id };
}

function connectHall(name, token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL);
    sockets.add(ws);
    const state = { ws, name, token, events: [], errors: [], room: null };
    const timeout = setTimeout(() => { ws.close(); reject(new Error('大厅连接超时: ' + name)); }, 12000);
    ws.onerror = () => { clearTimeout(timeout); reject(new Error('WebSocket 连接失败: ' + name)); };
    ws.onopen = () => {
      ws.send(JSON.stringify({ type: 'HALL_BIND', playerName: name, guestToken: token }));
      ws.send(JSON.stringify({ type: 'GET_ROOMS' }));
    };
    ws.onmessage = event => {
      let message;
      try { message = JSON.parse(event.data.toString()); } catch (_) { return; }
      if (message.type === 'FRIEND_EVENT') {
        try { state.events.push(JSON.parse(message.data)); } catch (_) {}
      }
      if (message.type === 'GAME_START') {
        let data = {};
        try { data = JSON.parse(message.data || '{}'); } catch (_) {}
        state.room = { roomId: message.roomId, playerId: message.playerId, stone: message.stone, gameType: message.gameType, data };
      }
      if (message.type === 'ERROR') state.errors.push(message.message);
      if (message.type === 'ROOM_LIST') {
        clearTimeout(timeout);
        resolve(state);
      }
    };
  });
}

async function befriend(a, b) {
  await request('/api/friends', auth(a.token));
  const code = (await request('/api/friends', auth(a.token))).body.me.code;
  await request('/api/friends/requests', post({ target: code }, b.token));
  const incoming = (await request('/api/friends', auth(a.token))).body.incoming || [];
  const requestFromB = incoming.find(item => item.id === b.id);
  if (!requestFromB) throw new Error('好友申请未出现在收件箱');
  const accepted = await request('/api/friends/requests/' + requestFromB.id + '/accept', { method: 'POST', ...auth(a.token) });
  if (accepted.status !== 200) throw new Error('接受好友申请失败: ' + JSON.stringify(accepted.body));
}

try {
  const a = await newGuest();
  const b = await newGuest();
  const stranger = await newGuest();
  check('创建三份独立游客身份', !!a.token && !!b.token && !!stranger.token && new Set([a.id, b.id, stranger.id]).size === 3);
  check('匿名读取约战列表被拒绝', (await request('/api/duels')).status === 401);
  check('伪造游客令牌被拒绝', (await request('/api/duels', auth('invalid-token'))).status === 401);

  await befriend(a, b);
  const strangerInvite = await request('/api/duels', post({ target: stranger.id, gameType: 'GOMOKU' }, a.token));
  check('不能约战非好友', strangerInvite.status === 400 && strangerInvite.body.code === 'INVALID', JSON.stringify(strangerInvite.body));
  const invalidType = await request('/api/duels', post({ target: b.id, gameType: 'NO_SUCH_GAME' }, a.token));
  check('拒绝不支持的玩法', invalidType.status === 400 && invalidType.body.code === 'INVALID', JSON.stringify(invalidType.body));
  const offlineTarget = await request('/api/duels', post({ target: b.id, gameType: 'GOMOKU' }, a.token));
  check('好友未连接时不创建邀请', offlineTarget.status === 409 && offlineTarget.body.code === 'CONFLICT', JSON.stringify(offlineTarget.body));

  const hallA = await connectHall('约战甲', a.token);
  const hallB = await connectHall('约战乙', b.token);
  check('双方已绑定大厅身份', hallA.ws.readyState === WebSocket.OPEN && hallB.ws.readyState === WebSocket.OPEN);
  const publicRoomsBefore = (await request('/api/rooms')).body || [];

  const created = await request('/api/duels', post({ target: b.id, gameType: 'GOMOKU' }, a.token));
  const invite = created.body && created.body.invite;
  check('好友约战邀请创建成功', created.status === 200 && !!invite, JSON.stringify(created.body));
  check('邀请含正确双方、玩法和两分钟期限', !!invite && invite.fromId === a.id && invite.toId === b.id
    && invite.gameType === 'GOMOKU' && invite.gameLabel === '五子棋'
    && invite.expiresAt - invite.createdAt === 120000, JSON.stringify(invite));
  check('发出邀请没有预先创建房间', JSON.stringify((await request('/api/rooms')).body || []) === JSON.stringify(publicRoomsBefore));

  const pushedInvite = await waitFor(() => hallB.events.find(event => event.kind === 'duel_invite' && event.id === a.id));
  check('接收人实时收到约战提醒', !!pushedInvite, JSON.stringify(hallB.events));
  const receivedList = await request('/api/duels', auth(b.token));
  const listedInvite = receivedList.body && receivedList.body.incoming && receivedList.body.incoming.find(item => item.id === invite.id);
  check('收件箱补拉包含邀请人及玩法', receivedList.status === 200 && !!listedInvite
    && listedInvite.fromName === '约战甲' && listedInvite.gameLabel === '五子棋', JSON.stringify(receivedList.body));
  const sentList = await request('/api/duels', auth(a.token));
  check('发件箱包含待处理邀请', sentList.status === 200 && sentList.body.outgoing.some(item => item.id === invite.id));
  const duplicate = await request('/api/duels', post({ target: b.id, gameType: 'UNO' }, a.token));
  check('双方同一时间不能重复约战', duplicate.status === 409 && duplicate.body.code === 'CONFLICT', JSON.stringify(duplicate.body));

  const unauthorizedAccept = await request('/api/duels/' + invite.id + '/accept', { method: 'POST', ...auth(a.token) });
  check('发起人不能代替好友接受', unauthorizedAccept.status === 403 && unauthorizedAccept.body.code === 'UNAUTHORIZED', JSON.stringify(unauthorizedAccept.body));
  check('越权接受没有消费邀请', (await request('/api/duels', auth(b.token))).body.incoming.some(item => item.id === invite.id));

  const accepted = await request('/api/duels/' + invite.id + '/accept', { method: 'POST', ...auth(b.token) });
  check('接受成功返回双人私房号', accepted.status === 200 && /^ROOM-/.test(accepted.body.roomId || '')
    && accepted.body.gameType === 'GOMOKU', JSON.stringify(accepted.body));
  const startA = await waitFor(() => hallA.room, 10000);
  const startB = await waitFor(() => hallB.room, 10000);
  check('双方收到 GAME_START 并进入同一房间', !!startA && !!startB && startA.roomId === startB.roomId
    && startA.roomId === accepted.body.roomId && startA.gameType === 'GOMOKU' && startB.gameType === 'GOMOKU',
    JSON.stringify({ startA, startB, response: accepted.body }));
  check('两端均收到完整双人席位数据', !!startA && !!startB
    && startA.data.players?.length === 2 && startB.data.players?.length === 2
    && startA.data.targetPlayers === 2 && startB.data.targetPlayers === 2
    && startA.stone !== startB.stone, JSON.stringify({ a: startA && startA.data, b: startB && startB.data }));
  check('约战私房没有出现在公开房间列表', !((await request('/api/rooms')).body || []).some(room => room.roomId === (accepted.body && accepted.body.roomId)));
  const settledA = await request('/api/duels', auth(a.token));
  const settledB = await request('/api/duels', auth(b.token));
  check('开局后双方待处理约战均清空', settledA.body.incoming.length === 0 && settledA.body.outgoing.length === 0
    && settledB.body.incoming.length === 0 && settledB.body.outgoing.length === 0);
  check('接受后不能再次开局', (await request('/api/duels/' + invite.id + '/accept', { method: 'POST', ...auth(b.token) })).status === 404);
  const startedEvent = await waitFor(() => hallA.events.find(event => event.kind === 'duel_started' && event.id === b.id));
  check('双方收到约战已开局事件', !!startedEvent, JSON.stringify(hallA.events));

  hallA.ws.send(JSON.stringify({ type: 'LEAVE' }));
  hallB.ws.send(JSON.stringify({ type: 'LEAVE' }));
  const lobbyDeadline = Date.now() + 7000;
  let bothInLobby = false;
  while (Date.now() < lobbyDeadline) {
    const friends = (await request('/api/friends', auth(a.token))).body.friends || [];
    if (friends.some(friend => friend.id === b.id && friend.online && !friend.roomId)) {
      bothInLobby = true; break;
    }
    await sleep(100);
  }
  check('离开对局后好友仍在线且恢复大厅状态', bothInLobby);

  const declined = (await request('/api/duels', post({ target: b.id, gameType: 'UNO' }, a.token))).body.invite;
  check('退出后可再次邀请且支持多人玩法的双人约战', !!declined && declined.gameType === 'UNO');
  const declineResult = await request('/api/duels/' + declined.id + '/decline', { method: 'POST', ...auth(b.token) });
  check('接收方可拒绝邀请', declineResult.status === 200);
  check('拒绝实时通知发起人', !!await waitFor(() => hallA.events.find(event => event.kind === 'duel_decline')));
  check('被拒邀请不能接受', (await request('/api/duels/' + declined.id + '/accept', { method: 'POST', ...auth(b.token) })).status === 404);

  const cancelled = (await request('/api/duels', post({ target: b.id, gameType: 'UNO' }, a.token))).body.invite;
  check('接收人不能冒充发起人撤回', (await request('/api/duels/' + cancelled.id, { method: 'DELETE', ...auth(b.token) })).status === 403);
  check('发起人可撤回邀请', (await request('/api/duels/' + cancelled.id, { method: 'DELETE', ...auth(a.token) })).status === 200);
  check('撤回实时通知接收人', !!await waitFor(() => hallB.events.find(event => event.kind === 'duel_cancel')));
  check('撤回后双方邀请为空', (await request('/api/duels', auth(a.token))).body.outgoing.length === 0
    && (await request('/api/duels', auth(b.token))).body.incoming.length === 0);

  const retriable = (await request('/api/duels', post({ target: b.id, gameType: 'UNO' }, a.token))).body.invite;
  hallB.ws.send(JSON.stringify({ type: 'JOIN', playerName: hallB.name, guestToken: b.token,
    gameType: 'GOMOKU', createRoom: true, privateRoom: true, playerCount: 2 }));
  let occupied = false;
  const busyDeadline = Date.now() + 7000;
  while (Date.now() < busyDeadline) {
    const friends = (await request('/api/friends', auth(a.token))).body.friends || [];
    if (friends.some(friend => friend.id === b.id && friend.roomId)) { occupied = true; break; }
    await sleep(100);
  }
  check('接收方在回应前可正常进入其他房间', occupied);
  const busy = await request('/api/duels/' + retriable.id + '/accept', { method: 'POST', ...auth(b.token) });
  check('回应时重验房间状态，不能从其他房间强拉人', busy.status === 409 && busy.body.code === 'CONFLICT');
  check('开局失败保留原邀请供重试', (await request('/api/duels', auth(b.token))).body.incoming.some(item => item.id === retriable.id));
  hallB.ws.send(JSON.stringify({ type: 'LEAVE' }));
  await sleep(150);
  hallA.room = null; hallB.room = null;
  const retry = await request('/api/duels/' + retriable.id + '/accept', { method: 'POST', ...auth(b.token) });
  check('返回大厅后可接受同一邀请', retry.status === 200, JSON.stringify(retry.body));
  const unoA = await waitFor(() => hallA.room);
  const unoB = await waitFor(() => hallB.room);
  check('多人玩法约战实际按双人容量开局', !!unoA && !!unoB && unoA.gameType === 'UNO'
    && unoA.roomId === unoB.roomId && unoA.data.targetPlayers === 2 && unoA.data.players.length === 2);
  check('两端协议未收到异常错误', hallA.errors.length === 0 && hallB.errors.length === 0,
    JSON.stringify({ a: hallA.errors, b: hallB.errors }));
} catch (error) {
  failed++;
  console.error('[duel] FAIL 冒烟异常 :: ' + (error && error.stack || error));
} finally {
  for (const ws of sockets) {
    if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'LEAVE' }));
    ws.close();
  }
}

console.log(failed === 0
  ? '[duel] PASS (' + passed + '/' + passed + ')'
  : '[duel] FAIL (' + failed + ' failed, ' + passed + ' passed)');
process.exitCode = failed === 0 ? 0 : 1;
