import assert from 'node:assert/strict';

const WS = process.env.WS_URL || 'ws://127.0.0.1:19001';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const clients = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let passed = 0;
function check(label, condition) {
  assert.ok(condition, label);
  console.log('[ultimate-ttt] PASS ' + label);
  passed++;
}
async function until(test, label, timeout = 12000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await test()) return;
    await sleep(30);
  }
  throw new Error('等待超时: ' + label);
}
async function connect() {
  const socket = new WebSocket(WS);
  const client = { socket, room: null, session: null, id: null, side: 0, seq: 0,
    view: null, board: Array.from({ length: 9 }, () => Array(9).fill(0)), errors: [], result: null };
  clients.push(client);
  socket.onmessage = event => {
    const message = JSON.parse(String(event.data));
    if (message.roomId) client.room = message.roomId;
    if (message.sessionId) client.session = message.sessionId;
    if (message.playerId && ['WAITING', 'GAME_START', 'GAME_SYNC'].includes(message.type)) client.id = message.playerId;
    if (['GAME_START', 'GAME_SYNC'].includes(message.type)) client.side = message.stone || 0;
    if (message.type === 'GAME_START') {
      client.seq = 0;
      client.result = null;
      client.board = Array.from({ length: 9 }, () => Array(9).fill(0));
    }
    let data = {};
    try { data = JSON.parse(message.data || '{}'); } catch {}
    if (data.view) client.view = data.view;
    if (message.type === 'GAME_SYNC') {
      client.board = data.board;
      client.seq = data.moveSeq;
      client.result = data.gameOver ? { ...JSON.parse(data.gameOver.data), winner: data.gameOver.winner } : null;
    }
    if (message.type === 'GAME_MOVE') {
      client.seq = message.moveSeq;
      for (const effect of data.effects || []) client.board[effect[0]][effect[1]] = effect[2];
    }
    if (message.type === 'GAME_OVER') client.result = { ...data, winner: message.winner, message: message.message };
    if (message.type === 'ERROR') client.errors.push(message.message);
  };
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  client.send = message => socket.send(JSON.stringify(message));
  client.move = async (row, col) => {
    await sleep(350);
    client.send({ type: 'MOVE', row, col, moveSeq: client.seq + 1 });
  };
  return client;
}
async function guestJoin(client, name, room) {
  const response = await fetch(REST + '/api/guest', { method: 'POST' });
  assert.ok(response.ok);
  const identity = await response.json();
  client.send({ type: 'JOIN', gameType: 'ULTIMATE_TTT', playerName: name,
    guestToken: identity.token, playerCount: 2, ...(room ? { roomId: room } : { createRoom: true }) });
}

