// 多人再来一局：真实双 WebSocket 验证服务端权威准备状态。
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
let passed = 0;
let failed = 0;
const sockets = new Set();

function check(name, condition, detail) {
  if (condition) {
    passed++;
    console.log('[rematch] PASS ' + name);
  } else {
    failed++;
    console.error('[rematch] FAIL ' + name + (detail ? ' :: ' + detail : ''));
  }
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function waitFor(predicate, timeoutMs = 8000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await sleep(50);
  }
  return null;
}

function parseData(message) {
  try { return JSON.parse(message.data || 'null'); } catch (_) { return null; }
}

function connect(name) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL);
    sockets.add(ws);
    const state = {
      name, ws, roomId: null, playerId: null, stones: [], messages: [],
      errors: [], starts: 0, infos: [], requests: []
    };
    const timeout = setTimeout(() => {
      ws.close();
      reject(new Error('连接超时: ' + name));
    }, 10000);
    ws.onerror = () => {
      clearTimeout(timeout);
      reject(new Error('连接失败: ' + name));
    };
    ws.onmessage = event => {
      let message;
      try {
        message = JSON.parse(event.data.toString());
      } catch (_) {
        return;
      }
      state.messages.push(message);
      if (message.type === 'ERROR') state.errors.push(message.message);
      if (message.type === 'ROOM_INFO') {
        const data = parseData(message);
        if (data) state.infos.push({ message, data });
        if (message.roomId) state.roomId = message.roomId;
      }
      if (message.type === 'RESTART_REQUEST') {
        state.requests.push({ message, data: parseData(message) });
      }
      if (message.type === 'GAME_START') {
        state.starts++;
        state.roomId = message.roomId;
        state.playerId = message.playerId;
        state.stones.push(message.stone);
      }
    };
    ws.onopen = () => {
      clearTimeout(timeout);
      resolve(state);
    };
  });
}

function send(state, message) {
  state.ws.send(JSON.stringify(message));
}

function latestInfo(state, predicate) {
  for (let i = state.infos.length - 1; i >= 0; i--) {
    if (predicate(state.infos[i])) return state.infos[i];
  }
  return null;
}

function latestRequest(state, predicate) {
  for (let i = state.requests.length - 1; i >= 0; i--) {
    if (predicate(state.requests[i])) return state.requests[i];
  }
  return null;
}

