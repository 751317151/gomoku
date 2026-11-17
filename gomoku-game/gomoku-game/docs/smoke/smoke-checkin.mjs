// 签到留存 REST 冒烟：状态查询、首次签到、重复签到不重复发奖、经验计入等级、未登录拒绝。
// 默认 http://127.0.0.1:19000，可用 API_BASE 覆盖。
const base = process.env.API_BASE || 'http://127.0.0.1:19000';
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[checkin] PASS ' + name); }
  else { failed++; console.error('[checkin] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}
async function req(path, options = {}) {
  const r = await fetch(base + path, options);
  let body = null;
  try { body = await r.json(); } catch (_) {}
  return { status: r.status, body };
}
const auth = (t) => ({ headers: t ? { Authorization: 'Bearer ' + t } : {} });

try {
  // 准备一个游客身份
  const guest = await req('/api/guest', { method: 'POST' });
  check('游客身份签发', guest.status === 200 && !!guest.body.token);
  const token = guest.body.token;

  // 1. 未签到状态
  const before = await req('/api/checkin/status', auth(token));
  check('状态查询成功', before.status === 200, JSON.stringify(before.body));
  check('初始未签到', before.body.signedToday === false);
  check('初始预期连续为 1', before.body.streak === 1, 'streak=' + before.body.streak);
  check('下一里程碑为 3', before.body.nextMilestone === 3, 'next=' + before.body.nextMilestone);

  // 2. 首次签到发基础奖励
  const first = await req('/api/checkin', { method: 'POST', headers: auth(token).headers });
  check('首次签到成功', first.status === 200 && first.body.alreadySigned === false);
  check('首次签到 +15 经验', first.body.rewardXp === 15, 'reward=' + first.body.rewardXp);
  check('首次签到连续 1 天', first.body.streak === 1);

  // 3. 重复签到不重复发奖
  const again = await req('/api/checkin', { method: 'POST', headers: auth(token).headers });
  check('重复签到标记 alreadySigned', again.body.alreadySigned === true);
  check('重复签到不发奖', again.body.rewardXp === 0, 'reward=' + again.body.rewardXp);
  check('累计经验不翻倍', again.body.bonusXp === 15, 'bonusXp=' + again.body.bonusXp);

  // 4. 状态变为已签到
  const after = await req('/api/checkin/status', auth(token));
  check('签到后状态为已签', after.body.signedToday === true);

  // 5. 签到经验计入成长等级（/api/me/progress 的 xp 应含 15）
  const progress = await req('/api/me/progress', auth(token));
  check('成长接口可用', progress.status === 200);
  check('签到经验计入等级经验', progress.body.xp >= 15, 'xp=' + progress.body.xp);
  check('签到不虚增对局数', (progress.body.totalGames || 0) === 0, 'totalGames=' + progress.body.totalGames);

  // 6. 未登录拒绝
  const anon = await req('/api/checkin', { method: 'POST' });
  check('未登录签到被拒(401)', anon.status === 401, 'status=' + anon.status);
  const anonStatus = await req('/api/checkin/status');
  check('未登录状态查询被拒(401)', anonStatus.status === 401, 'status=' + anonStatus.status);
} catch (error) {
  failed++;
  console.error('[checkin] FAIL 异常 :: ' + (error && error.message));
}
console.log(failed === 0
  ? '[checkin] PASS (' + passed + '/' + passed + ')'
  : '[checkin] FAIL (' + failed + ' failed, ' + passed + ' passed)');
process.exitCode = failed === 0 ? 0 : 1;
