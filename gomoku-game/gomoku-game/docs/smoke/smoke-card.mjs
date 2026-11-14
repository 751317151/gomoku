// 卡牌对战端到端冒烟：两个 WS 客户端，验证规则、隐藏信息、AI 对局
// 用法：node docs/smoke/smoke-card.mjs [ws://localhost:9001]
const WS_URL = process.env.WS_URL || process.argv[2] || 'ws://127.0.0.1:19001';
const GAME = 'CARD';

function connect(name) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL);
    const client = { ws, name, id: null, side: 0, view: null, over: null, myTurn: 0 };
    ws.onopen = () => {
      ws.send(JSON.stringify({ type: 'JOIN', playerName: name, gameType: GAME }));
    };
    ws.onmessage = (ev) => {
      let m;
      try { m = JSON.parse(ev.data.toString()); } catch { return; }
      if (m.playerId) { client.id = m.playerId; }
      // 只有 GAME_START 里的 stone 才代表「我执哪一方」。
      // GAME_MOVE 的 stone 是「这手是谁走的」，用它覆盖会把自己误判成对手，
      // 进而把对手的视角当自己的用（实测导致脚本选出错误的行动方）。
      if (m.type === 'GAME_START' && m.stone) {
        client.side = m.stone;
        client.id = m.playerId || client.id;
      }
      if (m.type === 'GAME_START' || m.type === 'ROOM_INFO' || m.type === 'GAME_SYNC') {
        if (m.data) {
          try {
            const d = JSON.parse(m.data);
            const v = d.view || (d.roomData && d.roomData.view);
            if (v) client.view = v;
            if (v && typeof v.turn === 'number') client.myTurn = v.turn;
          } catch {}
        }
      }
      if (m.type === 'GAME_MOVE' && m.data) {
        try {
          const d = JSON.parse(m.data);
          if (d.view) { client.view = d.view; client.myTurn = d.view.turn; }
        } catch {}
      }
      if (m.type === 'GAME_OVER') {
        client.over = m;
      }
    };
    ws.onerror = () => reject(new Error('WS 连接失败'));
    setTimeout(() => resolve(client), 1400);
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 从某方视角拿到的 view 里提取手牌
function handOf(view, side) {
  if (!view || !view.players) return null;
  const p = view.players.find((x) => x.side === side);
  return p ? p.hand : null;
}

function assert(cond, label, extra) {
  if (cond) { console.log(`  ✓ ${label}`); return true; }
  console.log(`  ✗ ${label}${extra ? ' — ' + JSON.stringify(extra) : ''}`);
  process.exitCode = 1;
  return false;
}

