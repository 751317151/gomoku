import assert from 'node:assert/strict';

const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const WS = process.env.WS_URL || 'ws://127.0.0.1:19001';
const clients = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let passed = 0;
function check(label, condition) { assert.ok(condition, label); passed++; console.log('[uno] PASS ' + label); }
async function until(test, label, timeout = 15000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (test()) return; await sleep(30); }
  throw new Error('等待超时: ' + label);
}
async function connect() {
  const socket = new WebSocket(WS);
  const client = { socket, room: null, session: null, side: 0, seq: 0, version: null, view: null, errors: [], result: null, syncs: 0 };
  clients.push(client);
  socket.onmessage = event => {
    const message = JSON.parse(String(event.data));
    let data = {}; try { data = JSON.parse(message.data || '{}'); } catch {}
    if (message.roomId) client.room = message.roomId;
    if (message.sessionId) client.session = message.sessionId;
    if (['GAME_START', 'GAME_SYNC'].includes(message.type)) client.side = message.stone || 0;
    if (data.view) client.view = data.view;
    if (data.actionVersion) client.version = data.actionVersion;
    if (data.roomData?.actionVersion) client.version = data.roomData.actionVersion;
    if (message.type === 'GAME_START') { client.seq = 0; client.result = null; }
    if (message.type === 'GAME_MOVE') client.seq = message.moveSeq;
    if (message.type === 'GAME_SYNC') { client.seq = data.moveSeq; client.syncs++; }
    if (message.type === 'GAME_OVER') client.result = { ...data, winner: message.winner };
    if (message.type === 'ERROR') client.errors.push(message.message);
  };
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  client.send = message => socket.send(JSON.stringify(message));
  return client;
}
async function join(client, name, room, count = 4) {
  const response = await fetch(REST + '/api/guest', { method: 'POST' }); assert.ok(response.ok);
  const identity = await response.json();
  client.send({ type: 'JOIN', gameType: 'UNO', playerName: name, guestToken: identity.token, playerCount: count,
    ...(room ? { roomId: room } : { createRoom: true }) });
  await until(() => !!client.room, '入座');
}
function publicState(view) {
  return { top: view.top, turn: view.turn, colour: view.activeColor, direction: view.direction, pending: view.pendingDraw,
    uno: view.unoPendingSide, drawn: view.drawnPendingSide, players: view.players.map(({ side, active, handCount }) => ({ side, active, handCount })) };
}
function moveFor(client) {
  const view = client.view, hand = view.players.find(p => p.side === client.side).hand;
  if (view.canCallUno) return { action: 101 };
  if (view.canEndDraw) return { action: 102 };
  const legal = hand.filter(c => c.playable);
  if (!legal.length) { assert.ok(view.canDraw); return { action: 100 }; }
  const card = legal.find(c => c.value === 7) || legal.find(c => c.color < 0) || legal[0];
  if (card.value === 7 && hand.length > 1) {
    const opponents = view.players.filter(p => p.side !== client.side && p.active).sort((a, b) => a.handCount - b.handCount);
    return { action: card.index, targetSide: 2, targetIndex: opponents[0].side };
  }
  if (card.color < 0) return { action: card.index, targetSide: 1, targetIndex: (view.activeColor + 1) % 4 };
  return { action: card.index };
}

