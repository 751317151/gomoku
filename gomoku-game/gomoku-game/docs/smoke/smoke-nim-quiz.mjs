// 取石子 + 答题对战端到端冒烟：真实 WebSocket 双客户端
// 用法：WS_URL=ws://127.0.0.1:19001 node docs/smoke/smoke-nim-quiz.mjs
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';

function connect(name, gameType) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL);
    const c = { ws, name, side: 0, view: null, over: null, errs: [], gameType };
    ws.onopen = () => ws.send(JSON.stringify({ type: 'JOIN', playerName: name, gameType }));
    ws.onmessage = (ev) => {
      let m;
      try { m = JSON.parse(ev.data.toString()); } catch { return; }
      if (m.type === 'GAME_START' && m.stone) { c.side = m.stone; }
      if (m.type === 'ERROR') c.errs.push(m.message);
      if (m.type === 'GAME_OVER') c.over = m;
      if (m.data) {
        try {
          const d = JSON.parse(m.data);
          const v = d.view || (d.roomData && d.roomData.view);
          if (v) c.view = v;
        } catch {}
      }
    };
    ws.onerror = () => reject(new Error('WS 失败'));
    // 等视图就绪，不要固定 sleep（服务刚启动时首条连接会更慢）
    let done = false;
    const finish = () => { if (done) return; done = true; resolve(c); };
    const poll = setInterval(() => {
      if (c.view || c.errs.length) { clearInterval(poll); finish(); }
    }, 100);
    setTimeout(() => { clearInterval(poll); finish(); }, 5000);
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failed = false;
function assert(cond, label, extra) {
  if (cond) { console.log(`  ✓ ${label}`); return true; }
  console.log(`  ✗ ${label}${extra ? ' — ' + JSON.stringify(extra) : ''}`);
  failed = true;
  return false;
}

async function testNim() {
  console.log('== 取石子博弈 ==');
  const a = await connect('石子甲', 'NIM');
  const b = await connect('石子乙', 'NIM');
  for (let i = 0; i < 40 && !(a.view && b.view); i++) await sleep(150);

  assert(a.view && a.view.piles, '收到石子局面');
  if (!a.view) return;

  const A = a.side === 1 ? a : b;
  const B = A === a ? b : a;
  assert(A.side !== B.side && A.side > 0, '双方分配到不同方', { a: a.side, b: b.side });

  const piles0 = a.view.piles.slice();
  assert(piles0.length === 3 && piles0[0] === 5 && piles0[1] === 7 && piles0[2] === 9,
    '初始三堆为 5/7/9', piles0);
  assert(a.view.total === 21, '总石子 21 颗', a.view.total);

  // 红方（先手）从第 3 堆（index 2）拿 4 颗
  const actor0 = [A, B].find((p) => p.view.turn === p.side);
  assert(!!actor0, '轮次分配正确');
  const pileIdx = 2, take = 4;
  actor0.ws.send(JSON.stringify({ type: 'MOVE', row: pileIdx, col: take }));
  await sleep(700);

  const after = actor0.view.piles.slice();
  assert(after[2] === piles0[2] - take, '取子后该堆减少', { before: piles0[2], after: after[2] });

  // 非法取子：超过剩余
  const actor1 = [A, B].find((p) => p.view.turn === p.side);
  const beforeErr = actor1.errs.length;
  actor1.ws.send(JSON.stringify({ type: 'MOVE', row: 0, col: 99 }));
  await sleep(600);
  assert(actor1.errs.length > beforeErr, '超量取子被拒绝');

  // 打到结束（AI 式：每次从最大堆拿 1）
  for (let i = 0; i < 60 && !a.over && !b.over; i++) {
    const actor = [A, B].find((p) => p.view && p.view.turn === p.side && !p.over);
    if (!actor) { await sleep(200); continue; }
    const piles = actor.view.piles;
    let maxPile = 0;
    for (let k = 1; k < piles.length; k++) if (piles[k] > piles[maxPile]) maxPile = k;
    if (piles[maxPile] === 0) break;
    actor.ws.send(JSON.stringify({ type: 'MOVE', row: maxPile, col: 1 }));
    await sleep(420);
  }

  const over = a.over || b.over;
  assert(!!over, '对局结束');
  if (over) {
    console.log(`  终局: winner=${over.winner} msg=${over.message}`);
    let d = {};
    try { d = JSON.parse(over.data); } catch {}
    assert(Array.isArray(d.moveHistory) && d.moveHistory.length > 0, '带回放棋谱', d.moveHistory && d.moveHistory.length);
  }
  a.ws.close(); b.ws.close();
  await sleep(300);
}

