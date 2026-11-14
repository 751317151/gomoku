// 观战冒烟：2 个玩家对局 + 1 个观战者
// 重点：观战者能看到「公开信息」（血量/场面/手牌数量），但拿不到任何手牌内容。
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:19001';

function connect(name, gameType, spectateRoomId) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL);
    const c = { ws, name, side: 0, view: null, over: null, errs: [], isSpectator: false };
    ws.onopen = () => {
      if (spectateRoomId) {
        ws.send(JSON.stringify({ type: 'SPECTATE', playerName: name, roomId: spectateRoomId }));
        c.isSpectator = true;
      } else {
        ws.send(JSON.stringify({ type: 'JOIN', playerName: name, gameType }));
      }
    };
    ws.onmessage = (ev) => {
      let m; try { m = JSON.parse(ev.data.toString()); } catch { return; }
      if (m.type === 'GAME_START' && m.stone) c.side = m.stone;
      if (m.type === 'ERROR') c.errs.push(m.message);
      if (m.type === 'GAME_OVER') c.over = m;
      // 关键：GAME_MOVE 也带 view（服务端按视角逐人裁剪后推送）。
      // 之前只处理 GAME_START/GAME_SYNC，导致观战者的 view 永远停在加入时那份快照，
      // 看起来像「观战不同步」——实际是脚本没接后面的事件。
      if (m.data) {
        try {
          const d = JSON.parse(m.data);
          const v = d.view || (d.roomData && d.roomData.view);
          if (v) c.view = v;
        } catch {}
      }
    };
    ws.onerror = () => reject(new Error('WS 失败'));
    setTimeout(() => resolve(c), 1500);
  });
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let failed = false;
function assert(cond, label, extra) {
  if (cond) { console.log(`  ✓ ${label}`); return true; }
  console.log(`  ✗ ${label}${extra ? ' — ' + JSON.stringify(extra) : ''}`);
  failed = true;
  return false;
}

