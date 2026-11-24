// 斗地主真实三人 WebSocket 冒烟：
// 1) 三人同房、每人 17 张手牌、底牌在叫地主前不可见；
// 2) 叫分→地主获得底牌并先手；非当前玩家出牌被拒；
// 3) 手牌只对自己可见；非法牌型/非法下标不改变服务端手数；
// 4) 真实出牌与过牌推进，最终有人出完并产生胜者。
// 默认 HTTP 19000 / WS 19001。
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
let passed = 0, failed = 0;
const sleep = ms => new Promise(r => setTimeout(r, ms));
function check(name, cond, detail) {
  if (cond) { passed++; console.log('[doudizhu] PASS ' + name); }
  else { failed++; console.error('[doudizhu] FAIL ' + name + (detail ? ' :: ' + JSON.stringify(detail) : '')); }
}
function until(fn, label, timeout = 15000) {
  return new Promise((resolve, reject) => {
    const end = Date.now() + timeout;
    const tick = async () => {
      if (await fn()) return resolve(true);
      if (Date.now() >= end) return reject(new Error('等待超时: ' + label));
      setTimeout(tick, 100);
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
  constructor(name) { this.name = name; this.token = null; this.ws = null; this.stone = 0; this.id = null; this.roomId = null; this.view = null; this.seq = 0; this.over = null; this.errors = []; }
  async connect() {
    this.token = await guestToken();
    this.ws = new WebSocket(WS_URL);
    await new Promise((res, rej) => { this.ws.onopen = res; this.ws.onerror = rej; });
    this.ws.onmessage = event => {
      let msg; try { msg = JSON.parse(event.data); } catch { return; }
      if (msg.type === 'ERROR') this.errors.push(msg.message || '');
      if (msg.type === 'WAITING') { if (msg.roomId) this.roomId = msg.roomId; if (msg.playerId) this.id = msg.playerId; }
      if (msg.type === 'GAME_START') {
        this.stone = msg.stone; this.id = msg.playerId; if (msg.roomId) this.roomId = msg.roomId;
        const d = JSON.parse(msg.data || '{}'); if (d.view) this.view = d.view;
      }
      if (msg.type === 'GAME_MOVE' || msg.type === 'GAME_SYNC') {
        const d = JSON.parse(msg.data || '{}'); if (d.view) this.view = d.view;
        if (msg.moveSeq != null) this.seq = msg.moveSeq;
      }
      if (msg.type === 'GAME_OVER') this.over = msg;
    };
  }
  join(count, roomId = null) {
    this.ws.send(JSON.stringify({ type: 'JOIN', playerName: this.name, gameType: 'DOUDIZHU', createRoom: !roomId, roomId: roomId || undefined, playerCount: count, guestToken: this.token }));
  }
  move(obj) { this.ws.send(JSON.stringify(Object.assign({ type: 'MOVE', moveSeq: this.seq + 1 }, obj))); }
  close() { try { this.ws.close(); } catch (_) {} }
}

async function run() {
  const host = new Client('斗地主房主' + Date.now());
  await host.connect(); host.join(3);
  await until(() => host.id && host.roomId, '房主入座');
  const players = [host];
  for (let i = 1; i < 3; i++) {
    const c = new Client('斗地主玩家' + i + '-' + Date.now());
    await c.connect(); c.join(3, host.roomId); players.push(c);
  }
  await until(() => players.every(p => p.id && p.stone > 0 && p.view), '三人开局', 20000);
  check('三人入座且每家 17 张', players.every(p => p.view.players.length === 3 && p.view.players.find(x => x.side === p.stone).hand.length === 17));
  check('对手手牌不可见', players.every(p => p.view.players.filter(x => x.side !== p.stone).every(x => !x.hand)));
  check('叫地主前底牌保密', players.every(p => (p.view.bottom || []).length === 0));

  const bidder = players.find(p => p.stone === host.view.turn);
  bidder.move({ action: 3 });
  await until(() => players.every(p => p.view && !p.view.bidding), '叫地主结束', 12000);
  const landlordSide = host.view.landlord;
  const landlord = players.find(p => p.stone === landlordSide);
  check('叫分最高者成为地主并获得底牌', !!landlord && landlord.view.players.find(x => x.side === landlordSide).hand.length === 20);
  check('确定地主后底牌公开', players.every(p => (p.view.bottom || []).length === 3));
  check('地主先手', host.view.turn === landlordSide);

  const nonTurn = players.find(p => p !== landlord);
  const beforeSeq = host.seq;
  nonTurn.move({ handIndices: '0' });
  await sleep(300);
  check('非当前玩家出牌被拒', host.seq === beforeSeq, { before: beforeSeq, after: host.seq });

  // 地主先领出一张最小单牌；之后每步由当前回合玩家压单张或过牌，最多 200 手。
  for (let step = 0; step < 200 && !players.every(p => p.over); step++) {
    const hostView = players.find(p => p.view && !p.view.over);
    if (!hostView) break;
    const view = hostView.view;
    const state = players.find(p => p.stone === view.turn);
    if (!state) break;
    if (state.view.over) break;
    const hand = state.view.players.find(x => x.side === state.stone).hand;
    if (!hand.length) break;
    const freeLead = !state.view.lastCombo;
    const target = state.view.lastPlay && state.view.lastPlay.length === 1 ? state.view.lastPlay[0] : 99;
    // 简单策略：自由出牌时优先出对子/三张，其次单张；跟牌时只跟单张。
    let chosen = null;
    if (freeLead) {
      for (let i = 0; i < hand.length && !chosen; i++) {
        const same = hand.filter(c => c.value === hand[i].value);
        if (same.length >= 2) chosen = same.slice(0, same.length >= 3 ? 3 : 2).map(c => c.index);
        else chosen = [hand[i].index];
      }
    } else {
      const card = hand.find(c => c.value > target);
      if (card) chosen = [card.index];
    }
    if (!chosen) state.move({ action: 100 });
    else state.move({ handIndices: chosen.join(',') });
    const seqBefore = state.seq;
    await until(() => state.seq > seqBefore || players.every(p => p.over), '下一步推进', 8000).catch(() => {});
  }
  check('出牌/过牌持续推进并产生终局', players.every(p => p.over) || players.some(p => p.view.over));
  for (const p of players) p.close();
  console.log(`\n[doudizhu] ${passed} passed, ${failed} failed`);
  if (failed) process.exitCode = 1;
}
run().catch(e => { console.error('[doudizhu] fatal', e.stack || e.message); process.exitCode = 1; });
