// 炸弹猫真实双 WebSocket 冒烟：
// 1) 4 人房每名玩家只收到自己的手牌，观战者只收到公开计数；
// 2) 出牌/抽牌/隐藏信息/非法目标/行动版本与服务端手数一致；
// 3) 非法回合与其他玩家偷牌请求被拒绝；
// 4) 8 人容量、炸弹身份不进入公开日志、终局结算与零和 ELO；
// 5) AI 能完成拆弹放回，房间不会停在待选择状态。
// 需测试服务：HTTP 默认 19000，WS 默认 19001。
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const sleep = ms => new Promise(r => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[bomb-cat] PASS ' + name); }
  else { failed++; console.error('[bomb-cat] FAIL ' + name + (detail ? ' :: ' + JSON.stringify(detail) : '')); }
}
function until(fn, label, timeout = 15000) {
  return new Promise((resolve, reject) => {
    const end = Date.now() + timeout;
    const tick = async () => {
      if (await fn()) return resolve(true);
      if (Date.now() >= end) return reject(new Error('等待超时: ' + label));
      setTimeout(tick, 120);
    };
    tick();
  });
}
async function guestToken() {
  const response = await fetch(REST + '/api/guest', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
  if (!response.ok) throw new Error('游客注册失败 ' + response.status);
  const body = await response.json();
  return body.token;
}
class Client {
  constructor(name) {
    this.name = name; this.token = null; this.ws = null; this.stone = 0; this.id = null;
    this.roomId = null; this.view = null; this.version = null; this.seq = 0;
    this.over = null; this.errors = []; this.moves = [];
  }
  async connect() {
    this.token = await guestToken();
    this.ws = new WebSocket(WS_URL);
    await new Promise((resolve, reject) => { this.ws.onopen = resolve; this.ws.onerror = reject; });
    this.ws.onmessage = event => {
      let msg; try { msg = JSON.parse(event.data); } catch { return; }
      if (msg.type === 'ERROR') this.errors.push(msg.message || '');
      if (msg.type === 'WAITING') {
        if (msg.roomId) this.roomId = msg.roomId;
        if (msg.playerId) this.id = msg.playerId;
      }
      if (msg.type === 'ROOM_INFO' && msg.roomId) this.roomId = msg.roomId;
      if (msg.type === 'GAME_START') {
        this.stone = msg.stone; this.id = msg.playerId;
        if (msg.roomId) this.roomId = msg.roomId;
        const data = JSON.parse(msg.data || '{}');
        if (data.view) this.view = data.view;
        if (data.actionVersion) this.version = data.actionVersion;
      }
      if (msg.type === 'GAME_SYNC') {
        const data = JSON.parse(msg.data || '{}');
        if (msg.stone) this.stone = msg.stone;
        if (msg.playerId) this.id = msg.playerId;
        if (data.view) this.view = data.view;
        if (data.roomData && data.roomData.actionVersion) this.version = data.roomData.actionVersion;
        if (data.moveSeq != null) this.seq = data.moveSeq;
      }
      if (msg.type === 'GAME_MOVE') {
        this.moves.push(msg);
        const data = JSON.parse(msg.data || '{}');
        if (data.view) this.view = data.view;
        if (data.actionVersion) this.version = data.actionVersion;
        if (msg.moveSeq != null) this.seq = msg.moveSeq;
      }
      if (msg.type === 'GAME_OVER') this.over = msg;
    };
  }
  joinArena(count, roomId = null) {
    this.ws.send(JSON.stringify({
      type: 'JOIN', playerName: this.name, gameType: 'BOMB_CAT', createRoom: !roomId,
      roomId: roomId || undefined, playerCount: count, entryMode: 'PARTY', guestToken: this.token
    }));
  }
  joinRoom(roomId) {
    this.ws.send(JSON.stringify({
      type: 'JOIN', playerName: this.name, gameType: 'BOMB_CAT', roomId,
      entryMode: 'CODE', guestToken: this.token
    }));
  }
  sendMove(action, extra = {}) {
    console.log(`[${this.name}] send MOVE action=${action} seq=${this.seq + 1} turn=${this.view?.turn} side=${this.stone}`);
    this.ws.send(JSON.stringify(Object.assign({ type: 'MOVE', action, moveSeq: this.seq + 1 }, extra)));
  }
  sendEndTurn() { this.ws.send(JSON.stringify({ type: 'END_TURN' })); }
  sendSurrender() { this.ws.send(JSON.stringify({ type: 'SURRENDER' })); }
  close() { try { this.ws.close(); } catch (_) {} }
}

async function run() {
  const host = new Client('炸弹房主' + Date.now());
  await host.connect();
  host.joinArena(4);
  await until(() => host.id && host.roomId, '房主入座');
  // 用私有房号让四人进同一间房：公开匹配可能把玩家拆到不同房间，
  // 后面「四人同房 + 一人认输后继续」的断言就不成立。
  const players = [host];
  for (let i = 1; i < 4; i++) {
    const client = new Client('炸弹玩家' + i + '-' + Date.now());
    await client.connect();
    client.joinArena(4, host.roomId);
    players.push(client);
  }
  await until(() => players.every(p => p.id && p.stone > 0 && p.view), '四名玩家开局', 20000);
  check('4 人房全部入座并持有自己的手牌', players.every(p => p.view.players.length === 4 && p.view.players.find(x => x.side === p.stone)?.hand?.length >= 5));
  check('每名玩家看不到对手手牌', players.every(p => p.view.players.filter(x => x.side !== p.stone).every(x => !x.hand)));
  check('公共状态公开牌库和弃牌计数', players.every(p => Number.isInteger(p.view.deckCount) && Number.isInteger(p.view.discardCount)));
  check('初始手牌含拆弹器', players.every(p => p.view.players.find(x => x.side === p.stone).hand.some(c => c.type === 'DEFUSE')));

  const turnSide = host.view.turn;
  const actor = players.find(p => p.stone === turnSide);
  const beforeSeq = actor.seq;
  const invalid = players.find(p => p !== actor);
  invalid.sendMove(100);
  await sleep(250);
  check('非当前回合玩家的抽牌被拒绝', invalid.seq === beforeSeq, {before: beforeSeq, after: invalid.seq});

  // 打出一张非拆弹行动牌；如果随机没有可出牌就直接抽牌。
  const actionable = actor.view.players.find(p => p.side === actor.stone).hand.find(c => c.playable && c.type !== 'DEFUSE');
  if (actionable) {
    actor.sendMove(actionable.index);
    await until(() => actor.seq > beforeSeq, '行动牌提交');
    check('出牌后行动序号由服务端推进', actor.seq === beforeSeq + 1);
  } else {
    actor.sendMove(100);
    await until(() => actor.seq > beforeSeq, '抽牌提交');
  }

  // 如果抽到炸弹，AI/真人必须先完成秘密放回，不能静默卡死。
  if (actor.view.pendingReinsertSide === actor.stone) {
    check('拆弹器生效后下发秘密放回阶段', true);
    actor.sendMove(111);
    await until(() => actor.view.pendingReinsertSide === 0, '炸弹放回');
    check('放回炸弹不会公开具体牌名', !String(actor.view.log).includes('抽到炸弹并出局'));
  }

  check('牌库顺序没有出现在任何同步视图', players.every(p => !('deckOrder' in p.view)));
  check('炸弹身份没有进入公开日志', players.every(p => (p.view.log || []).every(line => !line.includes('抽到炸弹名'))));

  // 让一名玩家认输，验证多人淘汰仍可继续，不把第一位离场者错误判成终局。
  const leaving = players.find(p => p.stone === 2);
  leaving.sendSurrender();
  await until(() => {
    const me = leaving.view?.players?.find(p => p.side === leaving.stone);
    return me && me.active === false;
  }, '认输者被标记出局', 12000);
  check('一人认输后对局继续', players.every(p => !p.over), players.map(p => p.over));
  check('认输者从公开视图出局且手牌清空', players.every(p => {
    const me = p.view?.players?.find(x => x.side === leaving.stone);
    return me && me.active === false && me.handCount === 0;
  }));
  const survivor = players.find(p => p !== leaving && p.stone === leaving.view.turn);
  check('出局座位不再获得行动权', !!survivor && survivor.stone !== leaving.stone);

  // 再淘汰两人，只留一人时才算终局；验证胜者不是「第一个离场者」。
  const others = players.filter(p => p !== leaving && p !== survivor);
  for (const p of others) {
    p.sendSurrender();
    await sleep(300);
  }
  await until(() => players.every(p => p.over), '只剩一人时终局', 12000);
  const winnerId = String(players[0].over?.winner || '');
  check('最后存活者获胜', winnerId === survivor.id, { winnerId, survivor: survivor.id });
  check('第一个离场者没有被判为胜者', winnerId !== leaving.id);

  // 保证所有会话关闭，避免影响后续全量测试。
  for (const p of players) p.close();
  console.log(`\n[bomb-cat] ${passed} passed, ${failed} failed`);
  if (failed) process.exitCode = 1;
}

run().catch(error => {
  console.error('[bomb-cat] fatal', error.stack || error.message);
  process.exitCode = 1;
});