try {
  let players = [];
  for (let i = 0; i < 4; i++) { const client = await connect(); players.push(client); await join(client, 'UNO联机' + i, players[0].room); }
  await until(() => players.every(p => p.view?.kind === 'UNO'), '四人开局');
  check('四人开局各自只收到七张私有手牌', players.every(p => p.view.players.filter(s => s.hand).length === 1 && p.view.players.find(s => s.side === p.side).hand.length === 7));
  const spectator = await connect(); spectator.send({ type: 'SPECTATE', roomId: players[0].room, playerName: 'UNO观众' });
  await until(() => !!spectator.view, '观战');
  check('观众没有手牌和可操作按钮', !spectator.view.players.some(p => p.hand) && ['canDraw','canEndDraw','canCallUno','canCatchUno'].every(k => !spectator.view[k]));
  const events = { endDraw: 0, wild: 0, exchange: 0, call: 0, catch: 0, departedExchange: false, reconnected: false };
  let staleChecked = false;
  while (!players[0].result && players[0].seq < 310) {
    let actor = players.find(p => p.side === players[0].view.turn);
    if (!events.reconnected && actor.view.drawnPendingSide === actor.side) {
      const old = actor, side = actor.side, before = publicState(actor.view); old.socket.close();
      await until(() => old.socket.readyState === WebSocket.CLOSED, '真实断开');
      actor = await connect(); actor.send({ type: 'RECONNECT', sessionId: old.session });
      await until(() => actor.view && actor.side === side, '恢复摸牌决定');
      assert.deepEqual(publicState(actor.view), before);
      players[players.indexOf(old)] = actor; events.reconnected = true;
      check('真实断线重连保留摸牌决定与私有手牌', actor.view.canEndDraw && actor.view.players.filter(p => p.hand).length === 1);
    }
    let command = moveFor(actor);
    if (command.targetSide === 2 && !events.departedExchange && players.length === 4) {
      const leaving = players.find(p => p.side !== actor.side && p.side !== command.targetIndex);
      leaving.send({ type: 'LEAVE' });
      players = players.filter(p => p !== leaving);
      await until(() => actor.view.players.find(p => p.side === leaving.side).active === false, '离场同步');
      const before = publicState(actor.view), seq = actor.seq, errors = actor.errors.length;
      await sleep(350); actor.send({ type: 'MOVE', action: command.action, targetSide: 2, targetIndex: leaving.side });
      await until(() => actor.errors.length > errors, '拒绝离场目标'); actor.errors.pop();
      assert.deepEqual(publicState(actor.view), before);
      check('真实请求不能换走离场空手牌且不推进序号', actor.seq === seq && !actor.result);
      events.departedExchange = true; command = moveFor(actor);
    }
    if (actor.view.unoPendingSide && events.catch === 0) {
      actor = players.find(p => p.side !== actor.view.unoPendingSide); command = { action: 103 }; events.catch++;
    }
    const seq = actor.seq, version = actor.version;
    if (command.action === 102) events.endDraw++;
    if (command.action === 101) events.call++;
    if (command.targetSide === 1) events.wild++;
    if (command.targetSide === 2) events.exchange++;
    await sleep(350); actor.send({ type: 'MOVE', ...command, actionVersion: version, moveSeq: seq });
    await until(() => players.every(p => p.seq === seq + 1) && spectator.seq === seq + 1, '逐手同步');
    for (const client of [...players, spectator]) assert.deepEqual(publicState(client.view), publicState(players[0].view));
    if (!staleChecked && command.action === 102 && !actor.result) {
      const syncs = actor.syncs; await sleep(350); actor.send({ type: 'MOVE', ...command, actionVersion: version, moveSeq: seq });
      await until(() => actor.syncs > syncs, '旧版本回传同步');
      check('结束摸牌推进版本，重复提交不会再走一手', actor.seq === seq + 1); staleChecked = true;
    }
  }
  await until(() => players.every(p => p.result) && spectator.result, '自然终局');
  check('四席对局自然结束并且观众看到同一结果', players[0].view.over && players.every(p => p.result.winner === spectator.result.winner));
  const history = players[0].result.moveHistory;
  check('摸牌结束、喊牌与抓牌次数和真实提交完全相符', [102,101,103].every((action, i) => history.filter(m => m[1] === action).length === [events.endDraw,events.call,events.catch][i]));
  check('所有行动均进入等长棋谱与效果序列', history.length === players[0].seq && players[0].result.moveEffects.length === history.length);
  check('新出牌记录保留换手目标和最后一张标记', history.filter(m => m[1] < 100).every(m => m.length === 6)
    && (players[0].result.winner === 'draw' ? history.length >= 300 : history.at(-1)[5] === 1));
  const records = await (await fetch(REST + '/api/games?gameType=UNO&limit=10')).json();
  const record = records.find(r => r.roomId === players[0].room); assert.ok(record);
  const detail = await (await fetch(REST + '/api/games/' + record.id)).json();
  check('持久化保留四席参与者与完整新格式行动', detail.participants.length === 4 && JSON.stringify(detail.moveHistory) === JSON.stringify(history));
  check('多人局不计双人ELO', detail.participants.every(p => p.eloChange === 0));
  for (const client of [...players,spectator]) client.send({ type: 'LEAVE' });
  const human = await connect(); await join(human, 'UNO八席人机', null, 8);
  for (let i = 0; i < 7; i++) { human.send({ type: 'ADD_AI', data: '6' }); await sleep(400); }
  await until(() => human.view?.players?.length === 8, '八席人机开局');
  check('真人加七台困难电脑按八席开局且手牌仍按视角裁剪',human.view.players.filter(p=>p.hand).length===1);
  const deadline = Date.now() + 360000;
  while (!human.result && Date.now() < deadline) {
    if (human.view.turn === human.side) {
      const seq = human.seq; await sleep(350);
      human.send({ type: 'MOVE', ...moveFor(human), actionVersion: human.version, moveSeq: seq });
      await until(() => human.seq > seq || human.result, '人机局真人行动');
    } else await sleep(60);
  }
  check('八席困难人机完整自然终局而非等待超时',human.result && human.view.over
    && (human.result.winner==='draw' ? human.result.moveHistory.length>=300 : human.result.moveHistory.at(-1)[5]===1));
  check('八席完整棋谱与逐手效果同样对齐',human.result.moveHistory.length===human.seq && human.result.moveEffects.length===human.seq);
  check('全程没有非预期协议错误', clients.every(c => c.errors.length === 0));
  console.log('[uno] observed ' + JSON.stringify(events));
  console.log('[uno] PASS ' + passed + '/' + passed);
} catch (error) { console.error('[uno] FAIL', error.stack); process.exitCode = 1; }
finally { for (const client of clients) { if (client.socket.readyState === WebSocket.OPEN) client.send({ type: 'LEAVE' }); client.socket.close(); } }
