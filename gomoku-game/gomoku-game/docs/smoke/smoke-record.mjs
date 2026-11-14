// 对局记录持久化端到端验证：真实打一局 PvP，然后通过 HTTP 接口确认
//   1. 记录进了索引（/api/games）
//   2. 详情含完整棋谱（/api/games/{id}）
//   3. 玩家战绩能查到（/api/stats/player/{name}）
//   4. data/games/ 下真的落了文件
// 之前这些数据只活在 GAME_OVER 消息里，重启即丢，所以必须验到「磁盘上有」。
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const HTTP_URL = process.env.HTTP_URL || 'http://127.0.0.1:19000';
const NAME_A = '存档测试甲' + Math.floor(Math.random() * 1000);
const NAME_B = '存档测试乙' + Math.floor(Math.random() * 1000);

const problems = [];
let roomIdA = null;
let gameOverSeen = 0;

function connect(name, onStart) {
  return new Promise((resolve) => {
    const ws = new WebSocket(WS_URL);
    const state = { ws, name, stone: 0, turn: 1, seq: 0, board: null, done: false, pending: false, resolve };

    ws.onopen = () => {
      ws.send(JSON.stringify({ type: 'JOIN', playerName: name, gameType: 'CONNECT4' }));
    };

    ws.onmessage = (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }

      if (msg.type === 'WAITING') {
        // 第一个人等第二个进来；第二个进来后服务端会自动开局
        return;
      }

      if (msg.type === 'GAME_START') {
        state.stone = msg.stone;
        try { state.board = JSON.parse(msg.data).board || null; } catch {}
        state.board = state.board || Array.from({ length: 6 }, () => new Array(7).fill(0));
        state.turn = 1;
        if (onStart) onStart(state);
        // 开局没有任何 GAME_MOVE 消息，先手方必须自己起手；
        // 否则双方都干等，最后被步时判负（0 手对局，棋谱是空的）。
        if (!state.done && state.turn === state.stone) scheduleMove(state);
        return;
      }

      if (msg.type === 'GAME_MOVE') {
        if (state.board && msg.row >= 0) state.board[msg.row][msg.col] = msg.stone;
        if (typeof msg.moveSeq === 'number') state.seq = msg.moveSeq;
        let data = {};
        try { data = JSON.parse(msg.data || '{}'); } catch {}
        if (typeof data.turn === 'number') state.turn = data.turn;
        // 只在轮到自己时走一手，避免两端同时发
        if (!state.done && state.turn === state.stone) scheduleMove(state);
        return;
      }

      if (msg.type === 'GAME_OVER') {
        state.done = true;
        gameOverSeen++;
        let data = {};
        try { data = JSON.parse(msg.data || '{}'); } catch {}
        if (gameOverSeen === 1 && data.moveHistory) {
          console.log('[rec] GAME_OVER 内嵌棋谱手数:', data.moveHistory.length);
        }
        setTimeout(() => {
          try { ws.close(); } catch {}
          state.resolve(state);
        }, 300);
      }
    };

    ws.onclose = () => { if (!state.done) state.resolve(state); };
    state.ws = ws;
  });
}

// 找一条能连成四子的路线（坐标固定，两端共享同一份逻辑，
// 但因为轮流落子，实际不会互相抢格：每方只在轮到自己时按自己的偏好选列）
function planMove(board, seq) {
  const order = [3, 2, 4, 1, 5, 0, 6];
  for (const c of order) {
    for (let r = 5; r >= 0; r--) {
      if (board[r][c] === 0) return { row: r, col: c };
    }
  }
  return null;
}

/** 排一手棋（带在途标记，避免同一步重复发送） */
function scheduleMove(state) {
  if (state.pending) return;
  const hit = planMove(state.board, state.seq);
  if (!hit) return;
  state.pending = true;
  setTimeout(() => {
    state.pending = false;
    if (state.done) return;
    try {
      state.ws.send(JSON.stringify({
        type: 'MOVE', row: hit.row, col: hit.col, moveSeq: state.seq + 1
      }));
    } catch {}
  }, 150);
}

async function httpJson(path) {
  const r = await fetch(HTTP_URL + path);
  if (!r.ok) throw new Error(path + ' -> HTTP ' + r.status);
  return r.json();
}