try {
  const first = await connect('重开甲');
  const second = await connect('重开乙');

  send(first, {
    type: 'JOIN', playerName: '重开甲', gameType: 'GOMOKU',
    createRoom: true, playerCount: 2, entryMode: 'PRIVATE'
  });
  await waitFor(() => first.roomId);
  check('首人创建双人私房', !!first.roomId, JSON.stringify(first.errors));

  send(second, {
    type: 'JOIN', playerName: '重开乙', gameType: 'GOMOKU',
    roomId: first.roomId, entryMode: 'PRIVATE'
  });
  const bothStarted = await waitFor(() => first.starts >= 1 && second.starts >= 1);
  check('双方收到首局 GAME_START', !!bothStarted,
    JSON.stringify({ first: first.starts, second: second.starts, errors: first.errors.concat(second.errors) }));
  check('首局分配到不同座位', first.stones[0] !== second.stones[0],
    JSON.stringify({ first: first.stones, second: second.stones }));
  check('首局房间一致', first.roomId === second.roomId, JSON.stringify({ first: first.roomId, second: second.roomId }));

  const sample = await waitFor(() => latestInfo(second,
    item => item.data.rematch && item.data.rematch.humanPlayers === 2));
  check('ROOM_INFO 携带两人准备状态', !!sample, JSON.stringify(second.infos.slice(-2)));
  check('首局未在等待重开', !!sample && sample.data.rematch.pending === false && sample.data.rematch.expiresAt == null,
    JSON.stringify(sample && sample.data.rematch));

  send(second, { type: 'SURRENDER' });
  const finished = await waitFor(() => first.messages.find(message => message.type === 'GAME_OVER')
    && second.messages.find(message => message.type === 'GAME_OVER'));
  check('认输后双方收到 GAME_OVER', !!finished);

  send(first, { type: 'RESTART' });
  const aProgress = await waitFor(() => latestRequest(first,
    item => item.data && item.data.pending === true));
  const bProgress = await waitFor(() => latestRequest(second,
    item => item.data && item.data.pending === true));
  check('准备者收到结构化等待状态', !!aProgress, JSON.stringify(first.requests.slice(-2)));
  check('其他真人同步收到等待状态', !!bProgress, JSON.stringify(second.requests.slice(-2)));
  const progress = bProgress && bProgress.data;
  check('准备者进入 ready 名单', !!progress && progress.readyPlayerIds.includes(first.playerId),
    JSON.stringify(progress));
  check('未准备者进入 waiting 名单', !!progress && progress.waitingPlayerIds.includes(second.playerId),
    JSON.stringify(progress));
  check('真人数量为 2', !!progress && progress.humanPlayers === 2, JSON.stringify(progress));
  check('确认窗口接近 60 秒', !!progress
    && progress.expiresAt - Date.now() > 55000 && progress.expiresAt - Date.now() <= 60000,
    JSON.stringify(progress));
  const progressInfo = latestInfo(second, item => item.data.rematch
    && item.data.rematch.pending === true && item.data.rematch.expiresAt === progress.expiresAt);
  check('ROOM_INFO 同步同一重开状态', !!progressInfo, JSON.stringify(second.infos.slice(-3)));

  send(first, { type: 'CANCEL_RESTART' });
  const aCancelled = await waitFor(() => latestRequest(first,
    item => item.data && item.data.pending === false && item.message.message.includes('撤回')));
  const bCancelled = await waitFor(() => latestRequest(second,
    item => item.data && item.data.pending === false && item.message.message.includes('撤回')));
  check('撤回方向双方广播取消', !!aCancelled && !!bCancelled,
    JSON.stringify({ first: first.requests.slice(-2), second: second.requests.slice(-2) }));
  check('撤回后房间仍为 FINISHED 数据', !!bCancelled && bCancelled.data.readyPlayerIds.length === 0
    && bCancelled.data.waitingPlayerIds.length === 2, JSON.stringify(bCancelled && bCancelled.data));

  send(first, { type: 'RESTART' });
  const oneReady = await waitFor(() => latestRequest(second,
    item => item.data && item.data.pending === true
      && item.data.readyPlayerIds.length === 1));
  check('再次发起可恢复等待状态', !!oneReady, JSON.stringify(second.requests.slice(-2)));
  // 服务端用 requestRestart 的幂等性吸收网络重传；连发不应提前开局。
  send(first, { type: 'RESTART' });
  await sleep(150);
  check('重复确认幂等且不会提前开局', first.starts === 1 && second.starts === 1,
    JSON.stringify({ first: first.starts, second: second.starts }));
  send(second, { type: 'RESTART' });

  const restarted = await waitFor(() => first.starts >= 2 && second.starts >= 2);
  check('两人均准备后自动开始第二局', !!restarted,
    JSON.stringify({ first: first.starts, second: second.starts, errors: first.errors.concat(second.errors) }));
  const cleared = await waitFor(() => latestInfo(second,
    item => item.data.rematch && item.data.rematch.pending === false
      && item.data.rematch.readyPlayerIds.length === 0
      && item.data.rematch.waitingPlayerIds.length === 2));
  check('开局后准备状态清空', !!cleared, JSON.stringify(second.infos.slice(-3)));
  check('第二局重新分配有效座位', first.stones[1] !== second.stones[1],
    JSON.stringify({ first: first.stones, second: second.stones }));
  check('两端未收到协议错误', first.errors.length === 0 && second.errors.length === 0,
    JSON.stringify({ first: first.errors, second: second.errors }));
} catch (error) {
  failed++;
  console.error('[rematch] FAIL 冒烟异常 :: ' + (error && error.stack || error));
} finally {
  for (const ws of sockets) {
    if (ws.readyState === WebSocket.OPEN) send({ ws }, { type: 'LEAVE' });
    try { ws.close(); } catch (_) {}
  }
}

console.log(failed === 0
  ? '[rematch] PASS (' + passed + '/' + passed + ')'
  : '[rematch] FAIL (' + failed + ' failed, ' + passed + ' passed)');
process.exitCode = failed === 0 ? 0 : 1;
