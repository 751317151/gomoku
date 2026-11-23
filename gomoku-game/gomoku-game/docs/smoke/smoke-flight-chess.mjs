// 飞行棋端到端冒烟：真实 4 人 WebSocket 对局。
//
// 覆盖：
// 1) 4 名玩家进入同一间 4 人房，座位 1–4 各不相同；
// 2) 开局 view 带首骰、阶段、4 名玩家位置与本人合法走法；
// 3) 服务端权威骰子下的「合法走子」与「无棋可走显式跳过」都能推进手数；
// 4) 4 人局不进入双人 ELO，结算 scores 含全部 4 席且 eloChange=0；
// 5) 终局棋谱与逐手效果等长，跳过手以 pieceIndex=-1 入谱，可供复盘；
// 6) 多人淘汰在独立房间验证，避免自然终局与人工退出互相竞争。
const URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
let passed = 0, failed = 0;
const sleep = ms => new Promise(r => setTimeout(r, ms));

function check(name, cond, detail) {
  if (cond) { passed++; console.log('[flight] PASS ' + name); }
  else { failed++; console.error('[flight] FAIL ' + name + (detail ? ' :: ' + JSON.stringify(detail) : '')); }
}

function parseData(m) {
  try { return JSON.parse(m.data || '{}'); } catch { return {}; }
}

function client(name, roomId) {
  const c = {
    name, ws: new WebSocket(URL), id: null, stone: 0, roomId: null,
    seq: 0, view: null, over: false, result: null, errors: [],
    sawSkip: false, sawMove: false, actingKey: null, autoPlay: true,
    statusMessages: []
  };
  c.ws.onopen = () => {
    const join = { type: 'JOIN', playerName: name, gameType: 'FLIGHT_CHESS', playerCount: 4 };
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
      const d = parseData(m);
      if (d.view) c.view = d.view;
      console.log(`[${name}] 开局 stone=${c.stone} room=${c.roomId} dice=${c.view?.dice}`);
      c.act();
    } else if (m.type === 'GAME_MOVE') {
      const d = parseData(m);
      if (typeof m.moveSeq === 'number') c.seq = m.moveSeq;
      if (d.view) c.view = d.view;
      if (d.pass === true) c.sawSkip = true;
      else if (typeof d.effects !== 'undefined') c.sawMove = true;
      c.act();
    } else if (m.type === 'GAME_OVER') {
      c.over = true;
      c.result = parseData(m);
      c.result.winner = m.winner;
      c.result.message = m.message;
      console.log(`[${name}] 终局: ${m.message} winner=${m.winner}`);
    } else if (m.type === 'PLAYER_STATUS') {
      c.statusMessages.push(m.message || '');
    } else if (m.type === 'ERROR') {
      c.errors.push(m.message);
      console.log(`[${name}] ERROR ${m.message} data=${m.data}`);
    }
  };
  c.send = obj => c.ws.send(JSON.stringify(obj));
  c.act = () => {
    if (!c.autoPlay) return;
    const v = c.view;
    if (!v || c.over || v.over || v.turn !== c.stone) return;
    const key = v.turn + ':' + v.dice + ':' + v.phase + ':' + c.seq;
    if (c.actingKey === key) return;
    c.actingKey = key;
    if (v.phase === 'SKIP' && v.canSkip === true) {
      c.sawSkip = true;
      c.send({ type: 'PASS' });
      return;
    }
    if (v.phase !== 'MOVE' || !Array.isArray(v.legalMoves) || v.legalMoves.length === 0) return;
    const move = v.legalMoves[0];
    c.sawMove = true;
    c.send({
      type: 'MOVE', fromRow: move.fromRow, fromCol: move.fromCol,
      row: move.toRow, col: move.toCol, moveSeq: c.seq + 1
    });
  };
  c.close = () => { try { c.ws.close(); } catch {} };
  return c;
}

async function waitFor(fn, label, timeout = 15000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await fn()) return true; await sleep(80); }
  throw new Error('等待超时: ' + label);
}

const watchdog = setTimeout(() => { console.error('[flight] 超时'); process.exit(1); }, 90000);

