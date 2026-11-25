import assert from 'node:assert/strict';

const WS = process.env.WS_URL || 'ws://127.0.0.1:19001';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const clients = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let passed = 0;
function check(label, condition) { assert.ok(condition, label); passed++; console.log('[dice-press] PASS ' + label); }
async function until(test, label, timeout = 12000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { if (await test()) return; await sleep(40); }
  throw new Error('等待超时: ' + label);
}
async function connect() {
  const socket = new WebSocket(WS);
  const client = { socket, seq:0, syncs:0, errors:[], result:null, version:null, side:0 };
  clients.push(client);
  socket.onmessage = event => {
    const msg = JSON.parse(String(event.data));
    let data = {}; try { data = JSON.parse(msg.data || '{}'); } catch {}
    if (msg.roomId) client.room = msg.roomId;
    if (msg.sessionId) client.session = msg.sessionId;
    if (['WAITING','GAME_START','GAME_SYNC'].includes(msg.type) && msg.playerId) client.id = msg.playerId;
    if (['GAME_START','GAME_SYNC'].includes(msg.type)) client.side = msg.stone || 0;
    if (data.view) client.view = data.view;
    if (data.actionVersion) client.version = data.actionVersion;
    if (msg.type === 'GAME_START') { client.seq = 0; client.result = null; }
    if (msg.type === 'GAME_MOVE') { client.seq = msg.moveSeq; client.effects = data.effects; }
    if (msg.type === 'GAME_SYNC') { client.syncs++; client.seq = data.moveSeq; client.version = data.roomData?.actionVersion || client.version; }
    if (data.timers) client.timers = data.timers;
    if (msg.type === 'GAME_OVER') client.result = {...data, winner:msg.winner};
    if (msg.type === 'ERROR') client.errors.push(msg.message);
  };
  await new Promise((resolve,reject) => { socket.onopen=resolve; socket.onerror=reject; });
  client.send = msg => socket.send(JSON.stringify(msg));
  client.action = async (action, version = client.version) => {
    await sleep(350); client.send({type:'MOVE',action,actionVersion:version});
  };
  return client;
}
const player = (client, side) => client.view.players.find(p=>p.side===side);
try {
  const seats = [];
  for (let index=0;index<4;index++) {
    const client=await connect();
    const guest=await (await fetch(REST+'/api/guest',{method:'POST'})).json();
    client.send({type:'JOIN',gameType:'DICE_RACE',playerCount:4,playerName:'冲刺'+(index+1),guestToken:guest.token,
      ...(index ? {roomId:seats[0].room} : {createRoom:true})});
    await until(()=>client.room && client.session,'入座'); seats.push(client);
  }
  await until(()=>seats.every(client=>client.view?.phase==='CHOOSE' && client.version),'四人开局');
  check('四人竞速以新规则开局', seats.every(client=>client.view.players.length===4));
  check('所有座位均有三种初始策略与一枚护盾', seats.every(client=>client.view.legalActions.join(',')==='0,1,2'
    && player(client,client.side).shieldAvailable));
  const watcher=await connect();
  watcher.send({type:'SPECTATE',roomId:seats[0].room,playerName:'竞速观众'});
  await until(()=>watcher.view?.phase==='CHOOSE','观众入场');
  check('观众不能获得可操作按钮',watcher.view.legalActions.length===0);
  let pressRounds=0, checkedHidden=false, checkedReconnect=false, usedShield=false;
  let checkedVersion=false, checkedDuplicate=false;
  const all = () => [...seats,watcher];
  async function sync(seq) { await until(()=>all().every(client=>client.seq===seq),'所有视角权威手数'); }
  for(let round=1;round<=6;round++) {
    const chooseVersion=seats[0].version;
    for(const [index,client] of seats.entries()) {
      const seq=client.seq+1; await client.action(1); await sync(seq);
      if(index===0) {
        assert.equal(player(client,client.side).choice,1);
        assert.ok(!('choice' in player(watcher,client.side)));
        assert.ok(!('dice' in player(watcher,client.side)));
        assert.equal(client.version,chooseVersion);
      }
    }
    if(seats[0].view.phase==='PRESS') {
      pressRounds++;
      const pending=seats.filter(client=>client.view.legalActions.length);
      const before=seats[0].view.players.map(p=>({...p,dice:[...p.dice]}));
      check('第 '+round+' 轮只由冒险成功者继续决策', seats.every(client=>
        !!client.view.legalActions.length === (player(client,client.side).pendingScore>0)));
      if(!checkedVersion) {
        const actor=pending[0], seq=actor.seq, syncs=actor.syncs;
        await actor.action(3,chooseVersion);
        await until(()=>actor.syncs>syncs,'旧阶段操作被拒并同步最新局面');
        check('旧策略窗口不能提交到加掷窗口',actor.seq===seq && actor.version!==chooseVersion);
        checkedVersion=true;
      }
      if(!checkedReconnect) {
        const old=pending[0], session=old.session, index=seats.indexOf(old);
        old.socket.close(); await until(()=>old.socket.readyState===WebSocket.CLOSED,'断开');
        const fresh=await connect(); fresh.send({type:'RECONNECT',sessionId:session});
        await until(()=>fresh.view?.phase==='PRESS' && fresh.side===old.side,'加掷阶段重连');
        check('重连恢复首骰待收分护盾与合法行动',fresh.seq===old.seq && fresh.id===old.id
          && JSON.stringify(fresh.view)===JSON.stringify(old.view));
        seats[index]=fresh; pending[pending.indexOf(old)]=fresh; checkedReconnect=true;
      }
      const submitted = new Map();
      const pressVersion=pending[0].version;
      for(const [index,client] of pending.entries()) {
        const action=client.view.legalActions.includes(5) && !usedShield ? 5 : index%2 ? 3 : 4;
        const seq=client.seq+1; await client.action(action); submitted.set(client.side,action);
        if(action===5) usedShield=true;
        await sync(seq);
        if(index===0 && pending.length>1) {
          check('加掷决定只对本人可见',player(client,client.side).decision===action
            && !('decision' in player(watcher,client.side)) && watcher.effects.length===0);
          assert.equal(player(watcher,client.side).shieldAvailable,before[client.side-1].shieldAvailable);
          const syncs=client.syncs; await client.action(3,pressVersion);
          await until(()=>client.syncs>syncs,'重复决定被拒并同步最新局面');
          assert.equal(client.seq,seq);
          checkedHidden=true; checkedDuplicate=true;
        }
      }
      for(const old of before) {
        const now=player(watcher,old.side), action=submitted.get(old.side);
        let points=old.pendingScore;
        if(action===4 || action===5) points=now.lastPush===1 ? action===5 ? points : 0 : points+now.lastPush;
        assert.equal(now.score,old.score+points); assert.equal(now.lastRoundScore,points);
        assert.equal(now.shieldAvailable,action===5 ? false : old.shieldAvailable);
      }
    }
    if(round<6) assert.ok(seats.every(client=>client.view.phase==='CHOOSE' && client.view.round===round+1));
  }
  await until(()=>all().every(client=>client.result),'六轮自然终局');
  check('六轮中真实进入加掷阶段且验证保密与重复拦截',pressRounds>0 && checkedHidden && checkedDuplicate);
  check('一次护盾已被服务端实际消耗',usedShield && watcher.view.players.some(p=>!p.shieldAvailable));
  check('四席与观众比分及终局一致',seats.every(client=>JSON.stringify(client.view.players)===JSON.stringify(watcher.view.players)
    && client.result.winner===watcher.result.winner));
  check('棋谱和效果逐手完整且包含二次决策',watcher.result.moveHistory.length===watcher.seq
    && watcher.result.moveEffects.length===watcher.seq && watcher.result.moveHistory.some(row=>row[1]>=3 && row.length===8));
  check('四人局不产生双人ELO',watcher.result.scores.length===4 && watcher.result.scores.every(p=>p.eloChange===0));
  const games=await(await fetch(REST+'/api/games?gameType=DICE_RACE&limit=10')).json();
  const record=games.find(g=>g.roomId===watcher.room); assert.ok(record);
  const detail=await(await fetch(REST+'/api/games/'+record.id)).json();
  check('持久化保留首骰加掷与最终总分',detail.moveHistory.length===watcher.seq
    && detail.moveHistory.every(row=>row.length===8) && watcher.view.players.every(p=>
      detail.moveHistory.filter(row=>row[0]===p.side).reduce((sum,row)=>sum+row[2],0)===p.score));
  for(const client of seats) client.send({type:'RESTART'});
  await until(()=>seats.every(client=>client.seq===0 && client.view.phase==='CHOOSE'),'全员再开');
  check('再来一局清空比分并恢复一次护盾',seats.every(client=>client.view.players.every(p=>p.score===0 && p.shieldAvailable)));
  check('全程无非预期协议错误',all().every(client=>!client.errors.length));
  console.log('[dice-press] PASS '+passed+'/'+passed);
} catch(error) { console.error('[dice-press] FAIL',error.stack); process.exitCode=1; }
finally { for(const client of clients) { if(client.socket.readyState===WebSocket.OPEN) client.send({type:'LEAVE'}); client.socket.close(); } }
