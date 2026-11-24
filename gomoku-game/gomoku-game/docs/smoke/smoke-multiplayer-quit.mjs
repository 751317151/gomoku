// 多人牌局退出语义冒烟：
// 1) UNO 四人局一人退出后其余继续，最后一人获胜；
// 2) 斗地主地主退出时两名农民共同获胜，不能整局作废；
// 3) 退出者手牌清空、不再收到行动权。
// 默认 HTTP 19000 / WS 19001。
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const sleep = ms => new Promise(r => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[multiplayer-quit] PASS ' + name); }
  else { failed++; console.error('[multiplayer-quit] FAIL ' + name + (detail ? ' :: ' + JSON.stringify(detail) : '')); }
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
  const r = await fetch(REST + '/api/guest', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
  if (!r.ok) throw new Error('游客注册失败 ' + r.status);
  return (await r.json()).token;
}
class Client {
  constructor(name) {
    this.name = name; this.token = null; this.ws = null; this.id = null; this.stone = 0;
    this.roomId = null; this.view = null; this.seq = 0; this.over = null; this.errors = [];
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
        this.id = msg.playerId; this.stone = msg.stone; if (msg.roomId) this.roomId = msg.roomId;
        const data = JSON.parse(msg.data || '{}');
        if (data.view) this.view = data.view;
      }
      if (msg.type === 'GAME_MOVE' || msg.type === 'GAME_SYNC') {
        const data = JSON.parse(msg.data || '{}');
        if (data.view) this.view = data.view;
        if (msg.moveSeq != null) this.seq = msg.moveSeq;
      }
      if (msg.type === 'GAME_OVER') this.over = msg;
    };
  }
  join(gameType, count, roomId = null) {
    this.ws.send(JSON.stringify({
      type: 'JOIN', playerName: this.name + Date.now(), gameType,
      createRoom: !roomId, roomId: roomId || undefined, playerCount: count, guestToken: this.token
    }));
  }
  surrender() { this.ws.send(JSON.stringify({ type: 'SURRENDER' })); }
  close() { try { this.ws.close(); } catch (_) {} }
}

async function joinRoom(gameType, count) {
  const host = new Client('host-' + gameType);
  await host.connect();
  host.join(gameType, count);
  await until(() => host.id && host.roomId, '房主入座');
  const players = [host];
  for (let i = 1; i < count; i++) {
    const client = new Client('p' + i + '-' + gameType);
    await client.connect();
    client.join(gameType, count, host.roomId);
    players.push(client);
  }
  await until(() => players.every(p => p.id && p.stone > 0 && p.view), '全员开局', 20000);
  return players;
}

async function unoQuit() {
  const players = await joinRoom('UNO', 4);
  const leaving = players[1];
  leaving.surrender();
  await until(() => {
    const me = leaving.view?.players?.find(p => p.side === leaving.stone);
    return me && me.active === false;
  }, '退出者被标记', 12000);
  check('UNO 一人退出后对局继续', players.every(p => !p.over), players.map(p => !!p.over));
  check('UNO 退出者手牌清空', players.every(p => {
    const me = p.view?.players?.find(x => x.side === leaving.stone);
    return me && me.active === false && me.handCount === 0;
  }));
  const survivors = players.filter(p => p !== leaving);
  for (const p of survivors) p.surrender();
  await until(() => players.every(p => p.over), '只剩一人时终局', 15000);
  check('UNO 最后存活者获胜', players.every(p => p.over && String(p.over.winner) !== 'draw'));
  for (const p of players) p.close();
}

async function doudizhuQuit() {
  const players = await joinRoom('DOUDIZHU', 3);
  const host = players[0];
  // 快速叫地主：座位 1 叫 3 分，其余不叫；保证地主确定再测退出。
  const bidder = players.find(p => p.stone === host.view.turn);
  bidder.ws.send(JSON.stringify({ type: 'MOVE', action: 3, moveSeq: bidder.seq + 1 }));
  await until(() => players.every(p => p.view && !p.view.bidding), '叫地主结束', 12000);
  const landlord = players.find(p => p.stone === host.view.landlord);
  landlord.surrender();
  await until(() => players.every(p => p.over), '地主退出后终局', 12000);
  const over = players[0].over || {};
  const winning = (over.data ? JSON.parse(over.data).winningSides : null) || [];
  check('斗地主地主退出后两名农民共同获胜', winning.length === 2 && !winning.includes(landlord.stone), { winning, landlord: landlord.stone });
  for (const p of players) p.close();
}

async function main() {
  await unoQuit();
  await doudizhuQuit();
  console.log(`\n[multiplayer-quit] ${passed} passed, ${failed} failed`);
  if (failed) process.exitCode = 1;
}
main().catch(e => { console.error('[multiplayer-quit] fatal', e.stack || e.message); process.exitCode = 1; });
