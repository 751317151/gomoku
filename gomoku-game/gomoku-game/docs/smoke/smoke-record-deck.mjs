// 卡牌对局记录里的套牌信息端到端校验
// 关键：记录必须写「引擎实际生效」的套牌 —— 自定义配比非法时服务端会回落均衡，
// 若照抄玩家提交值，记录就会撒谎（写着自定义、实际打的均衡）。
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const HTTP_URL = process.env.HTTP_URL || 'http://127.0.0.1:19000';

const NAME = '套牌记录' + Math.floor(Math.random() * 1000);
const CUSTOM = { FOOTMAN: 6, ARCHER: 6, KNIGHT: 6, WAR_TITAN: 6, NECROMANCER: 6 };

const problems = [];
let roomId = null;
let gameOver = false;

function connect() {
  return new Promise((resolve) => {
    const ws = new WebSocket(WS_URL);
    const st = { ws, stone: 0, view: null, done: false, resolve };
    ws.onopen = () => {
      ws.send(JSON.stringify({
        type: 'JOIN', playerName: NAME, gameType: 'CARD',
        deck: 'CUSTOM', deckCards: JSON.stringify(CUSTOM),
      }));
      setTimeout(() => ws.send(JSON.stringify({ type: 'ADD_AI', data: '2', deck: 'AGGRO' })), 500);
    };
    ws.onmessage = (ev) => {
      let m; try { m = JSON.parse(ev.data); } catch { return; }
      if (m.type === 'GAME_START') {
        st.stone = m.stone;
        roomId = m.roomId;
      }
      if (m.data) {
        try {
          const d = JSON.parse(m.data);
          const v = d.view || (d.roomData && d.roomData.view);
          if (v) st.view = v;
        } catch {}
      }
      if (m.type === 'GAME_OVER') {
        st.done = true; gameOver = true;
        setTimeout(() => { try { ws.close(); } catch {} resolve(st); }, 300);
      }
    };
    setTimeout(() => resolve(st), 2500);
  });
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
function assert(cond, label, extra) {
  if (cond) { console.log(`  ✓ ${label}`); return true; }
  console.log(`  ✗ ${label}${extra ? ' — ' + JSON.stringify(extra) : ''}`);
  problems.push(label);
  return false;
}

(async () => {
  console.log('== 卡牌对局记录 · 套牌信息 ==');

  const st = await connect();
  assert(!!st.view, '对局已开始', { roomId });
  if (!st.view) { consoles(); return; }

  // 投降快速结束，避免等 AI 打完
  st.ws.send(JSON.stringify({ type: 'SURRENDER' }));
  for (let i = 0; i < 40 && !gameOver; i++) await sleep(250);
  assert(gameOver, '对局已结束（投降）');
  await sleep(1200); // 等异步存档落盘

  // 从最近对局里找这一局的记录
  let rec = null;
  for (let i = 0; i < 10 && !rec; i++) {
    const list = await (await fetch(`${HTTP_URL}/api/games?limit=30&gameType=CARD`)).json();
    rec = (Array.isArray(list) ? list : []).find(g =>
      g.roomId === roomId && (g.participants || []).some(p => p.name === NAME));
    if (!rec) await sleep(400);
  }
  assert(!!rec, '记录出现在 /api/games 索引里', { roomId });
  if (!rec) { consoles(); return; }

  const me = rec.participants.find(p => p.name === NAME);
  assert(me && me.deck === 'CUSTOM', '我的记录套牌为 CUSTOM', me && me.deck);
  assert(me && me.deckLabel === '自定义', '记录带中文标签「自定义」', me && me.deckLabel);
  assert(me && typeof me.deckSummary === 'string' && me.deckSummary.includes('战争泰坦×6'),
    '自定义配比摘要含新卡', me && me.deckSummary);

  const ai = rec.participants.find(p => p.name !== NAME);
  assert(ai && ai.deck === 'AGGRO', 'AI 记录套牌为 AGGRO', ai && ai.deck);
  assert(ai && ai.deckLabel === '快攻', 'AI 记录带中文标签「快攻」', ai && ai.deckLabel);
  assert(ai && !ai.deckSummary, '预组不写配比摘要', ai && ai.deckSummary);

  // 套牌筛选：任意一方实际使用该套牌即命中；先过滤再 limit，limit=1 也应命中本局
  const aggroList = await (await fetch(`${HTTP_URL}/api/games?limit=1&gameType=CARD&deck=AGGRO`)).json();
  assert(Array.isArray(aggroList) && aggroList.length === 1,
    'deck=AGGRO 在 limit=1 下仍能命中（先过滤后截断）', aggroList);
  assert(Array.isArray(aggroList) && aggroList.every(g =>
      (g.participants || []).some(p => p.deck === 'AGGRO')),
    'deck=AGGRO 返回的每局都至少一方使用快攻', aggroList);

  const controlList = await (await fetch(`${HTTP_URL}/api/games?limit=30&gameType=CARD&deck=CONTROL`)).json();
  assert(Array.isArray(controlList) && controlList.every(g =>
      (g.participants || []).some(p => p.deck === 'CONTROL')),
    'deck=CONTROL 不会混入其他套牌的对局', controlList);

  // 详情接口也要带（复盘弹窗读的是详情）
  const detail = await (await fetch(`${HTTP_URL}/api/games/${encodeURIComponent(rec.id)}`)).json();
  const dme = (detail.participants || []).find(p => p.name === NAME);
  assert(dme && dme.deckLabel === '自定义', '详情接口同样带套牌标签', dme && dme.deckLabel);

  consoles();
})().catch(e => { console.error('异常:', e.message); process.exit(1); });

function consoles() {
  console.log('  问题:', problems.length ? problems : '无');
  console.log(problems.length ? '失败' : '全部通过');
  // 与 smoke-rpg 同因：process.exit 打断 WebSocket 关闭握手会触发
  // Node 24/Windows 的 libuv 断言（退出码 127），把通过误报成失败。
  process.exitCode = problems.length ? 1 : 0;
}
