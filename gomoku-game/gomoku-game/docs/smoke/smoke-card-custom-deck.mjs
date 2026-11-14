// 自定义套牌端到端校验
// 关键：服务端必须独立校验配比（客户端规则不可信），且合法配比要真的决定牌库内容。
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';

function startWithCustom(deckCards, label) {
  return new Promise((resolve) => {
    const ws = new WebSocket(WS_URL);
    const c = { ws, view: null, side: 0, errs: [], label };
    ws.onopen = () => {
      ws.send(JSON.stringify({
        type: 'JOIN', playerName: '自组-' + label, gameType: 'CARD',
        deck: 'CUSTOM', deckCards: JSON.stringify(deckCards),
      }));
      setTimeout(() => ws.send(JSON.stringify({ type: 'ADD_AI', data: '4' })), 500);
    };
    ws.onmessage = (ev) => {
      let m; try { m = JSON.parse(ev.data.toString()); } catch { return; }
      if (m.type === 'GAME_START' && m.stone) c.side = m.stone;
      if (m.type === 'ERROR') c.errs.push(m.message);
      if (m.data) {
        try {
          const d = JSON.parse(m.data);
          const v = d.view || (d.roomData && d.roomData.view);
          if (v) c.view = v;
        } catch {}
      }
    };
    setTimeout(() => resolve(c), 2800);
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

const VALID = { FOOTMAN: 6, ARCHER: 6, KNIGHT: 6, FIREBALL: 6, SHIELD_GUARD: 6 };

(async () => {
  console.log('== 卡牌自定义套牌校验 ==');

  // 1. 合法配比：开局成功，起手只可能来自所选 5 种卡
  const ok = await startWithCustom(VALID, 'valid');
  assert(!!ok.view, '合法配比能开局', ok.errs);
  if (ok.view) {
    const me = ok.view.players.find(p => p.side === ok.side);
    assert(me && me.deck === 'CUSTOM', '套牌模式为 CUSTOM', me && me.deck);
    assert(me && me.deckLabel === '自定义', '显示中文标签「自定义」', me && me.deckLabel);
    const allowed = new Set(Object.keys(VALID));
    const LABEL_TO_KEY = {
      '步兵': 'FOOTMAN', '弓箭手': 'ARCHER', '骑士': 'KNIGHT', '火球术': 'FIREBALL',
      '守卫': 'GUARDIAN', '幼龙': 'DRAGON', '治疗术': 'HEAL', '横扫': 'CLEAVE',
      '鼓舞': 'BUFF', '狂战士': 'BERSERKER', '骨龙': 'BONE_DRAGON',
      '圣盾卫兵': 'SHIELD_GUARD', '骷髅': 'SKELETON',
    };
    const bad = (me.hand || []).filter(c => !allowed.has(LABEL_TO_KEY[c.label]));
    assert(bad.length === 0, '起手只出现所选卡牌', bad.map(c => c.label));
    ok.ws.close();
    await sleep(300);
  }

  // 2. 总数不对 → 回落均衡（而不是开不了局）
  const wrongTotal = await startWithCustom({ FOOTMAN: 6, ARCHER: 4 }, 'short');
  assert(!!wrongTotal.view, '配比不足 30 张仍能开局（回落预设）', wrongTotal.errs);
  if (wrongTotal.view) {
    const me = wrongTotal.view.players.find(p => p.side === wrongTotal.side);
    assert(me && me.deck === 'BALANCED', '非法配比回落为 BALANCED', me && me.deck);
    wrongTotal.ws.close();
    await sleep(250);
  }

  // 3. 超过单卡上限 → 同样回落
  const overCopies = await startWithCustom({
    FOOTMAN: 7, ARCHER: 6, KNIGHT: 6, FIREBALL: 6, SHIELD_GUARD: 5,
  }, 'over');
  assert(!!overCopies.view, '单卡超限仍能开局（回落预设）');
  if (overCopies.view) {
    const me = overCopies.view.players.find(p => p.side === overCopies.side);
    assert(me && me.deck === 'BALANCED', '单卡超限回落为 BALANCED', me && me.deck);
    overCopies.ws.close();
    await sleep(250);
  }

  // 4. 含衍生物 → 回落
  const withToken = await startWithCustom({
    WOLF_CUB: 6, FOOTMAN: 6, ARCHER: 6, KNIGHT: 6, FIREBALL: 6,
  }, 'token');
  if (withToken.view) {
    const me = withToken.view.players.find(p => p.side === withToken.side);
    assert(me && me.deck === 'BALANCED', '含衍生物的配比回落为 BALANCED', me && me.deck);
    withToken.ws.close();
  }

  console.log(failed ? '\n失败' : '\n全部通过');
  process.exitCode = failed ? 1 : 0;
})().catch(e => { console.error('异常:', e.message); process.exit(1); });
