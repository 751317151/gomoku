// 自带隔离目录和测试服务的历史资产验收：真实 jar、5005 条旧日志、账号升级、重启和索引重建。
// 先 mvn package；可用 JAVA_BIN 指定 Java 11，JAR_PATH/OUT_DIR/HISTORY_HTTP_PORT/HISTORY_WS_PORT 覆盖默认值。
import { spawn } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { mkdirSync, mkdtempSync, writeFileSync, appendFileSync, readFileSync, existsSync, renameSync, openSync, closeSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:net';

const jar = resolve(process.env.JAR_PATH || fileURLToPath(new URL('../../target/gomoku-game-1.0-SNAPSHOT.jar', import.meta.url)));
if (!existsSync(jar)) throw new Error('先执行 mvn package，找不到打包 jar');
const output = resolve(process.env.OUT_DIR || join(dirname(jar), 'history-smoke'));
mkdirSync(output, { recursive: true });
const runDir = mkdtempSync(join(output, 'run-'));
const dataDir = join(runDir, 'data');
const gamesDir = join(dataDir, 'games');
mkdirSync(gamesDir, { recursive: true });
const token = randomBytes(32).toString('base64url');
const identity = 'guest-history-' + randomBytes(8).toString('hex');
const adminToken = randomBytes(24).toString('hex');
const password = randomBytes(18).toString('base64url');
const username = 'hist' + randomBytes(5).toString('hex');
writeFileSync(join(dataDir, 'guests.json'), JSON.stringify({ [createHash('sha256').update(token).digest('base64')]: identity }));
let passed = 0, failed = 0, stage = 0, running = null;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(label, condition, detail) {
  if (condition) { passed++; console.log('[history] PASS ' + label); }
  else { failed++; console.error('[history] FAIL ' + label + (detail ? ' :: ' + detail : '')); }
}
async function availablePort(wanted = 0) {
  const server = createServer();
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(Number(wanted), '127.0.0.1', resolve); });
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
const httpPort = await availablePort(process.env.HISTORY_HTTP_PORT);
let wsPort;
do {
  wsPort = await availablePort(process.env.HISTORY_WS_PORT);
  if (wsPort === httpPort && process.env.HISTORY_WS_PORT) throw new Error('HTTP 与 WS 测试端口必须不同');
} while (wsPort === httpPort);
const base = 'http://127.0.0.1:' + httpPort;
async function api(path, credential, method = 'GET', body, extraHeaders = {}) {
  const response = await fetch(base + path, {
    method, headers: { ...(credential ? { Authorization: 'Bearer ' + credential } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}), ...extraHeaders },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(12000)
  });
  const result = await response.json();
  if (!response.ok) throw new Error(path + ' HTTP ' + response.status);
  return result;
}
async function start() {
  const logPath = join(runDir, 'server-' + (++stage) + '.log');
  const fd = openSync(logPath, 'a');
  const child = spawn(process.env.JAVA_BIN || 'java', ['-jar', jar,
    '--server.address=127.0.0.1', '--server.port=' + httpPort, '--netty.port=' + wsPort,
    '--management.endpoint.shutdown.enabled=true', '--management.endpoints.web.exposure.include=health,shutdown'], {
    cwd: runDir, windowsHide: true, env: { ...process.env, ADMIN_TOKEN: adminToken }, stdio: ['ignore', fd, fd]
  });
  const context = { child, exited: false, error: null, done: null, logPath };
  context.done = new Promise(resolve => {
    child.once('error', error => { context.error = error; });
    child.once('close', () => { context.exited = true; closeSync(fd); resolve(); });
  });
  running = context;
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    if (context.exited || context.error) throw new Error('测试服务未能启动: ' + (context.error?.message || readFileSync(logPath, 'utf8').slice(-1800)));
    try {
      const health = await fetch(base + '/actuator/health', { signal: AbortSignal.timeout(1000) });
      if (health.ok && (await health.json()).status === 'UP') return;
    } catch (_) {}
    await sleep(150);
  }
  throw new Error('测试服务健康检查超时，日志：' + logPath);
}
async function stop() {
  const context = running;
  if (!context) return;
  if (!context.exited) {
    try { await api('/actuator/shutdown', null, 'POST'); } catch (_) {}
    await waitForExit(context, 15000);
    if (!context.exited) {
      context.child.kill(); // 仅终止本脚本持有的子进程，不按进程名或端口清理其他服务。
      await waitForExit(context, 5000);
      if (!context.exited) throw new Error('测试子进程未退出，请检查 ' + context.logPath);
      throw new Error('测试服务未能优雅停机');
    }
  }
  running = null;
}

async function waitForExit(context, timeoutMs) {
  let timer;
  try {
    await Promise.race([context.done, new Promise(resolve => { timer = setTimeout(resolve, timeoutMs); })]);
  } finally { clearTimeout(timer); }
}