(async () => {
  console.log('== 飞行棋 4 人对局 ==');
  console.log('   WS=' + URL);
  const clients = [client('飞行甲', null)];
  try {
    await waitFor(() => clients[0].roomId, '甲建房', 10000);
    for (let i = 1; i < 4; i++) clients.push(client(['飞行乙', '飞行丙', '飞行丁'][i - 1], clients[0].roomId));
    await waitFor(() => clients.every(c => c.stone > 0), '四人开局', 15000);

    check('四名玩家进入同一房间', clients.every(c => c.roomId === clients[0].roomId),
      clients.map(c => c.roomId));
    check('座位号覆盖 1–4', new Set(clients.map(c => c.stone)).size === 4,
      clients.map(c => c.stone));
    const first = clients.find(c => c.stone === 1);
    check('开局下发首骰与阶段', first.view?.dice >= 1 && first.view?.dice <= 6
      && ['MOVE', 'SKIP', 'ROLLING'].includes(first.view?.phase), first.view);
    check('开局包含 4 名玩家完整位置', Array.isArray(first.view?.players)
      && first.view.players.length === 4
      && first.view.players.every(p => Array.isArray(p.positions) && p.positions.length === 4), first.view?.players);

    // 双方轮流行动，直到至少见过一次合法移动和一次无棋可走跳过。
    await waitFor(() => clients.some(c => c.sawMove) && clients.some(c => c.sawSkip),
      '观察到合法移动与跳过', 30000);
    await waitFor(() => clients[0].seq >= 4, '手数推进', 15000);
    check('真实对局出现无棋可走跳过', clients.some(c => c.sawSkip));
    check('真实对局出现合法移动', clients.some(c => c.sawMove));
    // 广播逐条出站，最后一条可能仍在别的连接队列里；等待收敛而不是按某一瞬间判失败。
    await waitFor(() => new Set(clients.map(c => c.seq)).size === 1, '四手数收敛', 5000);
    check('四人各自收到同一手数', new Set(clients.map(c => c.seq)).size === 1,
      clients.map(c => ({ name: c.name, seq: c.seq })));
    check('未产生协议错误', clients.every(c => c.errors.length === 0),
      clients.map(c => ({ name: c.name, errors: c.errors })));

    await waitFor(() => clients.every(c => c.over), '自然终局', 60000);

    const result = clients[0].result;
    check('自然终局有获胜者', !!result?.winner, result);
    check('4 人结算包含全部座位', Array.isArray(result.scores) && result.scores.length === 4,
      result.scores);
    check('4 人局不进入双人 ELO', (result.scores || []).every(s => s.eloChange === 0),
      (result.scores || []).map(s => s.eloChange));
    check('终局棋谱与逐手效果等长',
      Array.isArray(result.moveHistory) && Array.isArray(result.moveEffects)
      && result.moveHistory.length === result.moveEffects.length,
      { history: result.moveHistory?.length, effects: result.moveEffects?.length });
    check('棋谱包含跳过记录', (result.moveHistory || []).some(mv => mv[1] === -1),
      result.moveHistory);
    check('棋谱包含正常移动', (result.moveHistory || []).some(mv => mv[1] >= 0),
      result.moveHistory);
    check('终局观战数据未泄漏异常字段', !String(result.message || '').includes('null'),
      result.message);
  } finally {
    clients.forEach(c => c.close());
  }

  await sleep(120);
  clients.length = 0;

  console.log('== 飞行棋多人淘汰 ==');
  try {
    clients.push(client('淘汰甲', null));
    await waitFor(() => clients[0].roomId, '淘汰房建房', 10000);
    for (let i = 1; i < 4; i++) clients.push(client(['淘汰乙', '淘汰丙', '淘汰丁'][i - 1], clients[0].roomId));
    await waitFor(() => clients.every(c => c.stone > 0), '淘汰房四人开局', 15000);
    clients.forEach(c => { c.autoPlay = false; });

    const quitter = clients.find(c => c.stone === 2);
    const third = clients.find(c => c.stone === 3);
    const fourth = clients.find(c => c.stone === 4);
    quitter.send({ type: 'SURRENDER' });
    await sleep(500);
    check('一人退出后其余三人继续对局', clients.every(c => !c.over));
    check('退出广播对玩家可见', clients.every(c =>
      c.statusMessages.some(text => text.includes(quitter.name) && text.includes('退出'))),
      clients.map(c => ({ name: c.name, status: c.statusMessages })));
    third.send({ type: 'SURRENDER' });
    fourth.send({ type: 'SURRENDER' });
    await waitFor(() => clients.every(c => c.over), '淘汰房收到终局', 15000);

    const result = clients[0].result;
    check('最后留在场上的玩家获胜', result.winner === clients[0].id,
      { winner: result.winner, expected: clients[0].id });
    check('淘汰结算包含全部座位', Array.isArray(result.scores) && result.scores.length === 4,
      result.scores);
    check('淘汰局不进入双人 ELO', (result.scores || []).every(s => s.eloChange === 0),
      (result.scores || []).map(s => s.eloChange));
    check('淘汰局结束原因正确', result.finishReason === 'SURRENDER', result.finishReason);
    check('淘汰局棋谱与效果对齐',
      Array.isArray(result.moveHistory) && Array.isArray(result.moveEffects)
      && result.moveHistory.length === result.moveEffects.length,
      { history: result.moveHistory?.length, effects: result.moveEffects?.length });
    check('终局棋谱与逐手效果等长',
      Array.isArray(result.moveHistory) && Array.isArray(result.moveEffects)
      && result.moveHistory.length === result.moveEffects.length,
      { history: result.moveHistory?.length, effects: result.moveEffects?.length });
    check('棋谱包含跳过记录', (result.moveHistory || []).some(mv => mv[1] === -1),
      result.moveHistory);
    check('棋谱包含正常移动', (result.moveHistory || []).some(mv => mv[1] >= 0),
      result.moveHistory);
    check('终局观战数据未泄漏异常字段', !String(result.message || '').includes('null'),
      result.message);
  } finally {
    clients.forEach(c => c.close());
  }

  clearTimeout(watchdog);
  console.log(failed ? `\n[flight] FAIL (${failed} failed, ${passed} passed)`
    : `\n[flight] PASS (${passed}/${passed})`);
  process.exit(failed ? 1 : 0);
})().catch(e => {
  clearTimeout(watchdog);
  console.error('[flight] 异常:', e.message);
  process.exit(1);
});
