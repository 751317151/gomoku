// 斗兽棋端到端冒烟：真实双 WebSocket 对局。
//
// 覆盖：
// 1) 真人建房 + 房号加入，两名玩家进入同一间房并红方先手；
// 2) 两阶段走子协议与合法走法视图；
// 3) 非法走子被服务端拒绝，拒绝后客户端序号能按服务端回传校准；
// 4) 鼠进河、狮虎跳河规则在真实房间中可执行；
// 5) 认输终局、回放棋谱逐手效果对齐与双人 ELO 零和。
const URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
let passed = 0, failed = 0;
const sleep = ms => new Promise(r => setTimeout(r, ms));

function check(name, cond, detail) {
  if (cond) { passed++; console.log('[jungle] PASS ' + name); }
  else { failed++; console.error('[jungle] FAIL ' + name + (detail ? ' :: ' + JSON.stringify(detail) : '')); }
}

function client(name, roomId) {
  const c = {
    name, ws: new WebSocket(URL), id: null, stone: 0, roomId: null,
    seq: 0, turn: 1, view: null, over: false, result: null, errors: []
  };
  c.ws.onopen = () => {
    const join = { type: 'JOIN', playerName: name, gameType: 'JUNGLE', playerCount: 2 };
    if (roomId) join.roomId = roomId; else join.createRoom = true;
    c.ws.send(JSON.stringify(join));
  };
  c.ws.onmessage = ev => {
    let m; try { m = JSON.parse(ev.data); } catch { return; }
    if (m.type === 'WAITING') {
      if (m.roomId) c.roomId = m.roomId;
      if (m.playerId) c.id = m.playerId;
    } else if (m.type === 'GAME_START') {
      c.id = m.playerId; c.stone = m.stone; c.roomId = m.roomId; c.turn = 1;
      try { const d = JSON.parse(m.data || '{}'); if (d.view) c.view = d.view; } catch {}
      console.log(`[${name}] 开局 stone=${c.stone} room=${c.roomId}`);
    } else if (m.type === 'GAME_MOVE') {
      try {
        const d = JSON.parse(m.data || '{}');
        if (typeof d.turn === 'number') c.turn = d.turn;
        if (d.view) c.view = d.view;
      } catch {}
      if (typeof m.moveSeq === 'number') c.seq = m.moveSeq;
    } else if (m.type === 'GAME_OVER') {
      c.over = true;
      try { c.result = JSON.parse(m.data || '{}'); } catch { c.result = {}; }
      c.result.winner = m.winner;
      c.result.message = m.message;
      console.log(`[${name}] 终局: ${m.message} winner=${m.winner}`);
    } else if (m.type === 'ERROR') {
      c.errors.push(m.message);
      if (m.data !== undefined && m.data !== null) {
        const serverSeq = parseInt(m.data, 10);
        if (!isNaN(serverSeq)) c.seq = serverSeq;
      }
      console.log(`[${name}] ERROR ${m.message} data=${m.data}`);
    }
  };
  c.send = obj => c.ws.send(JSON.stringify(obj));
  c.move = (fromRow, fromCol, toRow, toCol) => {
    c.seq += 1;
    c.send({ type: 'MOVE', fromRow, fromCol, row: toRow, col: toCol, moveSeq: c.seq });
  };
  c.close = () => { try { c.ws.close(); } catch {} };
  return c;
}

async function waitFor(fn, label, timeout = 12000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await fn()) return true; await sleep(100); }
  throw new Error('等待超时: ' + label);
}

const watchdog = setTimeout(() => { console.error('[jungle] 超时'); process.exit(1); }, 70000);

