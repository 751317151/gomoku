import assert from 'node:assert/strict';
const WS = process.env.WS_URL || 'ws://127.0.0.1:19001';
const clients = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate, label) {
  const end = Date.now() + 10000;
  while (Date.now() < end) { if (predicate()) return; await sleep(20); }
  throw Error('Timeout: ' + label);
}
async function connect() {
  const ws = new WebSocket(WS), c = { ws, side: 0, syncs: 0, errors: [] };
  clients.push(c);
  c.send = message => ws.send(JSON.stringify(message));
  ws.onmessage = event => {
    const m = JSON.parse(String(event.data));
    if (m.type === 'ERROR') c.errors.push(m.message);
    if (['WAITING', 'GAME_START', 'GAME_SYNC'].includes(m.type)) c.room = m.roomId;
    if (['GAME_START', 'GAME_SYNC'].includes(m.type)) c.side = m.stone || c.side;
    if (m.sessionId) c.session = m.sessionId;
    if (m.type === 'GAME_OVER') c.over = m;
    if (m.type === 'GAME_SYNC') c.syncs++;
    let d;
    try { d = m.data ? JSON.parse(m.data) : {}; } catch { return; }
    if (d.view || d.roomData?.view) c.view = d.view || d.roomData.view;
    if (d.actionVersion || d.roomData?.actionVersion) c.version = d.actionVersion || d.roomData.actionVersion;
    if (Number.isInteger(m.moveSeq) && m.type === 'GAME_MOVE') c.seq = m.moveSeq;
    if (Number.isInteger(d.moveSeq)) c.seq = d.moveSeq;
  };
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  return c;
}
async function room(type, count) {
  const seats = [];
  for (let i = 0; i < count; i++) {
    const c = await connect(); seats.push(c);
    c.send({ type: 'JOIN', playerName: `version-${type}-${i}`, gameType: type, createRoom: i === 0,
      playerCount: count, roomId: i ? seats[0].room : undefined });
    await until(() => c.room, 'join');
  }
  await until(() => seats.every(c => c.side && c.version && c.view), type + ' start');
  return seats.sort((a, b) => a.side - b.side);
}
function leave(seats) { seats.forEach(c => c.send({ type: 'LEAVE' })); }
try {
  const seats = await room('QUIZ', 2), [a, b] = seats;
  const first = a.version;
  b.send({ type: 'MOVE', action: 2, actionVersion: first });
  await until(() => a.view.players[1].answered, 'second seat first');
  assert.equal(a.version, first);
  a.send({ type: 'MOVE', action: 1, actionVersion: first });
  await until(() => a.view.round === 2 && b.view.round === 2, 'question 2');
  const syncs = a.syncs;
  a.send({ type: 'MOVE', action: 0, actionVersion: first });
  await until(() => a.syncs > syncs, 'stale answer resynchronization');
  assert(a.view.players.every(p => !p.answered), 'stale answer cannot fill question 2');
  assert.equal(a.seq, 2);
  assert(a.view.correctIndex == null);
  console.log('PASS stale quiz answer rejected with private authoritative resynchronization');

  const oldSession = b.session;
  b.ws.close();
  const replacement = await connect();
  replacement.send({ type: 'RECONNECT', sessionId: oldSession });
  await until(() => replacement.side === 2 && replacement.version, 'reconnect version');
  assert.equal(replacement.version, a.version);
  replacement.send({ type: 'MOVE', action: 3, actionVersion: replacement.version });
  await until(() => a.view.players[1].answered, 'reconnected answer');
  assert(a.view.players[1].choice == null);
  console.log('PASS reconnect restores version and keeps opponent answer hidden');
  leave([a, replacement]);

  const cards = await room('CARD', 2), [c, d] = cards;
  const initial = c.version;
  const me = c.view.players.find(p => p.side === c.side);
  const playable = me.hand.find(p => p.cost <= me.mana && !p.needsTarget);
  assert(playable);
  c.send({ type: 'MOVE', action: playable.index, actionVersion: initial });
  c.send({ type: 'MOVE', action: playable.index, actionVersion: initial });
  await until(() => c.syncs > 0, 'card duplicate resync');
  assert.equal(c.seq, 1);
  c.send({ type: 'END_TURN', actionVersion: c.version });
  await until(() => d.view.turn === 2, 'turn 2');
  d.send({ type: 'END_TURN', actionVersion: d.version });
  await until(() => c.view.turn === 1, 'turn 1 again');
  const before = c.syncs;
  c.send({ type: 'END_TURN', actionVersion: initial });
  await until(() => c.syncs > before, 'delayed end turn resync');
  assert.equal(c.view.turn, 1);
  assert.equal(c.seq, 3);
  console.log('PASS card duplicate cannot consume next card; delayed end turn cannot skip next turn');
  leave(cards);
  assert(clients.every(c => c.errors.length === 0));
} finally { clients.forEach(c => c.ws.close()); }
