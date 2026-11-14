// 回合制 RPG 对战端到端验证
//   1. GAME_START 就带完整战斗状态（血条/技能栏不用等第一手）
//   2. 每手 GAME_MOVE 的 view 都反映真实扣血与轮次切换
//   3. 战斗能正常分出胜负（不会无限互奶）
//   4. 对局记录按 RPG 类型落盘，棋谱是 {side, action, value}
//   5. 战绩 / 排行榜接口对 RPG 可用，且 AI 不会混进真人榜
//
// 用法：node docs/smoke/smoke-rpg.mjs
//   WS_URL / HTTP_URL 可用环境变量覆盖
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const HTTP_URL = process.env.HTTP_URL || 'http://127.0.0.1:19000';

const NAME_A = '对战甲' + Math.floor(Math.random() * 1000);
const NAME_B = '对战乙' + Math.floor(Math.random() * 1000);

const problems = [];
let gameOverSeen = 0;
let sawStartView = false;
let sawHpDrop = false;
let sawTurnSwitch = false;

// 与 RpgBattleEngine.Action 下标一致
const ACT = { ATTACK: 0, HEAVY: 1, FIREBALL: 2, HEAL: 3, DEFEND: 4, POTION: 5 };

/**
 * 用 view 里的技能可用性挑一个行动。
 * 故意不追求最优：这里验的是协议与流程，不是 AI 强度。
 */
function pickAction(view) {
  const me = view.fighters.find(f => f.side === view.turn);
  const foe = view.fighters.find(f => f.side !== view.turn);
  const usable = (i) => (view.actions || []).some(a => a.index === i && a.available);

  if (me.hp <= 40 && usable(ACT.HEAL)) return ACT.HEAL;
  if (me.hp <= 35 && usable(ACT.POTION)) return ACT.POTION;
  if (foe.hp <= 22 && usable(ACT.HEAVY)) return ACT.HEAVY;
  if (usable(ACT.FIREBALL)) return ACT.FIREBALL;
  if (usable(ACT.HEAVY)) return ACT.HEAVY;
  return ACT.ATTACK;
}

function connect(name) {
  return new Promise((resolve) => {
    const ws = new WebSocket(WS_URL);
    const state = {
      ws, name, stone: 0, turn: 1, seq: 0, done: false,
      pending: false, moves: 0, lastHp: null, resolve
    };

    const scheduleAction = () => {
      if (state.pending || state.done) return;
      const view = state.view;
      if (!view || view.over) return;
      if (view.turn !== state.stone) return;
      state.pending = true;
      setTimeout(() => {
        state.pending = false;
        if (state.done || !state.view || state.view.over) return;
        if (state.view.turn !== state.stone) return;
        try {
          ws.send(JSON.stringify({
            type: 'MOVE',
            action: pickAction(state.view),
            moveSeq: state.seq + 1
          }));
        } catch {}
      }, 120);
    };
    state.scheduleAction = scheduleAction;

    ws.onopen = () => {
      ws.send(JSON.stringify({ type: 'JOIN', playerName: name, gameType: 'RPG' }));
    };

    ws.onmessage = (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }

      if (msg.type === 'WAITING') return;

      if (msg.type === 'GAME_START') {
        state.stone = msg.stone;
        let data = {};
        try { data = JSON.parse(msg.data || '{}'); } catch {}
        state.turn = data.currentTurn || 1;
        state.view = data.view || null;

        if (state.view && Array.isArray(state.view.fighters) && state.view.fighters.length === 2) {
          sawStartView = true;
          const me = state.view.fighters.find(f => f.side === state.stone);
          if (!me) problems.push('GAME_START 的 view 里找不到自己那一方');
          else state.lastHp = me.hp;
          if (!Array.isArray(state.view.actions) || state.view.actions.length === 0) {
            problems.push('GAME_START 的 view 缺少技能栏');
          }
        } else {
          problems.push('GAME_START 没有携带 view（面板会空到第一手）');
        }
        scheduleAction();
        return;
      }

      if (msg.type === 'GAME_MOVE') {
        if (typeof msg.moveSeq === 'number') state.seq = msg.moveSeq;
        state.moves++;
        let data = {};
        try { data = JSON.parse(msg.data || '{}'); } catch {}
        if (data.view) state.view = data.view;
        if (typeof data.turn === 'number') {
          if (data.turn !== state.turn) sawTurnSwitch = true;
          state.turn = data.turn;
        }
        // 校验血条真的在动：只看自己那一方，避免对手治疗干扰判断
        const me = state.view && state.view.fighters
          ? state.view.fighters.find(f => f.side === state.stone) : null;
        if (me && state.lastHp !== null && me.hp !== state.lastHp) sawHpDrop = true;
        if (me) state.lastHp = me.hp;
        scheduleAction();
        return;
      }

      if (msg.type === 'GAME_OVER') {
        state.done = true;
        gameOverSeen++;
        state.overMsg = msg;
        setTimeout(() => {
          try { ws.close(); } catch {}
          state.resolve(state);
        }, 300);
      }
    };

    ws.onclose = () => { if (!state.done) state.resolve(state); };
    state.ws = ws;
  });
}

