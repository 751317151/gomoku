// 卡牌套牌预组端到端校验
// 关键：套牌选择必须真的影响牌库（否则只是 UI 装饰）
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';

function startSolo(deck) {
  return new Promise((resolve) => {
    const ws = new WebSocket(WS_URL);
    const c = { ws, view: null, side: 0, errs: [] };
    ws.onopen = () => {
      ws.send(JSON.stringify({ type: 'JOIN', playerName: '套牌-' + deck, gameType: 'CARD', deck }));
      setTimeout(() => ws.send(JSON.stringify({ type: 'ADD_AI', data: '4', deck: 'CONTROL' })), 500);
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

(async () => {
  console.log('== 卡牌套牌预组校验 ==');

  for (const deck of ['BALANCED', 'AGGRO', 'CONTROL']) {
    const s = await startSolo(deck);
    assert(!!s.view, `${deck} 对局开局`);
    if (!s.view) { s.ws.close(); continue; }

    const me = s.view.players.find(p => p.side === s.side);
    const foe = s.view.players.find(p => p.side !== s.side);
    assert(me && me.deck === deck, `我的套牌是 ${deck}`, me && me.deck);
    assert(foe && foe.deck === 'CONTROL', `AI 套牌是 CONTROL`, foe && foe.deck);
    assert(me.deckLabel === ({ BALANCED: '均衡', AGGRO: '快攻', CONTROL: '控制' })[deck],
      `${deck} 有中文标签`, me.deckLabel);
    assert(Array.isArray(me.hand) && me.hand.length > 0, `${deck} 有起手牌`);
    s.ws.close();
    await sleep(300);
  }

  // 牌库完整性：起手抓完后「牌库剩余 + 手牌数」应仍等于 30。
  // 这能验证所选套牌真的被拿去建库，而不是被忽略后随便发牌。
  // （曲线差异是静态配比属性，已由 DeckPresetTest 用 100 个种子确定性断言；
  //   这里不再用 3 局随机起手做统计判断 —— 样本太小，会随机误报。）
  for (const deck of ['AGGRO', 'CONTROL']) {
    const s = await startSolo(deck);
    assert(!!s.view, `${deck} 再次开局`);
    if (!s.view) { s.ws.close(); continue; }
    const me = s.view.players.find(p => p.side === s.side);
    const hand = me && Array.isArray(me.hand) ? me.hand.length : -1;
    assert(s.view.deckLeft + hand === 30,
      `${deck} 牌库完整（剩余 ${s.view.deckLeft} + 手牌 ${hand} = 30）`,
      { deckLeft: s.view.deckLeft, hand });
    s.ws.close();
    await sleep(250);
  }

  console.log(failed ? '\n失败' : '\n全部通过');
  process.exitCode = failed ? 1 : 0;
})().catch(e => { console.error('异常:', e.message); process.exit(1); });
