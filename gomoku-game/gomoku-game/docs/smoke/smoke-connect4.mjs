// 四子棋端到端冒烟：真实 WebSocket 对局（人类 + AI）
// 落点按重力实时计算，避免上次「固定坐标被服务端拒绝」的测试脚本问题
const URL = process.env.WS_URL || 'ws://127.0.0.1:19001';

const state = {
  board: Array.from({ length: 6 }, () => new Array(7).fill(0)),
  myStone: 0,
  turn: 1,
  over: false,
  result: null,
  moves: 0,
  errors: [],
  gameOverPayload: null,
};

function dropRow(board, col) {
  for (let r = board.length - 1; r >= 0; r--) {
    if (board[r][col] === 0) return r;
  }
  return -1;
}

function log(...a) { console.log('[smoke]', ...a); }

const ws = new WebSocket(URL);

ws.onopen = () => {
  log('已连接', URL);
  ws.send(JSON.stringify({ type: 'JOIN', playerName: '冒烟测试', gameType: 'CONNECT4' }));
};

ws.onerror = (e) => {
  console.error('[smoke] ws error', e.message || e);
  process.exit(1);
};

ws.onmessage = (ev) => {
  let msg;
  try { msg = JSON.parse(typeof ev.data === 'string' ? ev.data : ''); } catch { return; }

  switch (msg.type) {
    case 'WAITING':
      log('房间已创建', msg.roomId, '→ 添加电脑');
      ws.send(JSON.stringify({ type: 'ADD_AI', data: '4' }));
      break;

    case 'GAME_START':
      state.myStone = msg.stone;
      state.turn = 1;
      log('开局 gameType=', msg.gameType, 'stone=', msg.stone, '时限已下发');
      // 人类执黑先手（room 内第 1 位玩家按轮次分配黑方）
      maybeMove();
      break;

    case 'GAME_MOVE': {
      const r = msg.row, c = msg.col;
      if (r >= 0) {
        if (state.board[r][c] !== 0) {
          state.errors.push(`位置冲突 ${r},${c} 已有子`);
        }
        state.board[r][c] = msg.stone;
        state.moves++;
        // 校验重力：该格下方必须已是实的（除非在底行）
        if (r < 5 && state.board[r + 1][c] === 0) {
          state.errors.push(`重力违规：(${r},${c}) 下方为空`);
        }
      }
      let data = {};
      try { data = JSON.parse(msg.data || '{}'); } catch {}
      if (typeof data.turn === 'number') state.turn = data.turn;
      // 以服务端回传的 seq 为准，避免 seq 推断错误被静默拒绝
      if (typeof msg.moveSeq === 'number') state.serverSeq = msg.moveSeq;
      maybeMove();
      break;
    }

    case 'GAME_OVER':
      state.over = true;
      state.result = { winner: msg.winner, message: msg.message };
      try { state.gameOverPayload = JSON.parse(msg.data || '{}'); } catch {}
      finish();
      break;

    case 'ERROR':
      state.errors.push('server: ' + msg.message);
      if (msg.message && msg.message.includes('无效落子')) {
        // 落子被拒 → 立即中止，避免无限重试
        finish(1);
      }
      break;

    case 'ROOM_INFO':
    case 'TIMER_SYNC':
    case 'PLAYER_STATUS':
    case 'GAME_CHAT':
      break;
  }
};

let movedThisTurn = -1;

function maybeMove() {
  if (state.over) return;
  if (state.turn !== state.myStone) return;
  if (movedThisTurn === state.moves + state.myStone * 1000) return; // 防重复触发
  movedThisTurn = state.moves + state.myStone * 1000;

  // 简单策略：优先中心列，找一个能赢的列，否则轮询
  const order = [3, 2, 4, 1, 5, 0, 6];
  let target = order.find(c => dropRow(state.board, c) >= 0);
  if (target === undefined) return;

  const row = dropRow(state.board, target);
  log(`准备落子 row=${row} col=${target} moveSeq=${state.moves + 1} readyState=${ws.readyState}`);
  setTimeout(() => {
    if (state.over) return;
    try {
      ws.send(JSON.stringify({ type: 'MOVE', row, col: target, moveSeq: state.moves + 1 }));
      log(`已发送 MOVE row=${row} col=${target}`);
    } catch (e) {
      log('发送失败', e.message);
    }
  }, 600);
}

function finish(forceExitCode) {
  const winLine = state.gameOverPayload && state.gameOverPayload.winLine;
  const eloChange = state.gameOverPayload && state.gameOverPayload.eloChange;
  log('—— 对局结束 ——');
  log('结果:', state.result);
  log('总手数:', state.moves);
  log('winLine:', winLine ? `有 ${winLine.length} 点` : '无');
  log('eloChange:', eloChange, '(AI 局应为 0)');
  log('scoreSummary:', state.gameOverPayload && state.gameOverPayload.scoreSummary);
  log('回放数据:', state.gameOverPayload && state.gameOverPayload.moveHistory
      ? state.gameOverPayload.moveHistory.length + ' 手' : '无');
  log('错误:', state.errors.length ? state.errors : '无');

  const pass = state.errors.length === 0 && state.moves > 0;
  log(pass ? 'PASS' : 'FAIL');
  try { ws.close(); } catch {}
  setTimeout(() => process.exit(forceExitCode ?? (pass ? 0 : 1)), 200);
}

// 兜底超时
setTimeout(() => {
  if (!state.over) {
    state.errors.push('超时未结束，最后手数=' + state.moves);
    finish(1);
  }
}, 60000);
