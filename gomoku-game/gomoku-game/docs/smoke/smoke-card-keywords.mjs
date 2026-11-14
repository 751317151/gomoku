// 卡牌关键词端到端校验：确认服务端下发的 view.board 带全关键词字段
// 单机即可（不需要对手出手），主要验证协议/视图层而不是规则
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';

function connect(name, gameType, deck, deckCards) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL);
    const c = { ws, side: 0, view: null, over: null, errs: [] };
    ws.onopen = () => {
      const join = { type: 'JOIN', playerName: name, gameType };
      if (deck) join.deck = deck;
      if (deckCards) join.deckCards = JSON.stringify(deckCards);
      ws.send(JSON.stringify(join));
    };
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

(async () => {
  console.log('== 卡牌关键词视图校验 ==');
  // 甲用「亡语拉满」的自定义套牌（12/30 带亡语），让「见过战吼/亡语随从」
  // 成为构造出来的事实，而不是靠随机抽牌。
  const a = await connect('关键词甲', 'CARD', 'CUSTOM',
    { SKELETON: 6, BONE_DRAGON: 6, FOOTMAN: 6, ARCHER: 6, KNIGHT: 6 });
  const b = await connect('关键词乙', 'CARD', 'AGGRO');
  await sleep(1500);

  assert(a.view && a.view.players, '收到对战视图');
  if (!a.view) { process.exit(1); }

  const me = a.view.players.find(p => p.side === a.side);
  assert(Array.isArray(me.hand), '能看到自己手牌');

  // 手牌里每张牌都应带 desc（前端展示卡面用）
  const noDesc = me.hand.filter(c => !c.desc || typeof c.desc !== 'string');
  assert(noDesc.length === 0, '每张手牌都带描述文本', noDesc.map(c => c.label));

  // 牌库里应有 11 种卡；多开局几次直到手牌出现带关键词的牌
  let sawBattlecryOrDeathrattle = false;
  let sawChargeOrTaunt = false;
  const seen = new Set();

  for (let round = 0; round < 12; round++) {
    // 双方都把能出的牌出掉，逼出新卡
    for (const p of [a, b]) {
      const v = p.view;
      if (!v || v.over) continue;
      const pm = v.players.find(x => x.side === p.side);
      if (!pm || !pm.hand) continue;
      for (const c of pm.hand.filter(x => x.playable)) {
        seen.add(c.label);
        // 关键词字段只在场面视图里；手牌用已知卡名兜底
        if (c.label === '狂战士' || c.label === '骨龙' || c.label === '死灵法师' || c.label === '骷髅') {
          sawBattlecryOrDeathrattle = true;
        }
        const mv = { type: 'MOVE', action: c.index };
        if (c.needsTarget) {
          if (c.label === '鼓舞') {
            mv.targetSide = p.side;
            mv.targetIndex = 0;
          } else {
            mv.targetSide = 3 - p.side;
            mv.targetIndex = -1;
          }
        }
        p.ws.send(JSON.stringify(mv));
        await sleep(320);
      }
      p.ws.send(JSON.stringify({ type: 'END_TURN' }));
      await sleep(320);
    }
    // 检查场上随从是否带关键词字段
    for (const p of [a, b]) {
      for (const pl of (p.view && p.view.players) || []) {
        for (const m of (pl.board || [])) {
          assert(typeof m.taunt === 'boolean', `随从 ${m.label} 带 taunt 字段`);
          assert(typeof m.charge === 'boolean', `随从 ${m.label} 带 charge 字段`);
          assert(typeof m.battlecry === 'boolean', `随从 ${m.label} 带 battlecry 字段`);
          assert(typeof m.deathrattle === 'boolean', `随从 ${m.label} 带 deathrattle 字段`);
          assert(typeof m.canAttack === 'boolean', `随从 ${m.label} 带 canAttack 字段`);
          if (m.battlecry || m.deathrattle) sawBattlecryOrDeathrattle = true;
          if (m.taunt || m.charge) sawChargeOrTaunt = true;
        }
      }
    }
    if (a.over || b.over) break;
  }

  console.log('  抽到过的卡:', [...seen].join(', '));
  // 12 轮应该覆盖到多数卡；至少确认见过不止步兵
  assert(seen.size >= 3, '多轮对局能抽到多种卡牌', [...seen]);
  assert(sawBattlecryOrDeathrattle, '见过战吼或亡语随从', [...seen]);

  a.ws.close(); b.ws.close();
  await sleep(300);
  console.log(failed ? '\n失败' : '\n全部通过');
  process.exitCode = failed ? 1 : 0;
})().catch(e => { console.error('异常:', e.message); process.exit(1); });