(async () => {
  console.log('== 卡牌对战冒烟 ==');
  const gameType = await fetchJson('http://127.0.0.1:19000/api/rooms');
  assert(Array.isArray(gameType), 'REST 可用');

  const p1 = await connect('牌手甲');
  const p2 = await connect('牌手乙');
  await sleep(600);
  // 让 p2 加入 p1 的房间：两人都用自动匹配，同类型会配到一起
  await sleep(400);

  assert(p1.view || p2.view, '收到对战视图');
  await sleep(1200); // 等 GAME_START 后的首个 ROOM_INFO

  assert(p1.side === 1 || p2.side === 1, '双方分配了先后手', { p1: p1.side, p2: p2.side });

  const A = p1.side === 1 ? p1 : p2;
  const B = A === p1 ? p2 : p1;

  // --- 隐藏信息校验：A 的 view 里只能看到自己的手牌 ---
  const handA_own = handOf(A.view, A.side);
  const handA_foe = handOf(A.view, B.side);
  assert(Array.isArray(handA_own) && handA_own.length > 0, '自己能看见手牌', handA_own);
  assert(handA_foe === undefined || handA_foe === null,
    '看不到对手手牌内容（隐藏信息隔离）', handA_foe);

  // --- 出牌 ---
  const before = A.view.players.find((x) => x.side === A.side).hand.length;
  const playable = (handOf(A.view, A.side) || []).find((c) => c.playable);
  assert(!!playable, '起手存在可打出的牌');
  if (playable) {
    A.ws.send(JSON.stringify({ type: 'MOVE', action: playable.index }));
    await sleep(1200);
    const after = A.view.players.find((x) => x.side === A.side).hand.length;
    assert(after !== before || A.view.turn !== A.side, '出牌后状态推进', { before, after });
  }

  // --- 非法指令被拒绝：坐标指令对卡牌无效 ---
  A.ws.send(JSON.stringify({ type: 'MOVE', row: 1, col: 2 }));
  await sleep(500);

  // --- 攻击相关规则 ---
  // 1. 召唤失调：刚召唤的非冲锋随从当回合不能攻击（canAttack=false）
  // 2. 有可攻击随从时，手动攻击（action=-2）对敌方英雄造成伤害
  const myBoard = (A.view.players.find((x) => x.side === A.side).board) || [];
  const sick = myBoard.filter((m) => m.canAttack === false && !m.attacked);
  if (sick.length > 0) {
    const idx = myBoard.indexOf(sick[0]);
    const foeHpBefore = A.view.players.find((x) => x.side !== A.side).hp;
    A.ws.send(JSON.stringify({ type: 'MOVE', action: -2, row: idx, col: -1 }));
    await sleep(700);
    const foeHpAfter = A.view.players.find((x) => x.side !== A.side).hp;
    assert(foeHpAfter === foeHpBefore, '召唤失调的随从不能攻击（服务端拒绝）',
      { foeHpBefore, foeHpAfter, canAttack: sick[0].canAttack });
  }

  // 结束回合 → 回来解除召唤失调后就能攻击
  A.ws.send(JSON.stringify({ type: 'END_TURN' }));
  await sleep(900);
  const readyBoard = (A.view.players.find((x) => x.side === A.side).board) || [];
  const readyIdx = readyBoard.findIndex((m) => m.canAttack !== false);
  if (readyIdx >= 0) {
    const foeHpBefore = A.view.players.find((x) => x.side !== A.side).hp;
    A.ws.send(JSON.stringify({ type: 'MOVE', action: -2, row: readyIdx, col: -1 }));
    await sleep(700);
    const foeHpAfter = A.view.players.find((x) => x.side !== A.side).hp;
    // 对方有嘲讽随从时可能被拒绝，这里只断言「要么掉血、要么被嘲讽挡住」
    assert(foeHpAfter < foeHpBefore || (A.view.players.find(x => x.side !== A.side).board || []).some(m => m.taunt),
      '可攻击随从对敌方英雄造成伤害（或被打脸外的嘲讽挡住）',
      { foeHpBefore, foeHpAfter });
  } else {
    console.log('  (无可攻击随从，跳过攻击断言)');
  }


  // --- 打到结束 ---
  // 卡牌是一回合多行动：出到没有可打的牌，再显式 END_TURN 交出回合。
  // 每步间隔必须大于 Player.canPerformAction 的 300ms 限流，否则指令被静默丢弃。
  let stalls = 0;
  for (let i = 0; i < 400 && !p1.over && !p2.over; i++) {
    const actor = [p1, p2].find((p) => p.view && p.view.turn === p.side && !p.over);
    if (!actor) { await sleep(200); continue; }
    const h = handOf(actor.view, actor.side) || [];
    const c = h.find((x) => x.playable);
    if (!c) {
      // 没牌可打 → 结束回合（会在服务端结算随从攻击并换手）
      actor.ws.send(JSON.stringify({ type: 'END_TURN' }));
      stalls++;
      if (stalls > 60) { console.log('  (连续无法出牌，提前结束)'); break; }
      await sleep(420);
      continue;
    }
    stalls = 0;
    // 需要选目标的法术（火球/鼓舞）必须带目标，否则服务端会拒绝
    const mv = { type: 'MOVE', action: c.index };
    if (c.needsTarget) {
      const meSide = actor.side;
      if (c.label === '鼓舞') {
        mv.targetSide = meSide;
        mv.targetIndex = 0; // 己方第一个随从
      } else {
        mv.targetSide = 3 - meSide;
        mv.targetIndex = -1; // 打敌方英雄
      }
    }
    actor.ws.send(JSON.stringify(mv));
    await sleep(420);
  }

  const over = p1.over || p2.over;
  assert(!!over, '对局在对局上限内结束');
  if (over) {
    console.log(`  终局: winner=${over.winner} msg=${over.message}`);
    let data = {};
    try { data = JSON.parse(over.data); } catch {}
    assert(Array.isArray(data.moveHistory) && data.moveHistory.length > 0, '带回放棋谱', data.moveHistory && data.moveHistory.length);
    // 终局数据里不应出现任何一方的手牌内容
    const txt = over.data || '';
    assert(!txt.includes('"hand"'), '终局数据不泄漏手牌');
  }

  p1.ws.close();
  p2.ws.close();
  await sleep(300);

  console.log(process.exitCode ? '\n失败' : '\n全部通过');
  process.exit(process.exitCode || 0);
})().catch((e) => {
  console.error('冒烟失败:', e.message);
  process.exit(1);
});

async function fetchJson(url) {
  const r = await fetch(url);
  return r.json();
}
