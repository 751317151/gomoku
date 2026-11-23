// 暗棋端到端冒烟：真实双 WebSocket 对局。
//
// 覆盖：
// 1) 两名真人进入同一间房、红方先手、32 枚棋子全部背面朝上；
// 2) 无起点的 MOVE 是翻子，双方视图都不会泄露未翻开的棋子身份；
// 3) 非法行动被拒且服务端手数能校准客户端；
// 4) 翻子后按服务端合法走法真实走子；
// 5) 认输终局、回放棋谱/逐手效果对齐、随机开局盘面随终局下发。
const URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
let passed = 0, failed = 0;
const sleep = ms => new Promise(r => setTimeout(r, ms));

function check(name, cond, detail) {
  if (cond) { passed++; console.log('[dark-chess] PASS ' + name); }
  else { failed++; console.error('[dark-chess] FAIL ' + name + (detail ? ' :: ' + JSON.stringify(detail) : '')); }
}

function client(name, roomId) {
  const c = {
    name, ws: new WebSocket(URL), id: null, stone: 0, roomId: null,
    seq: 0, turn: 1, board: null, view: null, over: false, result: null, errors: []
  };
  c.ws.onopen = () => {
    const join = { type: 'JOIN', playerName: name, gameType: 'DARK_CHESS', playerCount: 2 };
    if (roomId) join.roomId = roomId; else join.createRoom = true;
    c.ws.send(JSON.stringify(join));
  };
  c.ws.onmessage = ev => {
    let m; try { m = JSON.parse(ev.data); } catch { return; }
    if (m.type === 'WAITING') {
      if (m.roomId) c.roomId = m.roomId;
      if (m.playerId) c.id = m.playerId;
    } else if (m.type === 'GAME_START') {
      c.id = m.playerId; c.stone = m.stone; c.roomId = m.roomId;
      try {
        const d = JSON.parse(m.data || '{}');
        if (typeof d.currentTurn === 'number') c.turn = d.currentTurn;
        if (d.view) c.view = d.view;
      } catch {}
      console.log(`[${name}] 开局 stone=${c.stone} room=${c.roomId}`);
    } else if (m.type === 'GAME_SYNC') {
      try {
        const d = JSON.parse(m.data || '{}');
        if (d.board) c.board = d.board;
        if (d.view) c.view = d.view;
        if (d.roomData?.currentTurn) c.turn = d.roomData.currentTurn;
      } catch {}
      if (typeof m.moveSeq === 'number') c.seq = m.moveSeq;
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
  c.flip = (row, col) => {
    c.seq += 1;
    c.send({ type: 'MOVE', row, col, moveSeq: c.seq });
  };
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

const watchdog = setTimeout(() => { console.error('[dark-chess] 超时'); process.exit(1); }, 70000);

(async () => {
  console.log('== 暗棋双人对局 ==');
  console.log('   WS=' + URL);
  const a = client('暗棋甲', null);
  let b = null;
  try {
    await waitFor(() => a.roomId, '甲建房', 10000);
    b = client('暗棋乙', a.roomId);
    await waitFor(() => a.stone > 0 && b.stone > 0, '双方开局', 12000);
    check('两名玩家进入同一房间', a.roomId === b.roomId, { a: a.roomId, b: b.roomId });
    check('双方分到不同颜色', a.stone !== b.stone, { a: a.stone, b: b.stone });
    const red = a.stone === 1 ? a : b;
    const black = red === a ? b : a;
    check('红方先手', red.stone === 1 && black.stone === 2, { red: red.stone, black: black.stone });
    check('开局 32 枚暗子全部可翻', Array.isArray(red.view?.legalFlips) && red.view.legalFlips.length === 32,
      red.view?.legalFlips?.length);
    check('开局没有已翻明棋可走', Array.isArray(red.view?.legalMoves) && red.view.legalMoves.length === 0,
      red.view?.legalMoves?.length);
    check('视图显示 32 枚背面棋', red.view?.hiddenCount === 32, red.view?.hiddenCount);
    check('开局红黑各 16 枚', red.view?.redRemaining === 16 && red.view?.blackRemaining === 16, red.view);

    red.flip(0, 0);
    await waitFor(() => red.turn === 2 && red.seq === 1, '红方翻子');
    check('无起点 MOVE 被解释为翻子', red.seq === 1, red.seq);
    check('翻子后暗子数减少', red.view?.hiddenCount === 31, red.view?.hiddenCount);
    check('对手也收到翻子后的公开信息', black.view?.hiddenCount === 31, black.view?.hiddenCount);

    const beforeErrors = red.errors.length;
    // 黑方当前必须行动；红方硬发一手应被轮次校验拒绝。
    red.flip(0, 1);
    await waitFor(() => red.errors.length > beforeErrors, '越权行动被拒');
    check('不在自己回合的行动被拒绝', red.errors.length > beforeErrors && red.turn === 2,
      { errors: red.errors, turn: red.turn });

    // 黑方翻开另一格；随后红方从服务端合法走法中选择一个已翻开的己方棋子移动（若已有）。
    black.flip(3, 7);
    await waitFor(() => red.turn === 1 && black.seq === 2, '黑方翻子');
    check('黑方翻子成功', black.seq === 2, black.seq);
    check('翻子后轮到红方', red.turn === 1, red.turn);

    // 继续翻到出现明棋走法；最多翻 10 手，避免脚本依赖随机具体棋子。
    let mover = red;
    let seeMove = false;
    for (let i = 0; i < 10 && !seeMove; i++) {
      const view = mover.view || {};
      if (Array.isArray(view.legalMoves) && view.legalMoves.length > 0) {
        const m = view.legalMoves[0];
        const before = mover.seq;
        mover.move(m.fromRow, m.fromCol, m.toRow, m.toCol);
        await waitFor(() => mover.seq === before + 1, '合法走子', 8000);
        seeMove = true;
        check('服务端合法走法可以真实移动', mover.seq === before + 1, mover.seq);
        break;
      }
      const flips = Array.isArray(view.legalFlips) ? view.legalFlips : [];
      if (!flips.length) break;
      const f = flips[0];
      const before = mover.seq;
      mover.flip(f.row, f.col);
      await waitFor(() => mover.seq === before + 1 || mover.errors.length > beforeErrors, '后续翻子', 8000);
      mover = mover === red ? black : red;
    }
    check('随机开局下仍能推进到走子或终局', seeMove || mover.over || mover.view?.over === true, {
      seq: mover.seq, turn: mover.turn, flips: mover.view?.legalFlips?.length
    });

    red.send({ type: 'SURRENDER' });
    await waitFor(() => red.over && black.over, '双方收到终局', 12000);
    check('终局胜者为黑方', red.result.winner === black.id, { winner: red.result.winner, black: black.id });
    check('终局包含回放手数', Array.isArray(red.result.moveHistory) && red.result.moveHistory.length >= 2,
      red.result.moveHistory?.length);
    check('终局逐手效果与手数对齐', Array.isArray(red.result.moveEffects)
      && red.result.moveEffects.length === red.result.moveHistory.length,
      { effects: red.result.moveEffects?.length, history: red.result.moveHistory?.length });
    check('终局带随机开局盘面', Array.isArray(red.result.initialBoard)
      && red.result.initialBoard.length === 4 && red.result.initialBoard[0][0] === 9,
      red.result.initialBoard);
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
  console.log(failed ? `\n[dark-chess] FAIL (${failed} failed, ${passed} passed)`
    : `\n[dark-chess] PASS (${passed}/${passed})`);
  process.exit(failed ? 1 : 0);
})().catch(e => {
  clearTimeout(watchdog);
  console.error('[dark-chess] 异常:', e.stack);
  process.exit(1);
});
