// 每日任务真实链路：新身份全未完成 → 打真实对局推进进度 → 领取经验 → 重复领取被拒 → 任务经验计入成长。
// 用「建房 + 加电脑 + 立刻认输」快速产生对局记录；任务进度派生自记录，不依赖任何埋点。
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';

const NAME = '任务玩家' + Math.floor(Math.random() * 10000);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[quests] PASS ' + name); }
  else { failed++; console.error('[quests] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}
async function req(path, options = {}) {
  const r = await fetch(REST + path, options);
  let body = null;
  try { body = await r.json(); } catch (_) {}
  return { status: r.status, body };
}
const auth = (t) => ({ headers: { Authorization: 'Bearer ' + t } });

async function waitQuest(predicate, label, timeout = 8000) {
  const end = Date.now() + timeout;
  let last = null;
  while (Date.now() < end) {
    const r = await req('/api/quests', auth(token));
    if (r.status === 200) {
      last = r.body;
      if (predicate(r.body)) return r.body;
    }
    await sleep(250);
  }
  throw new Error('等待超时: ' + label + ' :: ' + JSON.stringify(last));
}

function quest(status, id) {
  return (status.quests || []).find(q => q.id === id);
}

/** 建房 → 加电脑 → 开局 → 认输，只为产生一条终局记录 */
function playSoloAndSurrender(gameType) {
  return new Promise((resolve) => {
    const st = { started: false, over: false, error: null };
    const ws = new WebSocket(WS_URL);
    let settled = false;
    const finish = (ok) => {
      if (settled) return;
      settled = true;
      try { ws.close(); } catch (_) {}
      resolve(Object.assign({ ok }, st));
    };
    ws.onopen = () => {
      ws.send(JSON.stringify({
        type: 'JOIN', playerName: NAME, gameType, createRoom: true, guestToken: token
      }));
      setTimeout(() => {
        try { ws.send(JSON.stringify({ type: 'ADD_AI', data: '1' })); } catch (_) {}
      }, 500);
    };
    ws.onmessage = (ev) => {
      let m; try { m = JSON.parse(ev.data); } catch { return; }
      if (m.type === 'ERROR') st.error = m.message;
      if (m.type === 'GAME_START') {
        st.started = true;
        setTimeout(() => {
          try { ws.send(JSON.stringify({ type: 'SURRENDER' })); } catch (_) {}
        }, 250);
      }
      if (m.type === 'GAME_OVER') {
        st.over = true;
        setTimeout(() => finish(true), 300);
      }
    };
    ws.onerror = () => finish(false);
    setTimeout(() => finish(st.over), 9000);
  });
}

let token = null;
(async () => {
  try {
  console.log('== 每日任务（真实 WS + REST）==');
  console.log('   REST=' + REST + ' WS=' + WS_URL);

  // 0. 未登录拒绝
  const anon = await req('/api/quests');
  check('未登录查询被拒(401)', anon.status === 401, 'status=' + anon.status);
  const anonClaim = await req('/api/quests/games-3/claim', { method: 'POST' });
  check('未登录领取被拒(401)', anonClaim.status === 401, 'status=' + anonClaim.status);

  // 1. 全新身份：5 个任务、全部不可领
  const guest = await req('/api/guest', { method: 'POST' });
  token = guest.body && guest.body.token;
  check('游客身份签发', guest.status === 200 && !!token);
  const fresh = await req('/api/quests', auth(token));
  check('任务列表结构完整', fresh.status === 200 && Array.isArray(fresh.body.quests)
    && fresh.body.quests.length === 5, JSON.stringify(fresh.body));
  check('新身份没有可领任务', fresh.body.claimableXp === 0
    && fresh.body.quests.every(q => !q.claimable && !q.claimed));
  check('新身份累计任务经验为 0', fresh.body.bonusXp === 0);
  const early = await req('/api/quests/games-3/claim', { method: 'POST', headers: auth(token).headers });
  check('未完成任务领取被拒(400)', early.status === 400, 'status=' + early.status + ' ' + JSON.stringify(early.body));
  const unknown = await req('/api/quests/no-such/claim', { method: 'POST', headers: auth(token).headers });
  check('未知任务领取被拒(404)', unknown.status === 404, 'status=' + unknown.status);

  // 2. 三局 GOMOKU（认输）→ 完成「热身三局」
  for (let i = 1; i <= 3; i++) {
    const game = await playSoloAndSurrender('GOMOKU');
    check('第 ' + i + ' 局单人局完成', game.ok && game.started && game.over,
      JSON.stringify({ error: game.error, started: game.started, over: game.over }));
    await sleep(300);
  }
  const afterThree = await waitQuest(s => s.todayGames >= 3, '三局记录进入任务统计');
  check('今日对局数达到 3', afterThree.todayGames === 3, 'games=' + afterThree.todayGames);
  check('三局任务变为可领', quest(afterThree, 'games-3').claimable === true);
  check('胜负任务未完成（三局全负）', quest(afterThree, 'win-1').claimable === false);

  // 3. 领取奖励，重复领取被拒
  const progressBefore = await req('/api/me/progress', auth(token));
  const xpBefore = progressBefore.body.xp;
  const claim = await req('/api/quests/games-3/claim', { method: 'POST', headers: auth(token).headers });
  check('领取三局任务成功', claim.status === 200 && claim.body.ok === true, JSON.stringify(claim.body));
  check('领取奖励为 30 经验', claim.body.rewardXp === 30, 'reward=' + claim.body.rewardXp);
  check('领取后状态标记已领', quest(claim.body.status, 'games-3').claimed === true);
  check('领取后累计任务经验为 30', claim.body.status.bonusXp === 30, 'bonus=' + claim.body.status.bonusXp);

  const again = await req('/api/quests/games-3/claim', { method: 'POST', headers: auth(token).headers });
  check('重复领取被拒(409)', again.status === 409, 'status=' + again.status + ' ' + JSON.stringify(again.body));

  const progressAfter = await req('/api/me/progress', auth(token));
  check('任务经验计入成长经验', progressAfter.body.xp === xpBefore + 30,
    xpBefore + ' -> ' + progressAfter.body.xp);
  check('任务经验不虚增对局数', progressAfter.body.totalGames === 3, 'totalGames=' + progressAfter.body.totalGames);

  // 4. 第二局玩法解锁「多面玩家」
  const other = await playSoloAndSurrender('CONNECT4');
  check('四子棋单人局完成', other.ok && other.over, JSON.stringify({ error: other.error, over: other.over }));
  const afterVariety = await waitQuest(s => s.todayGameTypes >= 2, '第二种玩法进入统计');
  check('玩法数达到 2', afterVariety.todayGameTypes === 2, 'types=' + afterVariety.todayGameTypes);
  check('多面玩家任务可领', quest(afterVariety, 'variety-2').claimable === true);
  const variety = await req('/api/quests/variety-2/claim', { method: 'POST', headers: auth(token).headers });
  check('领取多面玩家成功', variety.status === 200 && variety.body.rewardXp === 35, JSON.stringify(variety.body));
  check('累计任务经验叠加到 65', variety.body.status.bonusXp === 65, 'bonus=' + variety.body.status.bonusXp);

  // 5. 未解锁任务仍不可领，领取状态刷新后保持
  const multiplayer = await req('/api/quests/multiplayer-1/claim', { method: 'POST', headers: auth(token).headers });
  check('三人局任务未完成时不可领(400)', multiplayer.status === 400, 'status=' + multiplayer.status);
  const finalStatus = await req('/api/quests', auth(token));
  check('刷新后已领状态保持', quest(finalStatus.body, 'games-3').claimed === true
    && quest(finalStatus.body, 'variety-2').claimed === true);
  check('刷新后累计任务经验保持', finalStatus.body.bonusXp === 65, 'bonus=' + finalStatus.body.bonusXp);
  check('剩余可领任务不包含已领两项', finalStatus.body.claimableXp === 0,
    'claimable=' + finalStatus.body.claimableXp);
  } catch (error) {
    failed++;
    console.error('[quests] FAIL 异常 :: ' + (error && error.message));
  }
  console.log(failed === 0
    ? '[quests] PASS (' + passed + '/' + passed + ')'
    : '[quests] FAIL (' + failed + ' failed, ' + passed + ' passed)');
  process.exitCode = failed === 0 ? 0 : 1;
})();
