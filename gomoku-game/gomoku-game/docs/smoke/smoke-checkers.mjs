// 西洋跳棋端到端冒烟：真实双 WebSocket 对局。
//
// 覆盖：
// 1) 真人建房 + 房号加入，两名玩家进入同一间房；
// 2) 两阶段走子协议（fromRow/fromCol + row/col）与红方先手；
// 3) 强制吃子：普通前进被服务端拒绝，且拒绝后 moveSeq 按服务端回传校准；
// 4) 吃子后盘面/回合随广播更新，后续走子不产生协议错误；
// 5) 认输终局、回放数据、分数与双人 ELO 零和结算。
const URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
let passed = 0, failed = 0;
const sleep = ms => new Promise(r => setTimeout(r, ms));

function check(name, cond, detail) {
  if (cond) { passed++; console.log('[checkers] PASS ' + name); }
  else { failed++; console.error('[checkers] FAIL ' + name + (detail ? ' :: ' + JSON.stringify(detail) : '')); }
}

function client(name, roomId) {
  const c = {
    name, ws: new WebSocket(URL), id: null, stone: 0, roomId: null,
    seq: 0, turn: 1, view: null, over: false, result: null, errors: []
  };
  c.ws.onopen = () => {
    const join = { type: 'JOIN', playerName: name, gameType: 'CHECKERS', playerCount: 2 };
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

const watchdog = setTimeout(() => { console.error('[checkers] 超时'); process.exit(1); }, 60000);

(async () => {
  console.log('== 西洋跳棋双人对局 ==');
  console.log('   WS=' + URL);
  const a = client('跳棋甲', null);
  let b = null;
  try {
    await waitFor(() => a.roomId, '甲建房', 10000);
    b = client('跳棋乙', a.roomId);
    await waitFor(() => a.stone > 0 && b.stone > 0, '双方开局', 12000);
    check('两名玩家进入同一房间', a.roomId === b.roomId, { a: a.roomId, b: b.roomId });
    check('双方分到不同颜色', a.stone !== b.stone, { a: a.stone, b: b.stone });
    const red = a.stone === 1 ? a : b;
    const black = red === a ? b : a;
    check('红方先手', red.stone === 1 && black.stone === 2, { red: red.stone, black: black.stone });
    check('开局下发合法走法视图', Array.isArray(red.view?.legalMoves) && red.view.legalMoves.length === 7,
      red.view);

    // 构造强制吃子：红 (5,2)->(4,3)，黑 (2,1)->(3,2)，此时红必须跳吃 (3,2)。
    red.move(5, 2, 4, 3);
    await waitFor(() => red.turn === 2, '红方第一步');
    black.move(2, 1, 3, 2);
    await waitFor(() => red.turn === 1, '黑方第一步');
    const captureView = red.view;
    check('存在必须执行的吃子', Array.isArray(captureView?.legalMoves)
      && captureView.legalMoves.length > 0 && captureView.legalMoves.every(m => m.capture), captureView);

    const seqBefore = red.seq;
    red.move(4, 3, 3, 4); // 普通前进，强制吃子下应被拒
    await waitFor(() => red.errors.length > 0, '非法普通移动被拒');
    check('强制吃子时普通前进被拒', red.errors.includes('无效落子'), red.errors);
    check('拒绝后服务端序号保持不变', red.seq === seqBefore, { before: seqBefore, after: red.seq });
    await sleep(200);

    // 真正执行跳吃：红 (4,3) 跳过黑 (3,2) 落到 (2,1)。
    red.move(4, 3, 2, 1);
    await waitFor(() => red.turn === 2, '红方完成吃子', 8000);
    check('吃子后轮到黑方', red.turn === 2, { turn: red.turn });
    check('吃子后无新增协议错误', red.errors.length === 1 && black.errors.length === 0,
      { red: red.errors, black: black.errors });

    // 此时黑方也有强制吃子，必须沿可吃路线继续，普通移动会被拒绝。
    const blackCapture = black.view.legalMoves.find(m => m.capture);
    check('黑方同样必须吃子', !!blackCapture, black.view);
    black.move(blackCapture.fromRow, blackCapture.fromCol, blackCapture.toRow, blackCapture.toCol);
    await waitFor(() => red.turn === 1, '黑方第二步');
    const redLegal = red.view.legalMoves.find(m => !m.capture) || red.view.legalMoves[0];
    red.move(redLegal.fromRow, redLegal.fromCol, redLegal.toRow, redLegal.toCol);
    await waitFor(() => red.turn === 2, '红方第二步');
    check('多手后仍无新增协议错误', red.errors.length === 1 && black.errors.length === 0,
      { red: red.errors, black: black.errors });
    check('合法走法视图随回合更新', Array.isArray(black.view?.legalMoves) && black.view.legalMoves.length > 0,
      black.view);

    red.send({ type: 'SURRENDER' });
    await waitFor(() => red.over && black.over, '双方收到终局', 12000);
    check('终局胜者为黑方', red.result.winner === black.id, { winner: red.result.winner, black: black.id });
    check('终局包含回放手数', Array.isArray(red.result.moveHistory) && red.result.moveHistory.length >= 5,
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
  console.log(failed ? `\n[checkers] FAIL (${failed} failed, ${passed} passed)` : `\n[checkers] PASS (${passed}/${passed})`);
  process.exit(failed ? 1 : 0);
})().catch(e => {
  clearTimeout(watchdog);
  console.error('[checkers] 异常:', e.message);
  process.exit(1);
});
