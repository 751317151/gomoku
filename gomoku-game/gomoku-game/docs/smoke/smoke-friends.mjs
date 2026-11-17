// 好友系统冒烟：好友码签发、按码/用户名申请、同意/拒绝/撤回/删除、错误码、未登录 401，
// 以及 WS 真实链路：好友在线状态、所在房间对好友可见（含私有房）与一键加入好友房间。
const base = process.env.API_BASE || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const suffix = Date.now().toString(36);
let passed = 0;
let failed = 0;

function check(name, condition, detail) {
  if (condition) { passed++; console.log('[friends] PASS ' + name); }
  else { failed++; console.error('[friends] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}

async function request(path, options = {}) {
  const response = await fetch(base + path, options);
  let body = null;
  try { body = await response.json(); } catch (_) {}
  return { status: response.status, body };
}

function auth(token) { return { headers: token ? { Authorization: 'Bearer ' + token } : {} }; }

function json(body, token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = 'Bearer ' + token;
  return { method: 'POST', headers, body: JSON.stringify(body) };
}

async function newGuest() {
  const guest = await request('/api/guest', { method: 'POST' });
  return { token: guest.body.token, id: guest.body.id };
}

/** WS 连接并 JOIN；token 为游客凭证（服务端按它绑定持久身份） */
function wsJoin(name, token, { create = false, roomId = null, gameType = 'GOMOKU' } = {}) {
  return new Promise((resolve) => {
    const ws = new WebSocket(WS_URL);
    const st = { ws, name, roomId: null, state: null, started: false, errs: [] };
    ws.onopen = () => {
      const join = { type: 'JOIN', playerName: name, gameType, guestToken: token };
      if (create) join.createRoom = true;
      if (roomId) join.roomId = roomId;
      ws.send(JSON.stringify(join));
    };
    ws.onmessage = (ev) => {
      let m; try { m = JSON.parse(ev.data.toString()); } catch { return; }
      if (m.type === 'WAITING') { st.roomId = m.roomId; st.state = 'WAITING'; }
      if (m.type === 'GAME_START') { st.roomId = m.roomId; st.state = 'PLAYING'; st.started = true; }
      if (m.type === 'ERROR') st.errs.push(m.message);
    };
    // 等服务端真的给出结果（WAITING / GAME_START / ERROR），不固定等待；
    // 冷启动 JVM 的首条 WS 握手可能远超 1.8s（见 smoke-room-invite.mjs 同款备注），放宽到 15s
    let done = false;
    const finish = () => { if (done) return; done = true; resolve(st); };
    const poll = setInterval(() => {
      if (st.state || st.errs.length) { clearInterval(poll); finish(); }
    }, 100);
    setTimeout(() => { clearInterval(poll); finish(); }, 15000);
  });
}

/**
 * 大厅连接：HALL_BIND 绑定身份但不进房（好友实时推送的接收端形态）。
 * 返回 st（含 events 数组收集 FRIEND_EVENT），等待服务端确认（收到 ROOM_LIST 即绑定成功）。
 */
function hallClient(name, token) {
  return new Promise((resolve) => {
    const ws = new WebSocket(WS_URL);
    const st = { ws, name, events: [], dms: [], errs: [], bound: false };
    ws.onopen = () => {
      // 绑定后立即 GET_ROOMS：服务端直接回一条 ROOM_LIST 作为「绑定成功」的确认信号
      ws.send(JSON.stringify({ type: 'HALL_BIND', playerName: name, guestToken: token }));
      ws.send(JSON.stringify({ type: 'GET_ROOMS' }));
    };
    ws.onmessage = (ev) => {
      let m; try { m = JSON.parse(ev.data.toString()); } catch { return; }
      if (m.type === 'FRIEND_EVENT') {
        let payload = null;
        try { payload = JSON.parse(m.data); } catch {}
        if (payload) st.events.push(payload);
      }
      if (m.type === 'DIRECT_MSG') st.dms.push({ from: m.playerId, name: m.playerName, text: m.message });
      if (m.type === 'ROOM_LIST' && !st.bound) { st.bound = true; resolve(st); }
      if (m.type === 'ERROR') { st.errs.push(m.message); }
    };
    // 冷启动放宽：15s 内没确认也算绑定失败返回，由断言判定
    setTimeout(() => { if (!st.bound) resolve(st); }, 15000);
  });
}

/** 等某大厅连接收到指定 kind 的好友事件（最多 timeoutMs 毫秒） */
async function waitForFriendEvent(st, kind, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const hit = st.events.find(e => e.kind === kind);
    if (hit) return hit;
    await new Promise(r => setTimeout(r, 100));
  }
  return null;
}

try {
  const a = await newGuest();
  const b = await newGuest();
  const c = await newGuest();
  check('游客身份签发 ×3', !!a.token && !!b.token && !!c.token);

  // 1. 未登录 401
  const anon = await request('/api/friends');
  check('未带凭证访问好友接口 401', anon.status === 401, 'status=' + anon.status);

  // 2. 首次打开面板签发 8 位好友码
  const listA = await request('/api/friends', auth(a.token));
  check('好友列表可读', listA.status === 200 && !!listA.body.me, JSON.stringify(listA.body));
  const codeA = listA.body.me.code;
  check('好友码为 8 位去混淆编码', /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$/.test(codeA), 'code=' + codeA);
  const listAgain = await request('/api/friends', auth(a.token));
  check('好友码稳定不变', listAgain.body.me.code === codeA);

  // 3. B 按好友码申请 A
  const req1 = await request('/api/friends/requests', json({ target: codeA }, b.token));
  check('按好友码发申请成功', req1.status === 200, JSON.stringify(req1.body));
  const reqDup = await request('/api/friends/requests', json({ target: codeA }, b.token));
  check('重复申请 409', reqDup.status === 409 && reqDup.body.code === 'CONFLICT', 'status=' + reqDup.status);

  // 4. 小写好友码也能解析
  const d = await newGuest();
  const listD = await request('/api/friends', auth(d.token));
  const reqLower = await request('/api/friends/requests', json({ target: listD.body.me.code.toLowerCase() }, c.token));
  check('小写好友码发申请成功', reqLower.status === 200, 'status=' + reqLower.status);

  // 5. 双方都能看到申请
  const outB = await request('/api/friends', auth(b.token));
  check('发起方 outgoing 可见', (outB.body.outgoing || []).some(x => x.id === a.id), JSON.stringify(outB.body.outgoing));
  const inA = await request('/api/friends', auth(a.token));
  check('接收方 incoming 可见', (inA.body.incoming || []).some(x => x.id === b.id), JSON.stringify(inA.body.incoming));
  check('incoming 条目带展示名', (inA.body.incoming[0] || {}).name, JSON.stringify(inA.body.incoming));

  // 6. A 同意 → 双向好友
  const accept = await request('/api/friends/requests/' + b.id + '/accept', { method: 'POST', ...auth(a.token) });
  check('同意申请成功', accept.status === 200, JSON.stringify(accept.body));
  const friendsA = (await request('/api/friends', auth(a.token))).body.friends || [];
  const friendsB = (await request('/api/friends', auth(b.token))).body.friends || [];
  check('发起方出现好友', friendsA.some(x => x.id === b.id));
  check('接收方出现好友', friendsB.some(x => x.id === a.id));

  // 7. 拒绝路径：C 已向 D 申请，D 拒绝后双方清单清空
  const decline = await request('/api/friends/requests/' + c.id + '/decline', { method: 'POST', ...auth(d.token) });
  check('拒绝申请成功', decline.status === 200, JSON.stringify(decline.body));
  const inD = (await request('/api/friends', auth(d.token))).body.incoming || [];
  check('拒绝后 incoming 清空', !inD.some(x => x.id === c.id));

  // 8. 撤回路径：B 向 C 申请再撤回
  await request('/api/friends/requests', json({ target: (await request('/api/friends', auth(c.token))).body.me.code }, b.token));
  const cancel = await request('/api/friends/requests/' + c.id, { method: 'DELETE', ...auth(b.token) });
  check('撤回申请成功', cancel.status === 200, JSON.stringify(cancel.body));
  const inC = (await request('/api/friends', auth(c.token))).body.incoming || [];
  check('撤回后对方 incoming 清空', !inC.some(x => x.id === b.id));

  // 9. 按用户名申请（D 注册账号后，A 用用户名加 D）
  const username = 'smokef' + suffix;
  const reg = await request('/api/account/register', json({ username, password: 'secret123' }, d.token));
  check('D 注册账号成功', reg.status === 200, JSON.stringify(reg.body));
  const byName = await request('/api/friends/requests', json({ target: username.toUpperCase() }, a.token));
  check('按用户名发申请成功（大小写不敏感）', byName.status === 200, JSON.stringify(byName.body));
  const acceptD = await request('/api/friends/requests/' + a.id + '/accept', { method: 'POST', ...auth(d.token) });
  check('D 同意 A 的用户名申请', acceptD.status === 200, JSON.stringify(acceptD.body));

  // 10. 错误路径：找不到目标 / 添加自己
  const unknown = await request('/api/friends/requests', json({ target: 'ZZZZ99XX' }, a.token));
  check('不存在的目标 404', unknown.status === 404 && unknown.body.code === 'NOT_FOUND', 'status=' + unknown.status);
  const self = await request('/api/friends/requests', json({ target: codeA }, a.token));
  check('添加自己 400', self.status === 400 && self.body.code === 'INVALID', 'status=' + self.status);

  // 11. 好友条目默认离线（REST 身份没有 WS 连接）
  check('REST 身份默认 offline', friendsA.every(x => x.online === false && !x.roomId), JSON.stringify(friendsA));

  // 12. 删除好友双向移除
  const del = await request('/api/friends/' + b.id, { method: 'DELETE', ...auth(a.token) });
  check('删除好友成功', del.status === 200, JSON.stringify(del.body));
  const afterDel = (await request('/api/friends', auth(a.token))).body.friends || [];
  check('删除后不再互为好友', !afterDel.some(x => x.id === b.id));
  const afterDelB = (await request('/api/friends', auth(b.token))).body.friends || [];
  check('对方一侧同步移除', !afterDelB.some(x => x.id === a.id));

  // ============ WS 真实链路：在线状态与一键加入好友房间 ============

  const p1 = await newGuest();
  const p2 = await newGuest();
  await request('/api/friends', auth(p1.token)); // 签发好友码
  const codeP1 = (await request('/api/friends', auth(p1.token))).body.me.code;
  await request('/api/friends/requests', json({ target: codeP1 }, p2.token));
  await request('/api/friends/requests/' + p2.id + '/accept', { method: 'POST', ...auth(p1.token) });

  // p1 连 WS 并建房（私有房）；p2 通过好友面板数据一键加入
  const wsP1 = await wsJoin('房主小张', p1.token, { create: true });
  check('p1 建房进入等待', wsP1.state === 'WAITING' && !!wsP1.roomId, JSON.stringify(wsP1.errs));

  const viewP2 = (await request('/api/friends', auth(p2.token))).body;
  const entryP1 = (viewP2.friends || []).find(x => x.id === p1.id) || {};
  check('好友在线状态 online', entryP1.online === true, JSON.stringify(entryP1));
  check('好友昵称实时解析', entryP1.name === '房主小张', 'name=' + entryP1.name);
  check('好友所在房间对好友可见（私有房=邀请语义）', entryP1.roomId === wsP1.roomId,
    JSON.stringify({ roomId: entryP1.roomId, want: wsP1.roomId }));
  check('等待房 joinable 且带玩法类型', entryP1.joinable === true && entryP1.gameType === 'GOMOKU',
    JSON.stringify(entryP1));
  const publicRooms = await (await fetch(base + '/api/rooms')).json();
  check('该私有房不在公共列表', (Array.isArray(publicRooms) ? publicRooms : [])
    .every(r => r.roomId !== wsP1.roomId), JSON.stringify(publicRooms.map(r => r.roomId)));

  // p2 按 joinFriendRoom 的方式带玩法类型 JOIN 好友房间
  const wsP2 = await wsJoin('好友小李', p2.token, { roomId: wsP1.roomId, gameType: entryP1.gameType });
  check('好友一键加入并开局', wsP2.started && wsP2.roomId === wsP1.roomId, JSON.stringify(wsP2.errs));
  const viewP1 = (await request('/api/friends', auth(p1.token))).body;
  const entryP2 = (viewP1.friends || []).find(x => x.id === p2.id) || {};
  check('加入后对端看到 PLAYING 不可加入', entryP2.online === true && entryP2.joinable === false,
    JSON.stringify(entryP2));

  // p1 断线后好友列表转离线（实时连接为准）
  wsP1.ws.close();
  let offlineSeen = false;
  for (let i = 0; i < 10 && !offlineSeen; i++) {
    await new Promise(r => setTimeout(r, 500));
    const list = (await request('/api/friends', auth(p2.token))).body.friends || [];
    offlineSeen = list.find(x => x.id === p1.id)?.online === false;
  }
  check('p1 断线后好友转离线', offlineSeen);
  wsP2.ws.close();

  // ============ WS 实时推送：上线/下线/申请/通过的大厅提醒 ============

  // 甲坐大厅（HALL_BIND 不进房），乙是其好友
  const h1 = await newGuest();
  const h2 = await newGuest();
  await request('/api/friends', auth(h1.token));
  const codeH1 = (await request('/api/friends', auth(h1.token))).body.me.code;
  await request('/api/friends/requests', json({ target: codeH1 }, h2.token));
  await request('/api/friends/requests/' + h2.id + '/accept', { method: 'POST', ...auth(h1.token) });

  const hallA = await hallClient('大厅甲', h1.token);
  check('甲在大厅完成身份绑定', hallA.bound && !hallA.errs.length, JSON.stringify(hallA.errs));

  // REST 侧确认大厅绑定 = 在线且无房间（HALL_BIND 的 presence 语义）
  const aView = (await request('/api/friends', auth(h2.token))).body.friends?.find(x => x.id === h1.id);
  check('大厅绑定后好友看到在线且无房间', aView?.online === true && !aView?.roomId, JSON.stringify(aView));

  // 乙上线 → 甲实时收到 online 事件
  const hallB = await hallClient('大厅乙', h2.token);
  const onlineEvt = await waitForFriendEvent(hallA, 'online');
  check('好友上线实时推送 online', !!onlineEvt && onlineEvt.id === h2.id && onlineEvt.name === '大厅乙',
    JSON.stringify(onlineEvt));

  // 丙（陌生人）上线 → 甲不应收到任何事件
  const h3 = await newGuest();
  const hallC = await hallClient('路人丙', h3.token);
  await new Promise(r => setTimeout(r, 800));
  check('陌生人上线不推送', !hallA.events.some(e => e.id === h3.id), JSON.stringify(hallA.events));

  // 丁向甲发申请（REST）→ 甲实时收到 request 事件
  const h4 = await newGuest();
  const codeAgain = (await request('/api/friends', auth(h1.token))).body.me.code;
  await request('/api/friends/requests', json({ target: codeAgain }, h4.token));
  const requestEvt = await waitForFriendEvent(hallA, 'request');
  check('收到好友申请实时推送 request', !!requestEvt && requestEvt.id === h4.id, JSON.stringify(requestEvt));


  // 精确复测：庚在线大厅 → 辛向庚发申请 → 庚同意 → 辛实时收到 accepted（辛一直在线）
  const h7 = await newGuest();
  const h8 = await newGuest();
  const hallG = await hallClient('大厅庚', h7.token);
  const hallH = await hallClient('大厅辛', h8.token);
  const codeG = (await request('/api/friends', auth(h7.token))).body.me.code;
  await request('/api/friends/requests', json({ target: codeG }, h8.token));
  const reqEvtG = await waitForFriendEvent(hallG, 'request');
  check('庚实时收到辛的申请', !!reqEvtG && reqEvtG.id === h8.id, JSON.stringify(reqEvtG));
  await request('/api/friends/requests/' + h8.id + '/accept', { method: 'POST', ...auth(h7.token) });
  const accEvtH = await waitForFriendEvent(hallH, 'accepted');
  check('辛实时收到通过提醒 accepted', !!accEvtH && accEvtH.id === h7.id, JSON.stringify(accEvtH));

  // 乙下线 → 甲实时收到 offline 事件
  hallB.ws.close();
  const offlineEvt = await waitForFriendEvent(hallA, 'offline');
  check('好友下线实时推送 offline', !!offlineEvt && offlineEvt.id === h2.id, JSON.stringify(offlineEvt));

  // ============ 好友私聊（DIRECT_MSG） ============

  // 庚→辛 私聊：好友且在线，双向可达
  hallG.ws.send(JSON.stringify({ type: 'DIRECT_MSG', data: h8.id, message: '辛你好，我是庚' }));
  const dmToH = await (async () => {
    const deadline = Date.now() + 4000;
    while (Date.now() < deadline) {
      const hit = hallH.dms.find(d => d.from === h7.id && d.text === '辛你好，我是庚');
      if (hit) return hit;
      await new Promise(r => setTimeout(r, 100));
    }
    return null;
  })();
  check('好友在线私聊送达', !!dmToH && dmToH.name === '大厅庚', JSON.stringify(hallH.dms));

  // 辛回复庚
  hallH.ws.send(JSON.stringify({ type: 'DIRECT_MSG', data: h7.id, message: '收到，我是辛' }));
  const dmBack = await (async () => {
    const deadline = Date.now() + 4000;
    while (Date.now() < deadline) {
      const hit = hallG.dms.find(d => d.from === h8.id);
      if (hit) return hit;
      await new Promise(r => setTimeout(r, 100));
    }
    return null;
  })();
  check('私聊回复反向可达', !!dmBack && dmBack.text === '收到，我是辛', JSON.stringify(hallG.dms));

  // 限流：庚 1 秒内连发两条，只有第一条送达（与房间聊天同款静默限流）
  await new Promise(r => setTimeout(r, 1100)); // 等过限流窗口再开始
  const beforeCount = hallH.dms.filter(d => d.from === h7.id).length;
  hallG.ws.send(JSON.stringify({ type: 'DIRECT_MSG', data: h8.id, message: '限流第一条' }));
  hallG.ws.send(JSON.stringify({ type: 'DIRECT_MSG', data: h8.id, message: '限流第二条应被丢弃' }));
  await new Promise(r => setTimeout(r, 800));
  const afterCount = hallH.dms.filter(d => d.from === h7.id).length;
  const lastText = (hallH.dms.filter(d => d.from === h7.id).pop() || {}).text;
  check('私聊 1 秒限流只送首条', afterCount === beforeCount + 1 && lastText === '限流第一条',
    JSON.stringify({ beforeCount, afterCount, lastText }));

  // 超长消息：服务端截断到 200 字（与房间聊天同款清洗）
  await new Promise(r => setTimeout(r, 1100));
  const longText = '甲'.repeat(300);
  hallG.ws.send(JSON.stringify({ type: 'DIRECT_MSG', data: h8.id, message: longText }));
  const longDm = await (async () => {
    const deadline = Date.now() + 4000;
    while (Date.now() < deadline) {
      const hit = hallH.dms.find(d => d.from === h7.id && d.text.startsWith('甲'));
      if (hit) return hit;
      await new Promise(r => setTimeout(r, 100));
    }
    return null;
  })();
  check('超长私聊截断到 200 字', !!longDm && longDm.text.length === 200,
    JSON.stringify(longDm ? longDm.text.length : null));

  // 非好友私聊被拒：丙 → 甲（不是好友），丙收到明确错误，甲收不到
  const aDmCountBefore = hallA.dms.length;
  hallC.ws.send(JSON.stringify({ type: 'DIRECT_MSG', data: h1.id, message: '陌生人私信' }));
  await new Promise(r => setTimeout(r, 600));
  check('非好友私聊被拒且带明确错误', hallC.errs.some(e => e.includes('只能给好友发私聊')),
    JSON.stringify(hallC.errs));
  check('非好友私信不送达', hallA.dms.length === aDmCountBefore);

  // 离线好友私聊明确报错：甲先 REST 同意丁的申请成为好友（丁仅 REST 身份、无 WS 连接）
  await request('/api/friends/requests/' + h4.id + '/accept', { method: 'POST', ...auth(h1.token) });
  await new Promise(r => setTimeout(r, 1100)); // 过限流窗口
  hallA.ws.send(JSON.stringify({ type: 'DIRECT_MSG', data: h4.id, message: '丁在线吗？' }));
  await new Promise(r => setTimeout(r, 600));
  check('离线好友私聊明确报错', hallA.errs.some(e => e.includes('对方当前不在线')),
    JSON.stringify(hallA.errs));

  [hallA, hallB, hallC, hallG, hallH].forEach(s => { try { s.ws.close(); } catch {} });
} catch (error) {
  failed++;
  console.error('[friends] FAIL 异常 :: ' + (error && error.message));
}

console.log(failed === 0
  ? '[friends] PASS (' + passed + '/' + passed + ')'
  : '[friends] FAIL (' + failed + ' failed, ' + passed + ' passed)');
process.exitCode = failed === 0 ? 0 : 1;
