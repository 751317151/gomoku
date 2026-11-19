// 运营看板冒烟：鉴权边界 + 指标结构 + AI 局不计活跃 + 真实对局后计数增长。
// 前置：服务端需配置 ADMIN_TOKEN（默认 smoke-admin-token）。
const base = process.env.API_BASE || 'http://127.0.0.1:19000';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || 'smoke-admin-token';
let passed = 0;
let failed = 0;

function check(name, condition, detail) {
  if (condition) { passed++; console.log('[ops] PASS ' + name); }
  else { failed++; console.error('[ops] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}

async function request(path, options = {}) {
  const response = await fetch(base + path, options);
  let body = null;
  try { body = await response.json(); } catch (_) {}
  return { status: response.status, body };
}

const admin = extra => ({ headers: Object.assign({ 'X-Admin-Token': ADMIN_TOKEN }, extra || {}) });

function wsJoin(name, token) {
  return new Promise(resolve => {
    const ws = new WebSocket(WS_URL);
    const st = { ws, errors: [], state: null };
    ws.onopen = () => ws.send(JSON.stringify({ type: 'JOIN', playerName: name, gameType: 'GOMOKU', guestToken: token }));
    ws.onmessage = ev => {
      let m; try { m = JSON.parse(ev.data.toString()); } catch { return; }
      if (m.type === 'WAITING' || m.type === 'GAME_START') st.state = m.type;
      if (m.type === 'ERROR') st.errors.push(m.message);
    };
    const poll = setInterval(() => {
      if (st.state || st.errors.length) { clearInterval(poll); resolve(st); }
    }, 100);
    setTimeout(() => { clearInterval(poll); resolve(st); }, 15000);
  });
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

try {
  // 1. 鉴权边界
  const anon = await request('/api/admin/ops');
  check('无令牌访问看板被拒', anon.status === 403 || anon.status === 503, 'status=' + anon.status);
  const wrong = await request('/api/admin/ops', { headers: { 'X-Admin-Token': 'wrong-token' } });
  check('错误令牌被拒(403)', wrong.status === 403, 'status=' + wrong.status);

  // 2. 正确令牌可读，结构完整
  const first = await request('/api/admin/ops?days=7', admin());
  check('管理员可读看板', first.status === 200, 'status=' + first.status);
  const metrics = first.body.metrics || {};
  check('实时在线数据存在', first.body.realtime && typeof first.body.realtime.totalPlayers === 'number',
    JSON.stringify(first.body.realtime));
  check('窗口为 7 天', metrics.windowDays === 7, String(metrics.windowDays));
  check('趋势按天补齐', Array.isArray(metrics.trend) && metrics.trend.length === 7,
    JSON.stringify(metrics.trend));
  check('留存字段存在', metrics.retention && metrics.retention.cohortDate,
    JSON.stringify(metrics.retention));
  check('口径说明随数据下发', Array.isArray(metrics.definitions) && metrics.definitions.length > 0,
    JSON.stringify(metrics.definitions));

  const gamesBefore = metrics.totalGames;

  // 3. AI 局不计入活跃：单人 + 添加电脑打一局
  const solo = await newGuestToken();
  const wsSolo = await wsJoin('看板人机', solo);
  if (wsSolo.state) {
    wsSolo.ws.send(JSON.stringify({ type: 'ADD_AI', data: '1' }));
    await sleep(1200);
  }
  try { wsSolo.ws.close(); } catch (_) {}
  await sleep(400);
  const afterAi = (await request('/api/admin/ops?days=7', admin())).body.metrics;
  check('人机局不增加真人活跃对局', afterAi.totalGames === gamesBefore,
    JSON.stringify({ before: gamesBefore, after: afterAi.totalGames }));
  check('人机局不增加今日活跃', (afterAi.todayActive || 0) === (metrics.todayActive || 0),
    JSON.stringify({ before: metrics.todayActive, after: afterAi.todayActive }));

  // 4. 真人局计入：必须真正打完一局才会产生对局记录（只开局不落子不算）
  const p1 = await newGuestToken();
  const p2 = await newGuestToken();
  const finished = await playQuickGame(p1, p2);
  check('真人双人对局打完一局', finished, '对局未在时限内结束');
  await sleep(900); // 等异步落盘进入内存索引
  const afterPvp = (await request('/api/admin/ops?days=7', admin())).body.metrics;
  check('真人局计入窗口对局数', afterPvp.totalGames > gamesBefore,
    JSON.stringify({ before: gamesBefore, after: afterPvp.totalGames }));
  const typeRow = (afterPvp.gameTypes || []).find(r => r.gameType === 'CONNECT4');
  check('玩法热度包含 CONNECT4 且带占比', !!typeRow && typeof typeRow.share === 'number',
    JSON.stringify(typeRow));
  check('时段分布为 8 段', (afterPvp.hourly || []).length === 8, JSON.stringify(afterPvp.hourly));
} catch (error) {
  failed++;
  console.error('[ops] FAIL 异常 :: ' + (error && error.message));
}

async function newGuestToken() {
  const guest = await request('/api/guest', { method: 'POST' });
  return guest.body.token;
}

/**
 * 两个真人打完一局四子棋（重力落子，策略固定）。
 * 只「开局」不会写对局记录——记录是终局产物，看板计数必须靠真打完。
 * 协议要点（与 smoke-record.mjs 一致）：GAME_START 不带 GAME_MOVE，先手须自己起手；
 * 轮次来自 GAME_START 的 turn=1 与每手 GAME_MOVE 的 data.turn。
 */
function playQuickGame(t1, t2) {
  return new Promise(resolve => {
    const conns = [];
    let over = false;
    const finish = ok => {
      if (over) return;
      over = true;
      conns.forEach(c => { try { c.ws.close(); } catch (_) {} });
      setTimeout(() => resolve(ok), 250);
    };

    const make = (name, token) => {
      const st = { ws: new WebSocket(WS_URL), stone: 0, seq: 0, turn: 1,
                   board: Array.from({ length: 6 }, () => new Array(7).fill(0)), pending: false };
      st.ws.onopen = () => st.ws.send(JSON.stringify({
        type: 'JOIN', playerName: name, gameType: 'CONNECT4', guestToken: token
      }));
      st.ws.onmessage = ev => {
        let m; try { m = JSON.parse(ev.data.toString()); } catch { return; }
        if (m.type === 'GAME_START') {
          st.stone = m.stone;
          try { st.board = JSON.parse(m.data).board || st.board; } catch {}
          st.turn = 1;
          schedule(st);
        } else if (m.type === 'GAME_MOVE') {
          // GAME_MOVE 不带宽盘面，只带这一手的 row/col/stone，本地自己落子维护棋盘
          if (m.row >= 0 && m.col >= 0) st.board[m.row][m.col] = m.stone;
          if (typeof m.moveSeq === 'number') st.seq = m.moveSeq;
          let data = {}; try { data = JSON.parse(m.data || '{}'); } catch {}
          if (typeof data.turn === 'number') st.turn = data.turn;
          schedule(st);
        } else if (m.type === 'GAME_OVER') {
          finish(true);
        } else if (m.type === 'ERROR' && /失败/.test(m.message || '')) {
          finish(false);
        }
      };
      conns.push(st);
      return st;
    };

    /** 轮到自己就落一手（在途标记避免重复发送） */
    function schedule(st) {
      if (over || st.pending || st.turn !== st.stone) return;
      st.pending = true;
      setTimeout(() => {
        st.pending = false;
        if (over || st.turn !== st.stone) return;
        const cell = planMove(st.board);
        if (!cell) { finish(false); return; }
        try {
          st.ws.send(JSON.stringify({ type: 'MOVE', row: cell.row, col: cell.col, moveSeq: st.seq + 1 }));
        } catch (_) { finish(false); }
      }, 120);
    }

    // 列优先级：中间优先，其次两侧，双方按此顺序填满，最终必有一方连成四子
    function planMove(board) {
      const order = [3, 2, 4, 1, 5, 0, 6];
      for (const c of order) {
        for (let r = 5; r >= 0; r--) if (board[r][c] === 0) return { row: r, col: c };
      }
      return null;
    }

    make('看板甲', t1);
    setTimeout(() => make('看板乙', t2), 500);
    setTimeout(() => finish(false), 25000);
  });
}

console.log(failed === 0
  ? '[ops] PASS (' + passed + '/' + passed + ')'
  : '[ops] FAIL (' + failed + ' failed, ' + passed + ' passed)');
process.exitCode = failed === 0 ? 0 : 1;