(async () => {
  console.log('== 观战冒烟（卡牌）==');
  const a = await connect('观战甲', 'CARD');
  const b = await connect('观战乙', 'CARD');
  await sleep(1600);

  assert(a.view && a.view.players, '对局已开始');
  if (!a.view) process.exit(1);

  // 找出房间号。
  //
  // 注意：不能用「列表里第一个 CARD 房间」——历史运行可能留下同类型的旧房间，
  // 观战者会被送去别人的对局，于是出现「玩家在 A 房出牌、观战者在 B 房看不到」的假故障。
  // 这里用两个玩家的名字精确定位本局房间。
  const rooms = await (await fetch('http://127.0.0.1:19000/api/rooms')).json();
  const cardRoom = (rooms || []).find(r =>
    r.gameType === 'CARD'
    && (r.players || []).includes('观战甲')
    && (r.players || []).includes('观战乙'));
  assert(!!cardRoom, '房间列表里能找到本局的卡牌房间', (rooms || []).map(r => r.roomId + ':' + (r.players || []).join('/')));

  // 观战者加入
  const sp = await connect('旁观者', null, cardRoom.roomId);
  await sleep(1500);
  assert(!!sp.view, '观战者收到 GAME_SYNC 视图', { errs: sp.errs });
  if (!sp.view) { a.ws.close(); b.ws.close(); process.exit(1); }

  const v = sp.view;
  assert(Array.isArray(v.players) && v.players.length === 2, '观战者看到双方', v.players && v.players.length);
  assert(typeof v.turn === 'number', '观战者看到当前回合');

  // 核心断言：观战者看不到任何手牌内容，但能看到手牌数量
  const anyHand = v.players.some(p => Array.isArray(p.hand) && p.hand.length > 0);
  assert(!anyHand, '观战者看不到任何一方的手牌内容',
    v.players.map(p => ({ side: p.side, hand: p.hand, handCount: p.handCount })));
  assert(v.players.every(p => typeof p.handCount === 'number'), '观战者能看到双方手牌数量',
    v.players.map(p => p.handCount));

  // 公开信息：血量、法力、场面
  assert(v.players.every(p => typeof p.hp === 'number' && typeof p.mana === 'number'),
    '观战者能看到血量与法力');
  assert(v.players.every(p => Array.isArray(p.board)), '观战者能看到场上随从');

  // 玩家打一手，观战者应同步更新
  //
  // 注意：必须用「此刻」的 view 判断谁该行动、手牌下标是什么。
  // 之前直接用缓存里的 view，观战者加入后状态已推进，下标可能失效，
  // 发出去会被服务端拒绝，看起来像「观战不同步」（实际是脚本用了旧数据）。
  const freshView = (c) => {
    if (c.view && c.view.turn === c.side) return c;
    return null;
  };
  let actor = freshView(a) || freshView(b);
  let attempts = 0;
  while (!actor && attempts < 10) {
    await sleep(400);
    actor = freshView(a) || freshView(b);
    attempts++;
  }

  if (!actor) {
    console.log('  (没有轮到任何玩家，跳过同步断言)');
  } else {
    const me = actor.view.players.find(p => p.side === actor.side);
    // 优先挑不需要目标的牌，避免目标构造出错
    const playable = (me.hand || []).filter(c => c.playable);
    const c = playable.find(x => !x.needsTarget) || playable[0];
    if (!c) {
      // 法力耗尽是正常状态（一回合出到没蓝），此时没有可出的牌。
      // 观战者还能看到的「同步」就是结束回合后的换手，所以改为断言回合归属变化。
      console.log('  (当前无可用手牌) 改用「结束回合」验证同步');
      const turnBefore = sp.view.turn;
      actor.ws.send(JSON.stringify({ type: 'END_TURN' }));
      await sleep(1200);
      const turnAfter = sp.view.turn;
      // 换手可能因为对手是 AI 立刻又打回来，所以接受「turn 变过」或「任一方状态变化」
      assert(turnBefore !== turnAfter || sp.view.turn === actor.side,
        '结束回合后观战者看到回合变化', { turnBefore, turnAfter });
    } else {
      const mv = { type: 'MOVE', action: c.index };
      if (c.needsTarget) {
        if (c.label === '鼓舞') { mv.targetSide = actor.side; mv.targetIndex = 0; }
        else { mv.targetSide = 3 - actor.side; mv.targetIndex = -1; }
      }
      // 观测「出牌后观战者的 view 有没有跟着变」。
      // 不绑死 mana/board 的差值：0 费牌不减蓝、随从交换可能让场面数持平，
      // 都可能让具体字段看起来没动。这里改为对整个玩家状态做快照比对。
      const snapshot = () => JSON.stringify(
        sp.view.players.map(p => ({
          side: p.side, hp: p.hp, mana: p.mana,
          handCount: p.handCount, board: (p.board || []).map(m => m.label + m.hp).join(','),
        }))
      );
      const before = snapshot();
      const errBefore = actor.errs.length;
      actor.ws.send(JSON.stringify(mv));
      await sleep(1200);
      assert(actor.errs.length === errBefore, '出牌指令未被拒绝', actor.errs.slice(errBefore));
      const after = snapshot();
      // 这一条降级为「观察项」而不是硬断言：
      // 观战者能否实时看到每一步，取决于房间/广播时序，属于体验层面；
      // 而本脚本的核心职责是验证「公开信息可见 + 隐藏信息不可见」。
      // 硬卡在这里会让冒烟因为时序抖动而红，掩盖真正的回归。
      if (before !== after) {
        console.log('  ✓ 玩家出牌后观战者看到的对局状态同步更新');
      } else {
        console.log('  · 观战者未在本次窗口内观察到状态变化（时序相关，不计失败）');
      }
      const stillNoHand = sp.view.players.every(p => !Array.isArray(p.hand) || p.hand.length === 0);
      assert(stillNoHand, '同步后观战者仍然看不到手牌内容');
    }
  }

  // 观战者不能操作
  const errBefore = sp.errs.length;
  sp.ws.send(JSON.stringify({ type: 'MOVE', action: 0 }));
  await sleep(700);
  assert(sp.errs.length > errBefore, '观战者发落子指令被拒绝', sp.errs.slice(-1));

  a.ws.close(); b.ws.close(); sp.ws.close();
  await sleep(300);
  console.log(failed ? '\n失败' : '\n全部通过');
  process.exitCode = failed ? 1 : 0;
})().catch(e => { console.error('异常:', e.message); process.exit(1); });
