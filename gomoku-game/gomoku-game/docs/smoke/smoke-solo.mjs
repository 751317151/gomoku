// 单机模式冒烟：19 个玩法逐个「建房 + 加电脑」，断言都能直接开局
// 这条覆盖的是一个曾经的致命缺陷：一个人进房间后「添加电脑」按钮从不渲染，
// 导致所有玩法都只能干等另一个真人加入。
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';

const GAMES = [
  { type: 'GOMOKU', name: '五子棋' },
  { type: 'REVERSI', name: '黑白棋' },
  { type: 'GO9', name: '围棋' },
  { type: 'XIANGQI', name: '中国象棋' },
  { type: 'CHECKERS', name: '西洋跳棋' },
  { type: 'JUNGLE', name: '斗兽棋' },
  { type: 'DARK_CHESS', name: '暗棋' },
  { type: 'FLIGHT_CHESS', name: '飞行棋' },
  { type: 'CONNECT4', name: '四子棋' },
  { type: 'RPG', name: '回合制对战' },
  { type: 'CARD', name: '卡牌对战' },
  { type: 'NIM', name: '取石子' },
  { type: 'QUIZ', name: '答题对战' },
  { type: 'DICE_RACE', name: '骰子竞速' },
  { type: 'GEM_AUCTION', name: '宝石拍卖' },
  { type: 'BLACKJACK', name: '21 点' },
  { type: 'HIGH_CARD', name: '比大小' },
  { type: 'UNO', name: 'UNO 对战' },
  { type: 'BOMB_CAT', name: '炸弹猫' },
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failed = false;
function assert(cond, label, extra) {
  if (cond) { console.log(`    ✓ ${label}`); return true; }
  console.log(`    ✗ ${label}${extra ? ' — ' + JSON.stringify(extra) : ''}`);
  failed = true;
  return false;
}

/** 建一个连接，JOIN 指定玩法后立刻 ADD_AI，返回开局状态 */
function soloStart(gameType) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL);
    const st = { gameState: null, myStone: 0, view: null, gameType: null, errs: [] };

    ws.onopen = () => {
      // 多人玩法默认 4 人，单机冒烟只需要验证「人 + 电脑」能开局，
      // 因此显式要求 2 人容量；否则加 1 个电脑仍差 2 席，永远不开局。
      ws.send(JSON.stringify({ type: 'JOIN', playerName: 'solo-' + gameType, gameType, playerCount: 2 }));
      // 服务端需要房间先建好才接受 ADD_AI
      setTimeout(() => ws.send(JSON.stringify({ type: 'ADD_AI', data: '4' })), 450);
    };
    ws.onmessage = (ev) => {
      let m; try { m = JSON.parse(ev.data.toString()); } catch { return; }
      if (m.type === 'ERROR') st.errs.push(m.message);
      if (m.type === 'GAME_START') {
        st.gameState = 'PLAYING';
        st.myStone = m.stone || 0;
        st.gameType = m.gameType || gameType;
        if (m.data) {
          try {
            const d = JSON.parse(m.data);
            if (d.view) st.view = d.view;
          } catch {}
        }
      }
      if (m.type === 'GAME_SYNC' && m.data) {
        try {
          const d = JSON.parse(m.data);
          const v = d.view || (d.roomData && d.roomData.view);
          if (v) st.view = v;
        } catch {}
      }
    };
    ws.onerror = () => reject(new Error('WS 连接失败'));
    // 给足时间：卡牌要走 JOIN -> 450ms -> ADD_AI
    setTimeout(() => resolve({ ws, ...st }), 2600);
  });
}

(async () => {
  console.log('== 单机模式（人机对战）冒烟 ==');
  console.log(`   WS=${WS_URL}`);

  // 前置：服务可用
  try {
    const rooms = await (await fetch(`${REST}/api/rooms`)).json();
    assert(Array.isArray(rooms), 'REST 服务可用');
  } catch (e) {
    console.log('  ✗ REST 不可用: ' + e.message);
    process.exit(1);
  }

  const started = [];
  for (const g of GAMES) {
    console.log(`  检查 ${g.name} (${g.type})`);
    const s = await soloStart(g.type);
    try {
      assert(s.gameState === 'PLAYING', `${g.name} 建房后自动加电脑并开局`,
        { gameState: s.gameState, errs: s.errs });
      assert(s.myStone > 0, `${g.name} 分配到我方棋色`, { myStone: s.myStone });
      // 非棋盘玩法应该有 view
      if (['RPG', 'CARD', 'NIM', 'QUIZ', 'DICE_RACE', 'GEM_AUCTION', 'BLACKJACK', 'HIGH_CARD', 'UNO'].includes(g.type)) {
        assert(!!s.view, `${g.name} 开局即下发 view`, { hasView: !!s.view });
      }
      started.push(g.name);
    } finally {
      s.ws.close();
      await sleep(250);
    }
  }

  // 收尾：确认人机局没有污染 ELO（用一个独有昵称跑一局后查排行榜）
  const probeName = 'solo-probe-' + Date.now();
  const p = await new Promise((resolve) => {
    const ws = new WebSocket(WS_URL);
    const st = { over: null, myStone: 0 };
    ws.onopen = () => {
      ws.send(JSON.stringify({ type: 'JOIN', playerName: probeName, gameType: 'GOMOKU' }));
      setTimeout(() => ws.send(JSON.stringify({ type: 'ADD_AI', data: '2' })), 450);
    };
    ws.onmessage = (ev) => {
      let m; try { m = JSON.parse(ev.data.toString()); } catch { return; }
      if (m.type === 'GAME_START' && m.stone) st.myStone = m.stone;
      if (m.type === 'GAME_OVER') st.over = m;
    };
    // 认输以触发结算
    setTimeout(() => ws.send(JSON.stringify({ type: 'SURRENDER' })), 2200);
    setTimeout(() => resolve({ ws, ...st }), 3600);
  });
  p.ws.close();
  await sleep(400);

  const lb = await (await fetch(`${REST}/api/leaderboard?gameType=GOMOKU`)).json();
  const entry = (lb || []).find((x) => x.name === probeName);
  // 人机局不应写入 ELO 记录（或至少保持初始 1200）
  assert(!entry || entry.elo === 1200,
    '人机对局不写 ELO（或保持初始值）', { entry });
  console.log(`  已成功单机开局: ${started.length}/${GAMES.length} —— ${started.join('、')}`);

  console.log(failed ? '\n失败' : '\n全部通过');
  process.exitCode = failed ? 1 : 0;
})().catch((e) => { console.error('异常:', e.message); process.exit(1); });
