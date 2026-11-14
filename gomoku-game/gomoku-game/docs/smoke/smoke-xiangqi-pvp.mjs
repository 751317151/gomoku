// 象棋双人对局冒烟：两个独立连接走 PvP 路径（验证协议 fromRow/fromCol 与 ELO 入账）
const URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const problems = [];

function mk(name) {
  const c = { name, ws: null, id: null, stone: 0, seq: 0, board: null, turn: 1, done: false, eloChange: 0 };
  c.ws = new WebSocket(URL);
  c.ws.onopen = () => c.ws.send(JSON.stringify({ type: 'JOIN', playerName: name, gameType: 'XIANGQI' }));
  c.ws.onmessage = (ev) => {
    let m; try { m = JSON.parse(ev.data); } catch { return; }
    switch (m.type) {
      case 'WAITING':
        c.id = m.playerId;
        break;
      case 'GAME_START':
        c.id = m.playerId; c.stone = m.stone;
        c.turn = 1; // 红方先手
        console.log(`[${name}] 开局 stone=${m.stone}`);
        setTimeout(onTurn, 300);
        break;
      case 'GAME_MOVE': {
        let d = {}; try { d = JSON.parse(m.data || '{}'); } catch {}
        if (typeof m.moveSeq === 'number') c.seq = m.moveSeq;
        if (typeof d.turn === 'number') c.turn = d.turn;
        onTurn();
        break;
      }
      case 'GAME_OVER':
        c.done = true;
        try {
          const d = JSON.parse(m.data || '{}');
          c.eloChange = d.eloChange || 0;
          // 关键校验：scores 里每位玩家的 eloChange 应非零且互为相反数
          c.scores = d.scores || [];
        } catch {}
        console.log(`[${name}] 结束: ${m.message} eloChange=${c.eloChange}`);
        setTimeout(finish, 400);
        break;
      case 'ERROR':
        problems.push(`[${name}] ${m.message}`);
        break;
    }
  };
  return c;
}

const A = mk('红方测试');
const B = mk('黑方测试');
const peers = [A, B];

// 红方（下方）着法计划：每步目标格都必须为空，且互不占位
const PLAN_RED = [
  [7, 1, 7, 4], // 炮二平五
  [6, 4, 5, 4], // 兵五进一
  [9, 1, 7, 2], // 马八进七
  [9, 6, 7, 8], // 相七进九
  [7, 7, 7, 6], // 炮八平七
];

// 黑方（上方）着法计划：两匹马来回跳，不与红方冲突
const PLAN_BLACK = [
  [0, 1, 2, 0],
  [2, 0, 0, 1],
  [0, 7, 2, 8],
  [2, 8, 0, 7],
];
let redStep = 0, blackStep = 0;
let lastTurnHandled = 0;

// 执红方（stone=1）走固定计划；执黑方走闲棋
function redSide() { return A.stone === 1 ? A : (B.stone === 1 ? B : null); }
function blackSide() { return A.stone === 2 ? A : (B.stone === 2 ? B : null); }

function onTurn() {
  const red = redSide(), black = blackSide();
  if (!red || !black) return;

  const actor = red.turn === 1 ? red : (black.turn === 2 ? black : null);
  if (!actor) return;
  const seq = actor.seq + 1;
  if (seq === lastTurnHandled) return;
  lastTurnHandled = seq;

  setTimeout(() => {
    if (A.done || B.done) return;
    if (actor === red) {
      if (redStep >= PLAN_RED.length) return; // 计划走完就停，靠认输结束
      const [fr, fc, tr, tc] = PLAN_RED[redStep++];
      red.ws.send(JSON.stringify({ type: 'MOVE', fromRow: fr, fromCol: fc, row: tr, col: tc, moveSeq: seq }));
    } else {
      // 黑方按预设计划走（马来回跳），保证每步起点都有子、终点为空
      const mv = PLAN_BLACK[blackStep % PLAN_BLACK.length];
      blackStep++;
      black.ws.send(JSON.stringify({
        type: 'MOVE', fromRow: mv[0], fromCol: mv[1], row: mv[2], col: mv[3], moveSeq: seq,
      }));
    }
  }, 350);
}

// 红方走完计划后直接认输，促成终局并检查 ELO
setTimeout(() => {
  const red = redSide();
  if (red && !red.done) {
    console.log('[红方] 认输以触发终局');
    red.ws.send(JSON.stringify({ type: 'SURRENDER' }));
  }
}, 9000);

function finish() {
  if (!A.done && !B.done) return;
  const eloA = A.eloChange, eloB = B.eloChange;
  console.log('[pvp] 手数 seq:', A.seq, '/', B.seq);
  console.log('[pvp] ELO 变化:', eloA, eloB);

  // 校验 scores[].eloChange（前端终局面板就读这个字段）
  const scores = A.scores.length ? A.scores : B.scores;
  console.log('[pvp] scores:', scores.map(s => `${s.name}=${s.elo}(${s.eloChange >= 0 ? '+' : ''}${s.eloChange})`).join(', '));
  const perPlayer = scores.map(s => s.eloChange);
  const hasNonZero = perPlayer.some(v => v !== 0);
  const symmetric = perPlayer.length === 2 && perPlayer[0] === -perPlayer[1];

  if (A.seq < 2) problems.push('对局手数不足，PvP 未真正进行');
  if (eloA === 0 && eloB === 0) problems.push('PvP 终局 ELO 未入账');
  if (!hasNonZero) problems.push('scores[].eloChange 全为 0 —— 终局面板无法显示加减分');
  if (!symmetric) problems.push('双方 scores[].eloChange 不为相反数: ' + perPlayer.join('/'));

  console.log('[pvp] 问题:', problems.length ? problems : '无');
  const pass = problems.length === 0;
  console.log(pass ? '[pvp] PASS' : '[pvp] FAIL');
  try { A.ws.close(); B.ws.close(); } catch {}
  setTimeout(() => process.exit(pass ? 0 : 1), 200);
}

setTimeout(() => { problems.push('超时'); finish(); }, 25000);
