// 真实身份的对局存档重启验收：游客凭证打一局 PvP → 查成长/战绩/列表 → 优雅重启 → 再查必须完全一致。
// 与 smoke-record.mjs 的区别：那条只验证「磁盘上有记录」，这条验证「同一游客身份在重启后仍能找回完整资产」。
// 自带隔离目录与动态端口，不接触真实 data/，也不依赖已启动的服务。
import { spawn } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { mkdirSync, mkdtempSync, openSync, closeSync, readFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:net';

const jar = resolve(process.env.JAR_PATH || fileURLToPath(new URL('../../target/gomoku-game-1.0-SNAPSHOT.jar', import.meta.url)));
if (!existsSync(jar)) throw new Error('先执行 mvn package，找不到打包 jar');
const output = resolve(process.env.OUT_DIR || join(dirname(jar), 'record-restart-smoke'));
mkdirSync(output, { recursive: true });
const runDir = mkdtempSync(join(output, 'run-'));
const dataDir = join(runDir, 'data');
const gamesDir = join(dataDir, 'games');
mkdirSync(gamesDir, { recursive: true });

let passed = 0, failed = 0, stage = 0, running = null;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(label, condition, detail) {
  if (condition) { passed++; console.log('[restart] PASS ' + label); }
  else { failed++; console.error('[restart] FAIL ' + label + (detail ? ' :: ' + detail : '')); }
}
async function availablePort() {
  const server = createServer();
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
const httpPort = await availablePort();
let wsPort;
do { wsPort = await availablePort(); } while (wsPort === httpPort);
const base = 'http://127.0.0.1:' + httpPort;
const wsUrl = 'ws://127.0.0.1:' + wsPort;

async function api(path, credential, method = 'GET') {
  const response = await fetch(base + path, {
    method, headers: credential ? { Authorization: 'Bearer ' + credential } : {},
    signal: AbortSignal.timeout(12000)
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
    cwd: runDir, windowsHide: true, stdio: ['ignore', fd, fd]
  });
  const context = { child, exited: false, error: null, done: null, logPath };
  context.done = new Promise(resolve => {
    child.once('error', error => { context.error = error; });
    child.once('close', () => { context.exited = true; closeSync(fd); resolve(); });
  });
  running = context;
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    if (context.exited || context.error) {
      throw new Error('测试服务未能启动: ' + (context.error?.message || readFileSync(logPath, 'utf8').slice(-1800)));
    }
    try {
      const health = await fetch(base + '/actuator/health', { signal: AbortSignal.timeout(1000) });
      if (health.ok && (await health.json()).status === 'UP') return;
    } catch (_) {}
    await sleep(150);
  }
  throw new Error('测试服务健康检查超时，日志：' + logPath);
}
async function waitForExit(context, timeoutMs) {
  let timer;
  try {
    await Promise.race([context.done, new Promise(resolve => { timer = setTimeout(resolve, timeoutMs); })]);
  } finally { clearTimeout(timer); }
}
async function stop() {
  const context = running;
  if (!context) return;
  if (!context.exited) {
    try { await api('/actuator/shutdown', null, 'POST'); } catch (_) {}
    await waitForExit(context, 15000);
    if (!context.exited) {
      context.child.kill();
      await waitForExit(context, 5000);
      if (!context.exited) throw new Error('测试子进程未退出，请检查 ' + context.logPath);
      throw new Error('测试服务未能优雅停机');
    }
  }
  running = null;
}

function connect(name, token) {
  return new Promise((resolve) => {
    const ws = new WebSocket(wsUrl);
    const state = { ws, name, token, stone: 0, turn: 1, seq: 0, board: Array.from({ length: 6 }, () => new Array(7).fill(0)), done: false };
    const startMove = () => {
      if (state.done || state.turn !== state.stone) return;
      const order = [3, 2, 4, 1, 5, 0, 6];
      for (const col of order) {
        for (let row = 5; row >= 0; row--) {
          if (state.board[row][col] === 0) {
            setTimeout(() => {
              try { ws.send(JSON.stringify({ type: 'MOVE', row, col, moveSeq: state.seq + 1 })); } catch (_) {}
            }, 80);
            return;
          }
        }
      }
    };
    ws.onopen = () => ws.send(JSON.stringify({
      type: 'JOIN', playerName: name, gameType: 'CONNECT4', guestToken: token
    }));
    ws.onmessage = ev => {
      let msg; try { msg = JSON.parse(ev.data); } catch { return; }
      if (msg.type === 'GAME_START') {
        state.stone = msg.stone;
        startMove();
      } else if (msg.type === 'GAME_MOVE') {
        if (msg.row >= 0) state.board[msg.row][msg.col] = msg.stone;
        if (typeof msg.moveSeq === 'number') state.seq = msg.moveSeq;
        let data = {}; try { data = JSON.parse(msg.data || '{}'); } catch (_) {}
        if (typeof data.turn === 'number') state.turn = data.turn;
        startMove();
      } else if (msg.type === 'GAME_OVER') {
        state.done = true;
        setTimeout(() => { try { ws.close(); } catch (_) {} resolve(state); }, 200);
      }
    };
    ws.onerror = () => { if (!state.done) resolve(state); };
    ws.onclose = () => { if (!state.done) resolve(state); };
  });
}

let before = null, recordId = null, detailMoves = 0;
try {
  await start();
  const a = await api('/api/guest', null, 'POST');
  const b = await api('/api/guest', null, 'POST');
  check('两个独立游客身份签发成功', a.token && b.token && a.id !== b.id);

  const suffix = randomBytes(3).toString('hex');
  const first = connect('重启甲' + suffix, a.token);
  await sleep(700);
  const second = connect('重启乙' + suffix, b.token);
  const [clientA, clientB] = await Promise.all([first, second]);
  check('真实 PvP 对局双方均收到终局', clientA.done && clientB.done && clientA.seq > 0);

  await sleep(1500);
  const games = await api('/api/games?limit=100&gameType=CONNECT4');
  const mine = games.find(game => (game.participants || []).some(p => p.name === '重启甲' + suffix));
  check('完局记录进入大厅列表', !!mine);
  if (mine) {
    recordId = mine.id;
    const detail = await api('/api/games/' + recordId);
    detailMoves = (detail.moveHistory || []).length;
    check('复盘详情含完整棋谱', detailMoves > 0 && detailMoves === mine.moveCount);
  }
  before = await api('/api/me/progress', a.token);
  const statsBefore = await api('/api/me/stats?gameType=CONNECT4', a.token);
  check('游客成长已计入本局', before.totalGames === 1 && before.xp === 75 && before.wins === 1,
    'progress=' + JSON.stringify({ xp: before.xp, totalGames: before.totalGames, wins: before.wins }));
  check('游客竞技战绩已计入且带 ELO', statsBefore.total === 1 && statsBefore.wins === 1
    && statsBefore.rankedTotal === 1 && statsBefore.elo > 0, JSON.stringify(statsBefore));
  await stop();
  check('第一次服务优雅停机', running === null);

  await start();
  const after = await api('/api/me/progress', a.token);
  const statsAfter = await api('/api/me/stats?gameType=CONNECT4', a.token);
  const gamesAfter = await api('/api/games?limit=100&gameType=CONNECT4');
  check('重启后成长资产逐字一致', JSON.stringify(after) === JSON.stringify(before));
  check('重启后竞技战绩与 ELO 一致', JSON.stringify(statsAfter) === JSON.stringify(statsBefore));
  check('重启后最近对局顺序与数量不变', gamesAfter.length === games.length && gamesAfter[0]?.id === games[0]?.id,
    'before=' + games.length + ' after=' + gamesAfter.length);
  if (recordId) {
    const detailAfter = await api('/api/games/' + recordId);
    check('重启后仍可按 ID 读取同一份复盘', (detailAfter.moveHistory || []).length === detailMoves);
  }
  check('SQLite 全量索引已落盘', existsSync(join(gamesDir, 'archive.sqlite'))
    && readFileSync(join(gamesDir, 'archive.sqlite')).length > 0);
  await stop();
  check('第二次服务优雅停机', running === null);
} catch (error) {
  failed++; console.error('[restart] FAIL ' + error.stack);
} finally {
  try { await stop(); } catch (error) { failed++; console.error('[restart] FAIL cleanup: ' + error.message); }
}
console.log('[restart] artifacts: ' + runDir);
console.log(failed ? '[restart] FAIL (' + failed + ' failed, ' + passed + ' passed)' : '[restart] PASS (' + passed + '/' + passed + ')');
process.exitCode = failed ? 1 : 0;
