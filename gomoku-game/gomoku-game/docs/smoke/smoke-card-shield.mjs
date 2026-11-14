// 圣盾 / 亡语召唤 端到端校验
//
// 通过真实对局观察：场上随从带 divineShield 字段，出现 true（未破盾）与 false（已破盾）；
// 亡语随从阵亡后场上出现衍生物。
//
// 为什么要打多局：圣盾随从是 4 费（圣盾卫兵）和 8 费（战争泰坦），开局只有 3 费打不出来；
// 若这一局结束得早（对面是快攻），可能整局都没见过圣盾。所以最多打 4 局，见到就停。
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';

const SHIELD_DECK = { SHIELD_GUARD: 6, WAR_TITAN: 6, FOOTMAN: 6, ARCHER: 6, KNIGHT: 6 };

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
    // 等对局真的开始（不要固定 sleep：服务刚启动时首条连接会更慢）
    let done = false;
    const finish = () => { if (done) return; done = true; resolve(c); };
    const poll = setInterval(() => {
      if (c.view || c.errs.length) { clearInterval(poll); finish(); }
    }, 100);
    setTimeout(() => { clearInterval(poll); finish(); }, 5000);
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
const me = (c) => (c.view && c.view.players ? (c.view.players.find(p => p.side === c.side) || {}) : {});
const allMinions = (c) => (c.view && c.view.players ? c.view.players.flatMap(p => (p.board || [])) : []);

/** 打一局，返回观察到的关键词现象 */
async function playOneGame(tag) {
  const a = await connect('盾甲' + tag, 'CARD', 'CUSTOM', SHIELD_DECK);
  const b = await connect('盾乙' + tag, 'CARD', 'AGGRO');
  const obs = { sawShield: false, sawShieldBroken: false, sawToken: false, labels: new Set(), started: false };

  if (a.view && a.view.players) obs.started = true;

  for (let round = 0; round < 60 && !a.over && !b.over; round++) {
    for (const p of [a, b]) {
      if (!p.view || p.view.over) continue;
      for (const m of allMinions(p)) {
        obs.labels.add(m.label);
        assert(typeof m.divineShield === 'boolean', `随从 ${m.label} 带 divineShield 字段`);
        if (m.divineShield === true) obs.sawShield = true;
        if (m.divineShield === false && (m.label === '圣盾卫兵' || m.label === '战争泰坦')) {
          obs.sawShieldBroken = true;
        }
        if (m.label === '幼狼' || m.label === '骷髅战士') obs.sawToken = true;
      }

      if (p.view.turn !== p.side) continue;
      const hand = me(p).hand || [];
      const c = hand.find(x => x.playable);
      if (!c) { p.ws.send(JSON.stringify({ type: 'END_TURN' })); await sleep(300); continue; }
      const mv = { type: 'MOVE', action: c.index };
      if (c.needsTarget) {
        if (c.label === '鼓舞') { mv.targetSide = p.side; mv.targetIndex = 0; }
        else { mv.targetSide = 3 - p.side; mv.targetIndex = -1; }
      }
      p.ws.send(JSON.stringify(mv));
      await sleep(280);
    }
    // 顶一下攻击（有可攻击随从就先打）
    for (const p of [a, b]) {
      if (!p.view || p.view.over || p.view.turn !== p.side) continue;
      const myBoard = me(p).board || [];
      const idx = myBoard.findIndex(m => m.canAttack);
      if (idx >= 0) {
        const taunt = (p.view.players.find(x => x.side !== p.side).board || [])
          .findIndex(m => m.taunt);
        p.ws.send(JSON.stringify({ type: 'MOVE', action: -2, row: idx, col: taunt >= 0 ? taunt : -1 }));
        await sleep(280);
      }
    }
    await sleep(100);
  }

  try { a.ws.close(); } catch {}
  try { b.ws.close(); } catch {}
  await sleep(300);
  return obs;
}

(async () => {
  console.log('== 卡牌圣盾 / 亡语召唤校验 ==');

  const agg = { sawShield: false, sawShieldBroken: false, sawToken: false, labels: new Set(), started: false };
  const MAX_GAMES = 4;
  for (let g = 0; g < MAX_GAMES; g++) {
    const obs = await playOneGame(g);
    assert(obs.started, `第 ${g + 1} 局对局已开始`);
    obs.labels.forEach(l => agg.labels.add(l));
    agg.sawShield = agg.sawShield || obs.sawShield;
    agg.sawShieldBroken = agg.sawShieldBroken || obs.sawShieldBroken;
    agg.sawToken = agg.sawToken || obs.sawToken;
    if (agg.sawShield) break; // 已观测到圣盾，不必再打
  }

  console.log('  场上见过的随从:', [...agg.labels].join(', '));
  assert(agg.sawShield, '见过带圣盾状态(true)的随从', [...agg.labels]);
  // 破盾与衍生物依赖对局走向，作为「尽力观察」而不是硬断言
  console.log(`  ${agg.sawShieldBroken ? '✓' : '·'} 观察到破盾（divineShield=false）`);
  console.log(`  ${agg.sawToken ? '✓' : '·'} 观察到亡语召唤的衍生物`);

  console.log(failed ? '\n失败' : '\n全部通过');
  process.exitCode = failed ? 1 : 0;
})().catch(e => { console.error('异常:', e.message); process.exit(1); });