try {
  const cross = await connect();
  await guestJoin(cross, '九宫甲');
  await until(() => cross.room && cross.session, '建房');
  let circle = await connect();
  await guestJoin(circle, '九宫乙', cross.room);
  await until(() => cross.side === 1 && circle.side === 2 && cross.view && circle.view, '双人开局');
  check('双人房间以终极井字棋引擎开局', cross.room === circle.room && cross.view.kind === 'ULTIMATE_TTT');
  check('首手可选全部 81 格', cross.view.nextBoard === -1 && cross.view.legalMoves.length === 81);

  await cross.move(4, 4);
  await until(() => cross.seq === 1 && circle.seq === 1, '首手同步');
  check('首手指定中央小盘', circle.view.nextBoard === 4 && circle.view.legalMoves.length === 8);
  const invalidCount = circle.errors.length;
  await circle.move(0, 0);
  await until(() => circle.errors.length === invalidCount + 1, '跨区域落子被拒');
  check('非法区域不会增加手数或改变回合', cross.seq === 1 && circle.view.turn === 2);
  await circle.move(3, 3);
  await until(() => cross.seq === 2, '第二手');
  check('小盘内左上格把对手送到大盘左上', cross.view.nextBoard === 0 && cross.view.legalMoves.length === 9);

  const spectator = await connect();
  spectator.send({ type: 'SPECTATE', roomId: cross.room, playerName: '九宫观众' });
  await until(() => spectator.view && spectator.session && spectator.seq === 2, '观战同步');
  check('观众恢复实际盘面与指定小盘', spectator.side === 0 && spectator.board[4][4] === 1
    && spectator.board[3][3] === 2 && spectator.view.nextBoard === 0);

  const session = circle.session;
  const circleId = circle.id;
  circle.socket.close();
  await until(() => circle.socket.readyState === WebSocket.CLOSED, '断开圈方');
  circle = await connect();
  circle.send({ type: 'RECONNECT', sessionId: session });
  await until(() => circle.view && circle.side === 2, '圈方重连');
  check('重连保留身份座位和权威手数', circle.id === circleId && circle.seq === 2);
  check('重连后仍限制正确的小盘', circle.view.nextBoard === 0 && circle.view.legalMoves.length === 9);

  let seed = 20261125;
  let observedClosed = false;
  let observedFree = false;
  while (!cross.result && cross.seq < 81) {
    const actor = cross.view.turn === 1 ? cross : circle;
    const before = cross.seq;
    const legal = actor.view.legalMoves;
    assert.ok(legal.length > 0, '未终局必须有合法行动');
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    const move = legal[seed % legal.length];
    await actor.move(move[0], move[1]);
    await until(() => cross.seq === before + 1 && circle.seq === before + 1 && spectator.seq === before + 1, '逐手广播');
    const flat = cross.view.subBoards.flat();
    observedClosed ||= flat.some(Boolean);
    observedFree ||= !cross.view.over && cross.view.nextBoard === -1;
    assert.deepEqual(circle.view, cross.view);
    assert.deepEqual(spectator.view, cross.view);
    for (const [row, col] of cross.view.legalMoves) assert.equal(cross.view.subBoards[Math.floor(row / 3)][Math.floor(col / 3)], 0);
  }
  await until(() => cross.result && circle.result && spectator.result, '自然终局');
  check('完整合法对局能够自然结束', cross.seq > 9 && cross.seq <= 81 && cross.view.over);
  check('对局实际经历了小盘封闭', observedClosed);
  check('指定已封闭小盘时实际出现自由选择', observedFree);
  check('双方及观众得到一致的终局', cross.result.winner === circle.result.winner && cross.result.winner === spectator.result.winner);
  check('正常终局有大盘规则说明', cross.result.message.includes('小盘') || cross.result.message.includes('平局'));
  check('棋谱和逐手效果完整对齐', cross.result.moveHistory.length === cross.seq
    && cross.result.moveEffects.length === cross.seq);
  check('双人胜负结算为零和 ELO', cross.result.scores.length === 2
    && cross.result.scores.reduce((sum, score) => sum + score.eloChange, 0) === 0);

  const games = await (await fetch(REST + '/api/games?gameType=ULTIMATE_TTT&limit=10')).json();
  const record = games.find(game => game.roomId === cross.room);
  check('自然终局进入持久化记录', !!record);
  const detail = await (await fetch(REST + '/api/games/' + record.id)).json();
  check('持久化棋谱保留实际手数', detail.gameType === 'ULTIMATE_TTT' && detail.moveHistory.length === cross.seq);

  cross.send({ type: 'RESTART' });
  circle.send({ type: 'RESTART' });
  await until(() => !cross.view.over && !circle.view.over && cross.view.legalMoves.length === 81, '双方重开');
  check('再来一局清空小盘归属和指定区域', cross.view.nextBoard === -1
    && cross.view.subBoards.flat().every(value => value === 0) && cross.seq === 0);
  check('除故意非法落子外无协议错误', cross.errors.length === 0 && circle.errors.length === 0 && spectator.errors.length === 0);
  console.log('[ultimate-ttt] PASS ' + passed + '/' + passed);
} catch (error) {
  console.error('[ultimate-ttt] FAIL', error.stack);
  process.exitCode = 1;
} finally {
  for (const client of clients) {
    if (client.socket.readyState === WebSocket.OPEN) client.send({ type: 'LEAVE' });
    client.socket.close();
  }
}
