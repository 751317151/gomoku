// 序号错位自愈验证：故意发一个超前 seq，确认服务端回带真实手数，
// 之后用正确 seq 仍能继续对局（修复前会永久锁死）
const URL = process.env.WS_URL || 'ws://127.0.0.1:19001';

let seqFromServer = 0;
let phase = 'init';
let aiRequested = false;
let board = Array.from({ length: 6 }, () => new Array(7).fill(0));
let myStone = 0, turn = 1;
const problems = [];

function dropRow(col) {
  for (let r = 5; r >= 0; r--) if (board[r][col] === 0) return r;
  return -1;
}

const ws = new WebSocket(URL);
ws.onopen = () => ws.send(JSON.stringify({ type: 'JOIN', playerName: '序号测试', gameType: 'CONNECT4', createRoom: true }));

ws.onmessage = (ev) => {
  let msg; try { msg = JSON.parse(ev.data); } catch { return; }

  if (msg.type === 'WAITING' && !aiRequested) {
    aiRequested = true;
    ws.send(JSON.stringify({ type: 'ADD_AI', data: '2' })); return;
  }

  if (msg.type === 'GAME_START') {
    myStone = msg.stone;
    // 故意先发一个明显超前的 seq，模拟「本地序号错位」
    phase = 'bogus';
    console.log('[seq] 故意发送超前 moveSeq=99');
    ws.send(JSON.stringify({ type: 'MOVE', row: 5, col: 3, moveSeq: 99 }));
    return;
  }

  if (msg.type === 'ERROR') {
    if (phase === 'bogus') {
      console.log('[seq] 收到拒绝:', msg.message, '| 服务端回传手数:', msg.data);
      if (msg.message !== '无效落子') problems.push('拒绝原因不对: ' + msg.message);
      const s = parseInt(msg.data, 10);
      if (isNaN(s)) {
        problems.push('未回带服务端手数（无法校准）');
        finish();
        return;
      }
      seqFromServer = s;
      phase = 'recover';
      // 用服务端手数校准后立即重试
      const col = 3, row = dropRow(col);
      console.log('[seq] 校准后重试 row=' + row + ' col=' + col + ' seq=' + (seqFromServer + 1));
      ws.send(JSON.stringify({ type: 'MOVE', row, col, moveSeq: seqFromServer + 1 }));
      return;
    }
    problems.push('对局中出现错误: ' + msg.message);
    finish();
    return;
  }

  if (msg.type === 'GAME_MOVE') {
    if (msg.row >= 0) {
      board[msg.row][msg.col] = msg.stone;
      if (typeof msg.moveSeq === 'number') seqFromServer = msg.moveSeq;
    }
    if (phase === 'recover' && seqFromServer === 1) {
      console.log('[seq] 校准后落子成功，服务端手数=1 → 自愈成立');
      // 再走两步证明可持续
      phase = 'continue';
    }
    let data = {};
    try { data = JSON.parse(msg.data || '{}'); } catch {}
    if (typeof data.turn === 'number') turn = data.turn;
    maybeMove();
    return;
  }

  if (msg.type === 'GAME_OVER') {
    console.log('[seq] 对局结束:', msg.message, '| 最终手数:', seqFromServer);
    finish();
  }
};

let lastSent = -1;

function maybeMove() {
  if (phase !== 'continue') return;
  if (turn !== myStone) return;
  if (lastSent === seqFromServer) return;
  lastSent = seqFromServer;

  const order = [3, 2, 4, 1, 5, 0, 6];
  const col = order.find(c => dropRow(c) >= 0);
  if (col === undefined) return;
  const row = dropRow(col);
  setTimeout(() => {
    ws.send(JSON.stringify({ type: 'MOVE', row, col, moveSeq: seqFromServer + 1 }));
  }, 600);
}

function finish() {
  const pass = problems.length === 0 && seqFromServer >= 2;
  console.log('[seq] 问题:', problems.length ? problems : '无');
  console.log('[seq] 累计手数:', seqFromServer);
  console.log(pass ? '[seq] PASS' : '[seq] FAIL');
  try { ws.close(); } catch {}
  setTimeout(() => process.exit(pass ? 0 : 1), 200);
}

setTimeout(() => { problems.push('超时'); finish(); }, 40000);
