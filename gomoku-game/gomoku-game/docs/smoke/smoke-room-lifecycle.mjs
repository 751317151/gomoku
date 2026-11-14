import assert from 'node:assert/strict';

const WS = process.env.WS_URL || 'ws://127.0.0.1:19001';
const clients = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(check, label) {
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) { if (check()) return; await sleep(25); }
  throw new Error('Timeout: ' + label);
}
async function connect() {
  const ws = new WebSocket(WS);
  const client = {ws, messages: [], room: null, session: null, side: 0};
  clients.push(client);
  ws.onmessage = event => {
    const message = JSON.parse(String(event.data));
    client.messages.push(message);
    if (message.roomId) client.room = message.roomId;
    if (message.sessionId) client.session = message.sessionId;
    if (message.type === 'GAME_START' || message.type === 'GAME_SYNC') client.side = message.stone || 0;
  };
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  client.send = message => ws.send(JSON.stringify(message));
  return client;
}
function lastSync(client) { return client.messages.filter(message => message.type === 'GAME_SYNC').at(-1); }

try {
  const a = await connect(), b = await connect();
  a.send({type:'JOIN', gameType:'GO9', playerName:'生命周期甲', createRoom:true});
  await until(() => a.room && a.session, 'create room');
  b.send({type:'JOIN', gameType:'GO9', playerName:'生命周期乙', roomId:a.room});
  await until(() => a.side && b.side, 'two player start');
  a.send({type:'MOVE', row:0, col:0, moveSeq:1});
  await until(() => b.messages.some(message => message.type === 'GAME_MOVE'), 'move accepted');
  const spectator = await connect();
  spectator.send({type:'SPECTATE', roomId:a.room.toLowerCase().replace('room-', ''), playerName:'刷新观众'});
  await until(() => spectator.session && lastSync(spectator), 'spectator session');
  assert.equal(spectator.side, 0);
  const observerSession = spectator.session;
  spectator.ws.close();
  await until(() => spectator.ws.readyState === WebSocket.CLOSED, 'spectator disconnected');
  const observer = await connect();
  observer.send({type:'RECONNECT', sessionId:observerSession});
  await until(() => lastSync(observer), 'spectator reconnect');
  assert.equal(observer.side, 0);
  let state = JSON.parse(lastSync(observer).data);
  assert.equal(state.moveSeq, 1); assert.equal(state.moveCount, 1);
  assert.equal(state.roomData.spectatorCount, 1); assert(state.timers.seatTotals.length === 2);
  console.log('PASS spectator refresh keeps one seat and restores move sequence/timers');

  const aSession = a.session;
  a.ws.close(); await until(() => a.ws.readyState === WebSocket.CLOSED, 'player disconnected');
  const resumed = await connect(); resumed.send({type:'RECONNECT', sessionId:aSession});
  await until(() => lastSync(resumed), 'player reconnect');
  state = JSON.parse(lastSync(resumed).data);
  assert.equal(state.moveSeq, 1); assert.equal(resumed.side, 1);
  await sleep(350); b.send({type:'PASS'});
  await until(() => resumed.messages.some(message => message.type === 'GAME_MOVE'), 'pass reaches resumed player');
  resumed.send({type:'PASS'});
  await until(() => resumed.messages.some(message => message.type === 'GAME_OVER'), 'finished game');
  resumed.ws.close(); await until(() => resumed.ws.readyState === WebSocket.CLOSED, 'finished player disconnected');
  const finished = await connect(); finished.send({type:'RECONNECT', sessionId:aSession});
  await until(() => lastSync(finished), 'finished reconnect');
  state = JSON.parse(lastSync(finished).data);
  assert.equal(state.state, 'FINISHED'); assert.equal(state.moveSeq, 3);
  assert.equal(state.gameOver.type, 'GAME_OVER');
  assert(JSON.parse(state.gameOver.data).winningSides.length > 0);
  console.log('PASS finished reconnect restores outcome and full authoritative move count');
} finally {
  for (const client of clients) {
    if (client.ws.readyState === WebSocket.OPEN) client.send({type:'LEAVE'});
    client.ws.close();
  }
}
