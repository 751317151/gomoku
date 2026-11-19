// 组队冒烟：邀请 → 接受 → 队长一次开局（全队进同一私房）→ 座位带队伍标识。
// 失败路径也验证「谁都没进房」，这是「各自 JOIN 再补通知」最容易破的地方。
const base = process.env.API_BASE || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
let passed = 0;
let failed = 0;

function check(name, condition, detail) {
  if (condition) { passed++; console.log('[party] PASS ' + name); }
  else { failed++; console.error('[party] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}

async function request(path, options = {}) {
  const response = await fetch(base + path, options);
  let body = null;
  try { body = await response.json(); } catch (_) {}
  return { status: response.status, body };
}

const auth = token => ({ headers: { Authorization: 'Bearer ' + token } });
const post = (body, token) => ({
  method: 'POST',
  headers: Object.assign({ 'Content-Type': 'application/json' }, { Authorization: 'Bearer ' + token }),
  body: JSON.stringify(body)
});

async function newGuest() {
  const guest = await request('/api/guest', { method: 'POST' });
  return { token: guest.body.token, id: guest.body.id };
}

/** 大厅连接：绑定身份，收集 GAME_START / 队伍事件 */
function hall(name, token) {
  return new Promise(resolve => {
    const ws = new WebSocket(WS_URL);
    const st = { ws, name, events: [], errs: [], bound: false, roomId: null, playing: false, roomData: null };
    ws.onopen = () => {
      ws.send(JSON.stringify({ type: 'HALL_BIND', playerName: name, guestToken: token }));
      ws.send(JSON.stringify({ type: 'GET_ROOMS' }));
    };
    ws.onmessage = ev => {
      let m; try { m = JSON.parse(ev.data.toString()); } catch { return; }
      if (m.type === 'FRIEND_EVENT') {
        let p = null; try { p = JSON.parse(m.data); } catch {}
        if (p) st.events.push(p);
      }
      if (m.type === 'ROOM_LIST' && !st.bound) { st.bound = true; resolve(st); }
      if (m.type === 'GAME_START') {
        st.roomId = m.roomId;
        st.playing = true;
        try { st.roomData = JSON.parse(m.data); } catch {}
      }
      if (m.type === 'ERROR') st.errs.push(m.message);
    };
    setTimeout(() => { if (!st.bound) resolve(st); }, 15000);
  });
}

const sleep = ms => new Promise(r => setTimeout(r, ms));
const close = s => { try { s.ws.close(); } catch (_) {} };

async function waitFor(pred, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const hit = pred();
    if (hit) return hit;
    await sleep(100);
  }
  return null;
}

async function befriend(a, b) {
  await request('/api/friends', auth(a.token));
  const code = (await request('/api/friends', auth(a.token))).body.me.code;
  await request('/api/friends/requests', post({ target: code }, b.token));
  const incoming = (await request('/api/friends', auth(a.token))).body.incoming || [];
  const from = incoming.find(x => x.id === b.id) || incoming[0];
  if (from) await request('/api/friends/requests/' + from.id + '/accept', { method: 'POST', ...auth(a.token) });
}

try {
  const a = await newGuest();
  const b = await newGuest();
  const c = await newGuest();

  check('未带凭证访问队伍接口 401', (await request('/api/party')).status === 401);

  const stranger = await request('/api/party/invite', post({ target: c.id }, a.token));
  check('只能邀请好友组队', stranger.status === 400 && stranger.body.code === 'INVALID',
    JSON.stringify(stranger.body));

  await befriend(a, b);

  // 双方必须在线：离线好友无法组队（避免建出永远凑不齐的队伍）
  const offlineInvite = await request('/api/party/invite', post({ target: b.id }, a.token));
  check('好友离线时不能邀请组队', offlineInvite.status === 409, JSON.stringify(offlineInvite.body));

  const hallA = await hall('队长甲', a.token);
  const hallB = await hall('队员乙', b.token);
  check('双方已连接大厅', hallA.bound && hallB.bound, JSON.stringify([hallA.errs, hallB.errs]));

  const invite = await request('/api/party/invite', post({ target: b.id }, a.token));
  check('邀请在线好友成功', invite.status === 200, JSON.stringify(invite.body));
  const party = invite.body.party;
  check('邀请人自动成为队长', !!party && party.isLeader === true && party.members.length === 1,
    JSON.stringify(party));
  check('队伍短码格式 PTY-XXXX', /^PTY-[A-Z2-9]{4}$/.test(party.id), party && party.id);

  const inviteEvent = await waitFor(() => hallB.events.find(e => e.kind === 'party_invite'));
  check('队员实时收到组队邀请', !!inviteEvent && inviteEvent.partyId === party.id,
    JSON.stringify(hallB.events));

  const bView = await request('/api/party', auth(b.token));
  const inviteForB = (bView.body.invites || [])[0];
  check('邀请列表带邀请人与人数', !!inviteForB && !!inviteForB.fromName && inviteForB.memberCount === 1,
    JSON.stringify(inviteForB));

  const accept = await request('/api/party/invites/' + inviteForB.id + '/accept',
    { method: 'POST', ...auth(b.token) });
  check('接受邀请成功', accept.status === 200, JSON.stringify(accept.body));
  check('队伍变为 2 人且队员视角正确',
    accept.body.party && accept.body.party.members.length === 2 && accept.body.party.isLeader === false,
    JSON.stringify(accept.body.party));

  await befriend(b, c);
  const hallC = await hall('队员丙', c.token);
  const memberInvite = await request('/api/party/invite', post({ target: c.id }, b.token));
  check('队员不能邀请他人', memberInvite.status === 403 && memberInvite.body.code === 'UNAUTHORIZED',
    JSON.stringify(memberInvite.body));

  // 队长一次开局：全队同时进同一私房，客户端不需要各自 JOIN
  const start = await request('/api/party/start', post({ gameType: 'GOMOKU' }, a.token));
  check('队长开局成功', start.status === 200 && !!start.body.roomId, JSON.stringify(start.body));
  const roomId = start.body && start.body.roomId;

  const bothIn = await waitFor(() => hallA.playing && hallB.playing, 6000);
  check('全队同时进入对局', !!bothIn,
    JSON.stringify({ a: hallA.roomId, b: hallB.roomId, errs: hallB.errs }));
  check('全队在同一间房', hallA.roomId === roomId && hallB.roomId === roomId,
    JSON.stringify({ want: roomId, a: hallA.roomId, b: hallB.roomId }));

  const seats = (hallB.roomData && hallB.roomData.players) || [];
  check('开局数据含两个座位', seats.length === 2, JSON.stringify(seats));
  check('两名队员座位都带同一队伍标识',
    seats.length === 2 && seats.every(x => x.partyId === party.id),
    JSON.stringify(seats.map(x => ({ name: x.name, partyId: x.partyId }))));

  const rooms = await (await fetch(base + '/api/rooms')).json();
  check('组队房间不出现在公共列表',
    !(Array.isArray(rooms) ? rooms : []).some(r => r.roomId === roomId),
    JSON.stringify((Array.isArray(rooms) ? rooms : []).map(r => r.roomId)));

  // 人数与玩法不符：拒绝，且谁都不进房
  close(hallA);
  close(hallB);
  await sleep(400);
  const hallA2 = await hall('队长甲', a.token);
  const hallB2 = await hall('队员乙', b.token);
  // 断线重连会恢复原座位，第二轮开始前必须先显式回大厅
  hallA2.ws.send(JSON.stringify({ type: 'LEAVE' }));
  hallB2.ws.send(JSON.stringify({ type: 'LEAVE' }));
  await sleep(500);
  const d = await newGuest();
  const hallD = await hall('队员丁', d.token);
  await befriend(a, d);
  const inviteD = await request('/api/party/invite', post({ target: d.id }, a.token));
  check('队伍可继续邀请第三名好友', inviteD.status === 200, JSON.stringify(inviteD.body));
  await request('/api/party/invites/' + inviteD.body.inviteId + '/accept',
    { method: 'POST', ...auth(d.token) });

  const tooMany = await request('/api/party/start', post({ gameType: 'GOMOKU' }, a.token));
  check('人数超过玩法上限时拒绝开局',
    tooMany.status === 400 && /3 人/.test(tooMany.body.message || ''),
    JSON.stringify(tooMany.body));
  await sleep(600);
  check('拒绝后没有人被塞进房间',
    !hallA2.playing && !hallB2.playing && !hallD.playing,
    JSON.stringify({ a: hallA2.playing, b: hallB2.playing, d: hallD.playing }));

  // 3 人打 4 人玩法：容量按队伍人数开，直接开局
  const threeWay = await request('/api/party/start', post({ gameType: 'DICE_RACE' }, a.token));
  check('3 人队伍可直接开多人玩法', threeWay.status === 200, JSON.stringify(threeWay.body));
  const allThree = await waitFor(() => hallA2.playing && hallB2.playing && hallD.playing, 6000);
  check('3 人同时进入同一局', !!allThree,
    JSON.stringify({ a: hallA2.roomId, b: hallB2.roomId, d: hallD.roomId }));
  check('房间容量等于队伍人数', !!allThree
    && (hallA2.roomData || {}).targetPlayers === 3,
    JSON.stringify(hallA2.roomData && hallA2.roomData.targetPlayers));

  // 退出：队长退出后移交，最后一人退出解散
  const leaveB = await request('/api/party/members', { method: 'DELETE', ...auth(b.token) });
  check('队员退出成功', leaveB.status === 200, JSON.stringify(leaveB.body));
  const leaveD = await request('/api/party/members', { method: 'DELETE', ...auth(d.token) });
  check('第三名退出后队伍仍存在', leaveD.status === 200, JSON.stringify(leaveD.body));
  const leaveA = await request('/api/party/members', { method: 'DELETE', ...auth(a.token) });
  check('最后一人退出后队伍解散', leaveA.status === 200 && leaveA.body.party === null,
    JSON.stringify(leaveA.body));

  [hallC, hallA2, hallB2, hallD].forEach(close);
} catch (error) {
  failed++;
  console.error('[party] FAIL 异常 :: ' + (error && error.message));
}

console.log(failed === 0
  ? '[party] PASS (' + passed + '/' + passed + ')'
  : '[party] FAIL (' + failed + ' failed, ' + passed + ' passed)');
process.exit(failed === 0 ? 0 : 1);
