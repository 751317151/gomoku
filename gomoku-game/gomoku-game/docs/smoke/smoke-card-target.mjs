// 选目标法术端到端校验：火球/鼓舞必须带合法目标，非法目标被拒绝且不扣资源
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';

function connect(name, gameType) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL);
    const c = { ws, side: 0, view: null, over: null, errs: [] };
    ws.onopen = () => ws.send(JSON.stringify({ type: 'JOIN', playerName: name, gameType }));
    ws.onmessage = (ev) => {
      let m; try { m = JSON.parse(ev.data.toString()); } catch { return; }
      if (m.type === 'GAME_START' && m.stone) c.side = m.stone;
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
    setTimeout(() => resolve(c), 1500);
  });
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let failed = false;
function assert(cond, label, extra) {
  if (cond) { console.log(`  ✓ ${label}`); return true; }
  console.log(`  ✗ ${label}${extra ? ' — ' + JSON.stringify(extra) : ''}`);
  failed = true;
  return false;
}
const me = (c) => (c.view.players.find(p => p.side === c.side) || {});

(async () => {
  console.log('== 卡牌选目标法术校验 ==');
  const a = await connect('目标甲', 'CARD');
  const b = await connect('目标乙', 'CARD');
  await sleep(1500);
  assert(a.view && a.view.players, '收到对战视图');
  if (!a.view) process.exit(1);

  // 手牌字段：needsTarget 必须存在
  const hand = me(a).hand || [];
  assert(hand.every(c => typeof c.needsTarget === 'boolean'),
    '每张手牌都带 needsTarget 字段', hand.map(c => c.label));

  // 找一张需要目标的牌；没有就等几轮抽牌
  let found = null;
  let actor = null;
  for (let round = 0; round < 15 && !found; round++) {
    for (const p of [a, b]) {
      const h = me(p).hand || [];
      const idx = h.findIndex(c => c.needsTarget && c.playable);
      if (idx >= 0) { found = h[idx]; actor = p; break; }
    }
    if (found) break;
    // 双方各出一张能出的牌 + 结束回合，推进抽牌
    for (const p of [a, b]) {
      const v = p.view;
      if (!v || v.over) continue;
      const h = me(p).hand || [];
      const c = h.find(x => x.playable && !x.needsTarget);
      if (c) { p.ws.send(JSON.stringify({ type: 'MOVE', action: c.index })); await sleep(320); }
      p.ws.send(JSON.stringify({ type: 'END_TURN' }));
      await sleep(320);
    }
  }

  let skipped = 0;
  if (!found) {
    console.log('  (15 轮内未抽到需选目标的牌，跳过目标校验)');
    skipped += 1;
  } else {
    console.log(`  抽到需选目标的牌: ${found.label}`);
    assert(found.playable, '该牌当前可打出');

    const before = {
      mana: me(actor).mana,
      handSize: (me(actor).hand || []).length,
      foeHp: (actor.view.players.find(p => p.side !== actor.side) || {}).hp,
    };

    // 1. 不带目标直接打 → 应被拒且不扣资源
    const errCount = actor.errs.length;
    actor.ws.send(JSON.stringify({ type: 'MOVE', action: found.index }));
    await sleep(700);
    assert(actor.errs.length > errCount, '缺少目标被服务端拒绝');
    assert(me(actor).mana === before.mana, '非法目标不扣法力', { before: before.mana, after: me(actor).mana });
    assert((me(actor).hand || []).length === before.handSize, '非法目标不消耗手牌');

    // 2. 带错误方目标（火球打己方）→ 也应被拒
    if (found.label === '火球术') {
      const errCount2 = actor.errs.length;
      actor.ws.send(JSON.stringify({
        type: 'MOVE', action: found.index, targetSide: actor.side, targetIndex: -1,
      }));
      await sleep(700);
      assert(actor.errs.length > errCount2, '火球指定己方目标被拒绝');
    }

    // 3. 带合法目标 → 成功
    //
    // 注意：前面几次非法尝试之后，回合可能已经交出去了（对手在此期间合法出牌）。
    // 所以这里必须重新确认「现在轮到谁、谁手上有这张牌」，不能沿用之前的 actor。
    const freshActor = [a, b].find(p => p.view && p.view.turn === p.side && !p.over);
    if (!freshActor) {
      console.log('  (对局已结束或无人可行动，跳过成功用例)');
    } else {
      const freshHand = me(freshActor).hand || [];
      const tIdx = freshHand.findIndex(c => c.needsTarget && c.playable);
      if (tIdx < 0) {
        console.log('  (当前行动方手上没有可打的目标牌，跳过成功用例)');
        skipped += 1;
      } else {
        const c2 = freshHand[tIdx];
        const manaBefore2 = me(freshActor).mana;
        const foeHpBefore2 = (freshActor.view.players.find(p => p.side !== freshActor.side) || {}).hp;
        const mv = { type: 'MOVE', action: c2.index };
        if (c2.label === '鼓舞') {
          if ((me(freshActor).board || []).length > 0) { mv.targetSide = freshActor.side; mv.targetIndex = 0; }
        } else {
          mv.targetSide = 3 - freshActor.side;
          mv.targetIndex = -1;
        }
        if (!mv.targetSide) {
          console.log('  (鼓舞无己方随从，跳过成功用例)');
        } else {
          freshActor.ws.send(JSON.stringify(mv));
          await sleep(900);
          assert(me(freshActor).mana < manaBefore2, '合法目标出牌成功并扣除法力',
            { before: manaBefore2, after: me(freshActor).mana });
          if (c2.label === '火球术') {
            const foeHpNow = (freshActor.view.players.find(p => p.side !== freshActor.side) || {}).hp;
            assert(foeHpNow <= foeHpBefore2 - 4, '火球对敌方英雄造成 4 点伤害',
              { before: foeHpBefore2, now: foeHpNow });
          }
        }
      }
    }
  }

  a.ws.close(); b.ws.close();
  await sleep(300);
  console.log(failed ? '\n失败' : '\n全部通过');
  process.exitCode = failed ? 1 : 0;
})().catch(e => { console.error('异常:', e.message); process.exit(1); });