const timestamp = Math.max(Date.now() - 60000, new Date().setHours(0, 0, 0, 0));
function game(id, owner, name, gameType, result, endedAt = timestamp, ranked = true) {
  return { id, gameType, roomId: 'ROOM-HISTORY', startedAt: endedAt - 60000, endedAt, moveCount: 7, ranked,
    participants: [
      { playerId: 'p-' + id, identityId: owner, name, side: 1, result, ai: false, eloBefore: 1200, eloAfter: 1216, eloChange: 16 },
      { playerId: 'o-' + id, identityId: 'guest-history-other', name: '对手', side: 2, result: 'LOSE', ai: false, eloBefore: 1200, eloAfter: 1184, eloChange: -16 }
    ] };
}
const first = game('HISTORY-OLD-1', identity, '历史玩家', 'GOMOKU', 'WIN');
first.eventId = 'EXPIRED-EVENT'; first.eventXpPercent = 50;
const second = game('HISTORY-OLD-2', identity, '历史玩家', 'GOMOKU', 'WIN', timestamp + 1);
second.participants[0].eloAfter = 1232;
const third = game('HISTORY-OLD-3', identity, '历史玩家', 'UNO', 'WIN', timestamp + 2, false);
third.participants.push({ playerId: 'third', identityId: 'guest-history-third', name: '第三人', side: 3, result: 'LOSE', ai: false });
const fixtures = [first, second, third];
for (let i = 0; i < 5002; i++) fixtures.push(game('HISTORY-FILL-' + i, 'guest-filler', '其他玩家', 'REVERSI', 'LOSE', timestamp + 3 + i));
const logPath = join(gamesDir, 'index.jsonl');
writeFileSync(logPath, fixtures.map(record => JSON.stringify(record)).join('\n') + '\n');
writeFileSync(join(gamesDir, first.id + '.json'), JSON.stringify({ ...first, moveHistory: [[7, 7, 1], [7, 8, 2]] }));
const originalLog = readFileSync(logPath);

try {
  await start();
  check('Java打包服务完成旧存档迁移且HTTP/WS健康', true);
  const progress = await api('/api/me/progress', token);
  const stats = await api('/api/me/stats', token);
  check('超过5000局后历史经验仍为248', progress.xp === 248, 'xp=' + progress.xp);
  check('历史三连胜和玩法成就保留', progress.winStreak === 3
    && progress.achievements.some(a => a.id === 'mode-win-uno' && a.unlocked));
  check('完整战绩含旧3局且竞技休闲口径分开', stats.total === 3 && stats.wins === 3 && stats.rankedTotal === 2 && stats.casualTotal === 1);
  check('最新竞技ELO没有因重启反转', (await api('/api/me/stats?gameType=GOMOKU', token)).elo === 1232);
  check('每日任务不被大厅窗口截掉', (await api('/api/me/daily-progress', token)).todayGames === 3);
  const ops = await api('/api/admin/ops?days=7', null, 'GET', null, { 'X-Admin-Token': adminToken });
  check('运营窗口包含全5005局', ops.metrics.totalGames === 5005, 'games=' + ops.metrics.totalGames);
  check('旧复盘详情仍可读取', (await api('/api/games/' + first.id)).moveHistory.length === 2);
  check('升级过程未改写旧摘要日志', originalLog.equals(readFileSync(logPath)));
  const registered = await api('/api/account/register', token, 'POST', { username, password, nickname: '历史账号' });
  const accountProgress = await api('/api/me/progress', registered.token);
  check('游客升级账号延续全部历史资产', JSON.stringify(accountProgress) === JSON.stringify(progress));
  await stop();

  await start();
  const login = await api('/api/account/login', null, 'POST', { username, password });
  check('服务重启后跨设备登录仍能找回资产', JSON.stringify(await api('/api/me/progress', login.token)) === JSON.stringify(progress));
  check('重复导入没有重复发奖或战绩膨胀', (await api('/api/me/stats', login.token)).total === 3);
  await stop();

  // 模拟摘要已强制落盘、索引事务尚未提交就停机的窗口。
  appendFileSync(logPath, JSON.stringify(game('HISTORY-RECOVERED', identity, '历史账号', 'GOMOKU', 'WIN', timestamp + 6000)) + '\n');
  await start();
  const recovered = await api('/api/me/progress', token);
  check('重放补齐日志已提交而索引未提交的对局', recovered.xp === 298 && recovered.totalGames === 4);
  check('补齐后的最近对局顺序正确', (await api('/api/games?limit=1'))[0].id === 'HISTORY-RECOVERED');
  await stop();

  for (const suffix of ['', '-wal', '-shm']) {
    const path = join(gamesDir, 'archive.sqlite' + suffix);
    if (existsSync(path)) renameSync(path, path + '.before-rebuild');
  }
  await start();
  check('移走派生索引后可从原日志重建相同资产', JSON.stringify(await api('/api/me/progress', token)) === JSON.stringify(recovered));
  check('重建后战绩仍只计四局', (await api('/api/me/stats', token)).total === 4);
  await stop();
  check('所有测试服务已优雅停机', running === null);
} catch (error) {
  failed++; console.error('[history] FAIL ' + error.stack);
} finally {
  try { await stop(); } catch (error) { failed++; console.error('[history] FAIL cleanup: ' + error.message); }
}
console.log('[history] artifacts: ' + runDir);
console.log(failed ? '[history] FAIL (' + failed + ' failed, ' + passed + ' passed)' : '[history] PASS (' + passed + '/' + passed + ')');
process.exitCode = failed ? 1 : 0;