async function httpJson(path) {
  const r = await fetch(HTTP_URL + path);
  if (!r.ok) throw new Error(path + ' -> HTTP ' + r.status);
  return r.json();
}

(async () => {
  const pA = connect(NAME_A);
  await new Promise(r => setTimeout(r, 700));
  const pB = connect(NAME_B);
  const [a, b] = await Promise.all([pA, pB]);

  console.log('[rpg] 双方结束，A 手数=' + a.moves + ' B 手数=' + b.moves);
  if (gameOverSeen < 2) problems.push('应收到两次 GAME_OVER，实际 ' + gameOverSeen);
  if (!sawStartView) problems.push('开局没有完整战斗状态');
  if (!sawHpDrop) problems.push('全程血条没有变化（伤害没生效？）');
  if (!sawTurnSwitch) problems.push('轮次从未切换（可能一直被同一方出手）');

  const over = a.overMsg || b.overMsg;
  if (over) {
    let data = {};
    try { data = JSON.parse(over.data || '{}'); } catch {}
    const moves = data.moveHistory || [];
    console.log('[rpg] 终局: winner=' + over.winner + ' 手数=' + moves.length + ' 说明=' + over.message);
    if (moves.length === 0) problems.push('终局没有棋谱');
    if (moves.length > 0 && moves[0].length !== 3) {
      problems.push('棋谱编码应为 {side, action, value}，实际长度 ' + moves[0].length);
    }
    if (over.winner === 'draw') problems.push('这局不该是平局（双方都在输出）');
    if (!data.scores || data.scores.length !== 2) problems.push('终局缺少双方分数');
  } else {
    problems.push('没有拿到 GAME_OVER 消息');
  }

  // 落盘异步，给写线程一点时间
  await new Promise(r => setTimeout(r, 1500));

  // 1) 对局列表按 RPG 过滤
  let games = [];
  try {
    games = await httpJson('/api/games?limit=20&gameType=RPG');
  } catch (e) {
    problems.push('RPG 对局列表接口失败: ' + e.message);
  }
  const mine = games.filter(g => {
    const names = (g.participants || []).map(p => p.name);
    return names.includes(NAME_A) && names.includes(NAME_B);
  });
  if (mine.length === 0) {
    problems.push('RPG 对局没有落盘（列表里找不到）');
  } else {
    const rec = mine[0];
    console.log('[rpg] 命中记录:', rec.id, '| 类型:', rec.gameType,
      '| 手数:', rec.moveCount, '| 计分:', rec.ranked);
    if (rec.gameType !== 'RPG') problems.push('记录类型应为 RPG，实际 ' + rec.gameType);
    if (rec.moveCount <= 0) problems.push('记录手数异常: ' + rec.moveCount);
    if (!rec.ranked) problems.push('双真人 RPG 局应标记 ranked=true');

    try {
      const detail = await httpJson('/api/games/' + rec.id);
      const hist = detail.moveHistory || [];
      console.log('[rpg] 详情棋谱:', hist.length, '手，首手 =', JSON.stringify(hist[0]));
      if (hist.length === 0) problems.push('详情里没有棋谱');
      const sides = new Set(hist.map(m => m[0]));
      if (sides.size !== 2) problems.push('棋谱应包含双方行动，实际只有 ' + [...sides].join(','));
    } catch (e) {
      problems.push('详情接口失败: ' + e.message);
    }
  }

  // 2) 战绩按 RPG 统计
  try {
    const stats = await httpJson('/api/stats/player/' + encodeURIComponent(NAME_A) + '?gameType=RPG');
    console.log('[rpg] 甲的战绩:', JSON.stringify(stats));
    if (stats.total < 1) problems.push('RPG 战绩应有至少 1 局，实际 ' + stats.total);
  } catch (e) {
    problems.push('RPG 战绩接口失败: ' + e.message);
  }

  // 3) RPG 排行榜可用且不含 AI
  try {
    const board = await httpJson('/api/leaderboard?gameType=RPG');
    const list = Array.isArray(board) ? board : [];
    console.log('[rpg] RPG 排行榜条目数:', list.length);
    if (list.some(e => e.name === '电脑' || String(e.name).startsWith('AI-'))) {
      problems.push('AI 出现在 RPG 真人排行榜里');
    }
  } catch (e) {
    problems.push('RPG 排行榜接口失败: ' + e.message);
  }

  console.log('[rpg] 问题:', problems.length ? problems : '无');
  console.log(problems.length === 0 ? '[rpg] PASS' : '[rpg] FAIL');
  // 不用 process.exit()：Node 24/Windows 上，若退出时 WebSocket 关闭握手
  // 还没走完，libuv 会断言崩溃（exit code 127），把通过的用例误报成失败。
  // 设置 exitCode 让事件循环自然收尾即可（超时定时器已 unref，不会拖住进程）。
  process.exitCode = problems.length === 0 ? 0 : 1;
})();

setTimeout(() => {
  console.log('[rpg] 问题: 超时（对局没有在 120 秒内结束）');
  console.log('[rpg] FAIL');
  process.exit(1);
}, 120000).unref?.();