async function testQuiz() {
  console.log('== 答题对战 ==');
  const a = await connect('答题甲', 'QUIZ');
  const b = await connect('答题乙', 'QUIZ');
  for (let i = 0; i < 40 && !(a.view && b.view); i++) await sleep(150);

  assert(a.view && a.view.question, '收到题目');
  if (!a.view) return;

  const A = a.side === 1 ? a : b;
  const B = A === a ? b : a;

  const q = a.view.question;
  assert(typeof q.text === 'string' && q.text.length > 0, '题干非空', q.text);
  assert(Array.isArray(q.options) && q.options.length === 4, '四个选项', q.options && q.options.length);
  assert(B.view && B.view.question && B.view.question.text === q.text, '双方看到同一道题');

  // 关键：揭晓前正确答案不下发
  assert(a.view.correctIndex === null || a.view.correctIndex === undefined,
    '揭晓前不下发正确答案', a.view.correctIndex);

  // 后手可以先答，双方无需等待兼容 turn 字段。
  const actor0 = B;
  actor0.ws.send(JSON.stringify({ type: 'MOVE', action: 1 }));
  await sleep(700);

  // 关键：另一方看不到对手选择
  const other = actor0 === A ? B : A;
  const otherMe = other.view.players.find((p) => p.side === other.side);
  const otherFoe = other.view.players.find((p) => p.side !== other.side);
  assert(otherFoe.answered === true, '对手「已作答」状态可见');
  assert(otherFoe.choice === null || otherFoe.choice === undefined,
    '揭晓前看不到对手选了什么', otherFoe && otherFoe.choice);

  // 乙作答 -> 本题揭晓
  const actor1 = [A, B].find((p) => p.view.turn === p.side);
  actor1.ws.send(JSON.stringify({ type: 'MOVE', action: 2 }));
  await sleep(800);

  assert(a.view.round === 2 && a.view.revealed === false, '两人答完后进入可作答的新题');
  assert(typeof a.view.lastCorrect === 'number', '上一题答案独立公开', a.view.lastCorrect);
  assert(a.view.correctIndex == null, '新题答案仍然保密', a.view.correctIndex);
  assert(a.view.lastChoices.length === 2, '上一题双方选择可回顾');
  const red = a.view.players.find((p) => p.side === 1);
  const blue = a.view.players.find((p) => p.side === 2);
  assert(typeof red.score === 'number' && typeof blue.score === 'number', '分数已结算',
    { red: red.score, blue: blue.score });

  // 打完全部 8 题
  for (let i = 0; i < 60 && !a.over && !b.over; i++) {
    // 任意尚未作答的座位可提交。
    const actor = [B, A].find((p) => p.view
      && !p.over
      && !(p.view.players.find((x) => x.side === p.side) || {}).answered);
    if (!actor) { await sleep(250); continue; }
    actor.ws.send(JSON.stringify({ type: 'MOVE', action: Math.floor(Math.random() * 4) }));
    await sleep(380);
  }

  const over = a.over || b.over;
  assert(!!over, '答满全部题目后结束');
  if (over) console.log(`  终局: winner=${over.winner} msg=${over.message}`);
  a.ws.close(); b.ws.close();
  await sleep(300);
}

(async () => {
  try {
    await testNim();
    await testQuiz();
  } catch (e) {
    console.error('冒烟异常:', e.message);
    failed = true;
  }
  console.log(failed ? '\n失败' : '\n全部通过');
  process.exitCode = failed ? 1 : 0;
})();