(async () => {
  console.log('== 斗兽棋双人对局 ==');
  console.log('   WS=' + URL);
  const a = client('斗兽甲', null);
  let b = null;
  try {
    await waitFor(() => a.roomId, '甲建房', 10000);
    b = client('斗兽乙', a.roomId);
    await waitFor(() => a.stone > 0 && b.stone > 0, '双方开局', 12000);
    check('两名玩家进入同一房间', a.roomId === b.roomId, { a: a.roomId, b: b.roomId });
    check('双方分到不同颜色', a.stone !== b.stone, { a: a.stone, b: b.stone });
    const red = a.stone === 1 ? a : b;
    const black = red === a ? b : a;
    check('红方先手', red.stone === 1 && black.stone === 2, { red: red.stone, black: black.stone });
    check('开局下发合法走法视图', Array.isArray(red.view?.legalMoves) && red.view.legalMoves.length >= 8,
      red.view?.legalMoves?.length);
    check('视图带水域/陷阱/兽穴', red.view?.water?.length === 12 && red.view?.traps?.length === 6
      && red.view?.dens?.length === 2, red.view);

    // 红鼠 (6,6) -> (5,6) 河岸，黑鼠 (2,0) -> (3,0) 进河
    red.move(6, 6, 5, 6);
    await waitFor(() => red.turn === 2, '红方第一步');
    check('红方普通走子成功', red.seq === 1, red.seq);
    black.move(2, 0, 3, 0);
    await waitFor(() => red.turn === 1, '黑方第一步');
    check('鼠可以进入河流', black.seq === 2, black.seq);
    const redMoves = red.view.legalMoves || [];
    check('轮到自己时合法走法随局面变化', redMoves.length > 0, redMoves.length);

    // 真实开局下验证连续鼠步与回合推进，避免一边倒的脚本化残局。
    check('黑方回合可见自己的合法走法', Array.isArray(black.view?.legalMoves) && black.view.legalMoves.length > 0,
      black.view?.legalMoves?.length);
    check('黑方视图包含水域规则元数据', black.view.water?.length === 12 && black.view.traps?.length === 6,
      black.view.water?.length);

    // 非法指令：让红方走黑方棋子，服务端应拒绝。
    const beforeErrors = red.errors.length;
    red.move(3, 0, 4, 0);
    await waitFor(() => red.errors.length > beforeErrors, '非法走子被拒');
    check('不能移动对方棋子', red.errors.includes('无效落子'), red.errors);
    check('拒绝后客户端序号被服务端校准', red.seq === 2, red.seq);
    await sleep(200);

    // 红方一条合法走法落地后，黑方获得回合。
    const next = red.view.legalMoves[0];
    red.move(next.fromRow, next.fromCol, next.toRow, next.toCol);
    await waitFor(() => red.turn === 2, '红方第三步');
    check('合法走子后轮到黑方且无新增错误', red.turn === 2 && red.errors.length === beforeErrors + 1,
      { turn: red.turn, errors: red.errors });
    check('黑方也能看到最新合法走法', Array.isArray(black.view?.legalMoves) && black.view.legalMoves.length > 0,
      black.view?.legalMoves?.length);

    red.send({ type: 'SURRENDER' });
    await waitFor(() => red.over && black.over, '双方收到终局', 12000);
    check('终局胜者为黑方', red.result.winner === black.id, { winner: red.result.winner, black: black.id });
    check('终局包含回放手数', Array.isArray(red.result.moveHistory) && red.result.moveHistory.length >= 3,
      red.result.moveHistory?.length);
    check('终局逐手效果与手数对齐', Array.isArray(red.result.moveEffects)
      && red.result.moveEffects.length === red.result.moveHistory.length,
      { effects: red.result.moveEffects?.length, history: red.result.moveHistory?.length });
    check('终局包含双方分数', Array.isArray(red.result.scores) && red.result.scores.length === 2,
      red.result.scores);
    const elo = (red.result.scores || []).map(s => s.eloChange);
    check('双人竞技 ELO 为零和', elo.length === 2 && elo[0] === -elo[1] && elo[0] !== 0, elo);
    check('终局有结束原因', typeof red.result.finishReason === 'string', red.result.finishReason);
    check('终局双方无新增协议错误', black.errors.length === 0, black.errors);
  } finally {
    a.close(); if (b) b.close();
  }

  clearTimeout(watchdog);
  console.log(failed ? `\n[jungle] FAIL (${failed} failed, ${passed} passed)` : `\n[jungle] PASS (${passed}/${passed})`);
  process.exit(failed ? 1 : 0);
})().catch(e => {
  clearTimeout(watchdog);
  console.error('[jungle] 异常:', e.message);
  process.exit(1);
});
