// 赛季段位 REST 冒烟：赛季信息、未登录匿名、段位映射、赛季分只算真人。
// 默认 http://127.0.0.1:19000，可用 API_BASE 覆盖。
const base = process.env.API_BASE || 'http://127.0.0.1:19000';
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[season] PASS ' + name); }
  else { failed++; console.error('[season] FAIL ' + name + (detail ? ' :: ' + detail : '')); }
}
async function req(path, options = {}) {
  const r = await fetch(base + path, options);
  let body = null;
  try { body = await r.json(); } catch (_) {}
  return { status: r.status, body };
}
const auth = (t) => ({ headers: t ? { Authorization: 'Bearer ' + t } : {} });

try {
  // 1. 未登录也能看赛季榜（me 为空战绩）
  const anon = await req('/api/season?limit=10');
  check('匿名可查赛季', anon.status === 200, JSON.stringify(anon.body));
  check('赛季有 id 与 label', !!anon.body.id && !!anon.body.label, JSON.stringify({ id: anon.body.id, label: anon.body.label }));
  check('赛季剩余天数合理', typeof anon.body.daysLeft === 'number' && anon.body.daysLeft >= 0, 'daysLeft=' + anon.body.daysLeft);
  check('匿名 me 为 0 分青铜', anon.body.me && anon.body.me.points === 0 && anon.body.me.tier === '青铜', JSON.stringify(anon.body.me));
  check('榜单是数组', Array.isArray(anon.body.leaderboard));

  // 2. 带游客身份查询
  const guest = await req('/api/guest', { method: 'POST' });
  const token = guest.body.token;
  const mine = await req('/api/season?limit=10', auth(token));
  check('带身份查赛季成功', mine.status === 200);
  check('带身份 me 含段位字段', mine.body.me && mine.body.me.tier && typeof mine.body.me.tierProgress === 'number', JSON.stringify(mine.body.me));
  check('新玩家赛季分为 0', mine.body.me.points === 0, 'points=' + mine.body.me.points);

  // 3. 段位映射完整性：榜单每行都应带 tier 与 points
  const board = mine.body.leaderboard || [];
  const boardOk = board.every(row => typeof row.points === 'number' && typeof row.tier === 'string');
  check('榜单每行含 points 与 tier', boardOk, JSON.stringify(board.slice(0, 3)));
} catch (error) {
  failed++;
  console.error('[season] FAIL 异常 :: ' + (error && error.message));
}
console.log(failed === 0
  ? '[season] PASS (' + passed + '/' + passed + ')'
  : '[season] FAIL (' + failed + ' failed, ' + passed + ' passed)');
process.exitCode = failed === 0 ? 0 : 1;
