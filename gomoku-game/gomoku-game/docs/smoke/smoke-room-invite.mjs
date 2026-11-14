// 房号邀请端到端校验
//   1. 「创建房间」必须新建房间，不能把人塞进别人的等待房间
//   2. 好友用（大小写/无前缀的）房号能加入，并立刻开局
//   3. 不存在的房号必须明确失败，不能回落到自动匹配
//   4. 已开局的房间不能再按房号加入
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const REST_URL = process.env.REST_URL || 'http://127.0.0.1:19000';

const problems = [];
let failed = false;
function assert(cond, label, extra) {
  if (cond) { console.log(`  ✓ ${label}`); return true; }
  console.log(`  ✗ ${label}${extra !== undefined ? ' — ' + JSON.stringify(extra) : ''}`);
  problems.push(label); failed = true;
  return false;
}

/** 建一条连接；create=true 时带 createRoom 建房，roomId 非空时按房号加入 */
function connect(name, { create = false, roomId = null, gameType = 'GOMOKU' } = {}) {
  return new Promise((resolve) => {
    const ws = new WebSocket(WS_URL);
    const st = { ws, name, roomId: null, state: null, stone: 0, errs: [], started: false };
    ws.onopen = () => {
      const join = { type: 'JOIN', playerName: name, gameType };
      if (create) join.createRoom = true;
      if (roomId) join.roomId = roomId;
      ws.send(JSON.stringify(join));
    };
    ws.onmessage = (ev) => {
      let m; try { m = JSON.parse(ev.data.toString()); } catch { return; }
      if (m.type === 'WAITING') { st.roomId = m.roomId; st.state = 'WAITING'; }
      if (m.type === 'GAME_START') { st.roomId = m.roomId; st.state = 'PLAYING'; st.stone = m.stone; st.started = true; }
      if (m.type === 'ROOM_INFO' && m.roomId && !st.roomId) st.roomId = m.roomId;
      if (m.type === 'ERROR') st.errs.push(m.message);
    };
    // 等服务端真的给出结果（WAITING / GAME_START / ERROR），不要固定等待：
    // 服务刚启动时首条连接可能超过 1.8s 才收到回包，固定 sleep 会误判成「建房失败」。
    let done = false;
    const finish = () => { if (done) return; done = true; resolve(st); };
    const poll = setInterval(() => {
      if (st.state || st.errs.length) { clearInterval(poll); finish(); }
    }, 100);
    setTimeout(() => { clearInterval(poll); finish(); }, 5000);
  });
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const close = (s) => { try { s.ws.close(); } catch {} };

(async () => {
  console.log('== 房号邀请校验 ==');

  // 1) 两次「创建房间」必须是两个不同房间
  const a = await connect('邀请甲', { create: true });
  const b = await connect('邀请乙', { create: true });
  assert(a.state === 'WAITING' && !!a.roomId, '甲建房成功并进入等待', a.errs);
  assert(b.state === 'WAITING' && !!b.roomId, '乙建房成功并进入等待', b.errs);
  assert(a.roomId !== b.roomId, '两个房间号不同（createRoom 没有匹配进同一间）', [a.roomId, b.roomId]);

  // 私有房不应出现在大厅列表里（否则陌生人能直接点进来占座，邀请就没意义了）
  const list = await (await fetch(REST_URL + '/api/rooms')).json();
  const listed = (Array.isArray(list) ? list : []).map(r => r.roomId);
  assert(!listed.includes(a.roomId) && !listed.includes(b.roomId),
    '私有房不出现在大厅房间列表', { listed, a: a.roomId, b: b.roomId });

  close(b);
  await sleep(300);

  // 2) 好友用「小写、无前缀」的房号加入甲的房
  const code = a.roomId.replace(/^ROOM-/, '').toLowerCase();
  const c = await connect('邀请好友', { roomId: code });
  assert(c.started, '好友按房号加入并开局', c.errs);
  assert(c.roomId === a.roomId, '双方在同一房间', [c.roomId, a.roomId]);

  // 3) 已开局的房间不能再加入
  const late = await connect('迟到者', { roomId: a.roomId });
  assert(!late.started && late.errs.length > 0, '已开局房间拒绝新玩家', late.errs);
  close(late);
  await sleep(300);

  // 4) 不存在的房号必须失败，且不能把玩家塞进某个等待房间
  const waiting = await connect('等待者', { create: true });
  assert(!!waiting.roomId, '先制造一个等待房间', waiting.errs);
  const wrong = await connect('输错房号', { roomId: 'ROOM-ZZZZZZ' });
  assert(!wrong.started && wrong.errs.length > 0, '不存在的房号被拒绝', wrong.errs);
  assert(wrong.roomId !== waiting.roomId, '没有被回落匹配进等待房间', [wrong.roomId, waiting.roomId]);

  close(a); close(c); close(waiting); close(wrong);
  console.log('  问题:', problems.length ? problems : '无');
  console.log(failed ? '失败' : '全部通过');
  // 不用 process.exit：Node 24/Windows 在 WS 关闭握手未完成时退出会触发 libuv 断言
  process.exitCode = failed ? 1 : 0;
})().catch(e => { console.error('异常:', e.message); process.exit(1); });
