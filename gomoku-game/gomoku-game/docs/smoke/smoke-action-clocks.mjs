// 真实 WebSocket：同时行动独立计时、提交停表、跨轮重置与后手先答。
import assert from 'node:assert/strict';
const WS = process.env.WS_URL || 'ws://127.0.0.1:19001';
const clients = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate, label, timeout = 10000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (predicate()) return; await sleep(25); }
  throw new Error('Timeout: ' + label);
}
async function connect() {
  const ws = new WebSocket(WS);
  const c = { ws, side: 0, room: null, view: null, timers: null, errors: [] };
  c.send = message => ws.send(JSON.stringify(message));
  clients.push(c);
  ws.onmessage = event => {
    const message = JSON.parse(String(event.data));
    if (message.type === 'ERROR') c.errors.push(message.message);
    if (['WAITING', 'GAME_START', 'GAME_SYNC'].includes(message.type)) c.room = message.roomId;
    if (['GAME_START', 'GAME_SYNC'].includes(message.type)) c.side = message.stone || c.side;
    const data = message.data ? JSON.parse(message.data) : {};
    if (data.view || data.roomData?.view) c.view = data.view || data.roomData.view;
    if (message.type === 'TIMER_SYNC') c.timers = data;
    else if (data.timers) c.timers = data.timers;
  };
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  return c;
}
async function room(gameType, count) {
  const seats = [];
  for (let i = 0; i < count; i++) {
    const c = await connect(); seats.push(c);
    c.send({ type: 'JOIN', gameType, playerName: `clock-${gameType}-${i}`,
      createRoom: i === 0, playerCount: count, roomId: i ? seats[0].room : undefined });
    await until(() => c.room, 'join ' + i);
  }
  await until(() => seats.every(c => c.side > 0 && c.view), gameType + ' start');
  return seats.sort((a, b) => a.side - b.side);
}
function leave(seats) { seats.forEach(c => c.send({ type: 'LEAVE' })); }
try {
  const seats = await room('HIGH_CARD', 3);
  const [a, b, c] = seats;
  await until(() => a.timers?.seatSteps.every(seconds => seconds <= 28), 'all seats tick');
  const start = a.timers;
  assert.deepEqual(start.seatSteps, [start.seatSteps[0], start.seatSteps[0], start.seatSteps[0]]);
  assert.deepEqual(start.awaitingSides, [1, 2, 3]);
  assert(start.seatTotals.every(seconds => seconds < 300));
  c.send({ type: 'MOVE', action: 10 });
  await until(() => a.view.players[2].answered && !a.timers.awaitingSides.includes(3), 'seat 3 submits');
  const submitted = a.timers;
  assert(submitted.seatSteps[0] <= start.seatSteps[0], 'submission cannot extend other clocks');
  await until(() => a.timers.seatSteps[0] <= submitted.seatSteps[0] - 2, 'others keep ticking');
  assert.equal(a.timers.seatSteps[2], submitted.seatSteps[2]);
  assert.equal(a.timers.seatTotals[2], submitted.seatTotals[2]);
  assert.equal(a.view.players[2].choice, undefined, 'opponent choice stays hidden');
  console.log('PASS all pending seats count down; submitted seat stops without extending others');

  a.send({ type: 'MOVE', action: 9 });
  await until(() => b.view.players[0].answered, 'seat 1 submits');
  b.send({ type: 'MOVE', action: 8 });
  await until(() => a.view.round === 2, 'next round');
  assert.deepEqual(a.timers.seatSteps, [30, 30, 30]);
  assert(a.timers.seatTotals.every(seconds => seconds < 300), 'total budget never resets');
  assert.deepEqual(a.timers.awaitingSides, [1, 2, 3]);
  console.log('PASS whole-round completion resets step clocks and preserves total budgets');
  leave(seats);

  const [first, second] = await room('QUIZ', 2);
  second.send({ type: 'MOVE', action: 2 });
  await until(() => first.view.players[1].answered, 'second seat answers first');
  assert(first.view.players[1].choice == null);
  assert(first.view.correctIndex == null);
  assert.deepEqual(first.timers.awaitingSides, [1]);
  first.send({ type: 'MOVE', action: 1 });
  await until(() => second.view.round === 2, 'question 2');
  assert.equal(second.view.revealed, false);
  assert(second.view.correctIndex == null);
  assert.equal(typeof second.view.lastCorrect, 'number');
  assert.deepEqual(second.view.lastChoices, [1, 2]);
  assert.deepEqual(second.timers.seatSteps, [25, 25]);
  second.send({ type: 'MOVE', action: 0 });
  await until(() => first.view.players[1].answered, 'question 2 accepts second seat first');
  assert.equal(first.view.round, 2);
  console.log('PASS quiz accepts either seat first and separates previous results from new hidden answers');
  leave([first, second]);
  assert(clients.every(client => client.errors.length === 0), 'no protocol errors');
} finally {
  for (const client of clients) client.ws.close();
}