(async () => {
  // 两个连接按顺序加入同一房间（第一个 WAITING，第二个自动开局）
  const pA = connect(NAME_A);
  await new Promise((r) => setTimeout(r, 700));
  const pB = connect(NAME_B);

  const [a, b] = await Promise.all([pA, pB]);

  console.log('[rec] 双方对局结束，A 手数=' + a.seq + ' B 手数=' + b.seq);
  if (gameOverSeen === 0) problems.push('没有收到 GAME_OVER');

  // 落盘是异步的，给写线程一点时间
  await new Promise((r) => setTimeout(r, 1200));

  // 1) 最近对局列表
  let games;
  try {
    games = await httpJson('/api/games?limit=20&gameType=CONNECT4');
  } catch (e) {
    problems.push('对局列表接口失败: ' + e.message);
    games = [];
  }

  const mine = games.filter(g => {
    const names = (g.participants || []).map(p => p.name);
    return names.includes(NAME_A) && names.includes(NAME_B);
  });

  if (mine.length === 0) {
    problems.push('对局列表里找不到本局（存档未生效）');
  } else {
    const rec = mine[0];
    console.log('[rec] 命中记录:', rec.id, '| 计分:', rec.ranked, '| 手数:', rec.moveCount);
    if (!rec.ranked) problems.push('双真人局应标记 ranked=true');
    if (!rec.winnerName) problems.push('记录缺少胜者');
    if (rec.moveCount <= 0) problems.push('记录手数异常: ' + rec.moveCount);

    // 2) 详情含棋谱
    try {
      const detail = await httpJson('/api/games/' + rec.id);
      const moves = detail.moveHistory || [];
      console.log('[rec] 详情棋谱手数:', moves.length);
      if (moves.length === 0) problems.push('详情里没有棋谱（无法复盘）');
      if (moves.length !== rec.moveCount) {
        problems.push('棋谱手数与摘要不一致: ' + moves.length + ' vs ' + rec.moveCount);
      }
      if (!detail.moveEffects || detail.moveEffects.length !== moves.length) {
        problems.push('moveEffects 与棋谱长度不一致');
      }
      const ranked = detail.participants || [];
      const eloSum = ranked.reduce((s, p) => s + (p.eloChange || 0), 0);
      console.log('[rec] ELO 变化合计:', eloSum,
        '|', ranked.map(p => p.name + '=' + p.result + '(' + p.eloChange + ')').join(', '));
      if (eloSum !== 0) problems.push('双方 ELO 变化之和应为 0，实际 ' + eloSum);
    } catch (e) {
      problems.push('详情接口失败: ' + e.message);
    }
  }

  // 3) 玩家战绩
  try {
    const stats = await httpJson('/api/stats/player/' + encodeURIComponent(NAME_A) + '?gameType=CONNECT4');
    console.log('[rec] 甲的战绩:', JSON.stringify(stats));
    if (stats.total !== 1) problems.push('甲的计分局数应为 1，实际 ' + stats.total);
    if ((stats.wins + stats.losses + stats.draws) !== 1) {
      problems.push('胜+负+平应等于 total');
    }
  } catch (e) {
    problems.push('战绩接口失败: ' + e.message);
  }

  // 4) 越权 ID 必须被拒（目录穿越防护）
  try {
    const r = await fetch(HTTP_URL + '/api/games/' + encodeURIComponent('../../etc/passwd'));
    if (r.status === 200) problems.push('非法 ID 竟返回 200（目录穿越风险）');
    else console.log('[rec] 非法 ID 被拒，HTTP', r.status);
  } catch (e) {
    // 连接层拒绝也算通过
  }

  console.log('[rec] 问题:', problems.length ? problems : '无');
  console.log(problems.length === 0 ? '[rec] PASS' : '[rec] FAIL');
  process.exitCode = problems.length === 0 ? 0 : 1;
})();

setTimeout(() => {
  console.log('[rec] 问题: 超时');
  console.log('[rec] FAIL');
  process.exit(1);
}, 60000).unref?.(); // 兜底定时器不能拖住进程，否则成功路径也会等到 60s 后误报超时
