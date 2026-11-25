import assert from 'node:assert/strict';

const WS = process.env.WS_URL || 'ws://127.0.0.1:19001';
const REST = process.env.REST_URL || 'http://127.0.0.1:19000';
const clients = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let passed = 0;
function check(label, condition) {
  assert.ok(condition, label);
  console.log('[dots-boxes] PASS ' + label);
  passed++;
}
async function until(test, label, timeout = 12000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await test()) return;
    await sleep(30);
  }
  throw new Error('等待超时: ' + label);
}
async function connect() {
  const socket = new WebSocket(WS);
  const client = { socket, room: null, session: null, id: null, side: 0, seq: 0,
    view: null, board: Array.from({ length: 9 }, () => Array(9).fill(0)), errors: [], result: null };
  clients.push(client);
  socket.onmessage = event => {
    const message = JSON.parse(String(event.data));
    if (message.roomId) client.room = message.roomId;
    if (message.sessionId) client.session = message.sessionId;
    if (message.playerId && ['WAITING', 'GAME_START', 'GAME_SYNC'].includes(message.type)) client.id = message.playerId;
    if (['GAME_START', 'GAME_SYNC'].includes(message.type)) client.side = message.stone || 0;
    if (message.type === 'GAME_START') {
      client.seq = 0;
      client.result = null;
      client.board = Array.from({ length: 9 }, () => Array(9).fill(0));
    }
    let data = {};
    try { data = JSON.parse(message.data || '{}'); } catch {}
    if (data.view) client.view = data.view;
    if (message.type === 'GAME_SYNC') {
      client.board = data.board;
      client.seq = data.moveSeq;
      client.result = data.gameOver ? { ...JSON.parse(data.gameOver.data), winner: data.gameOver.winner } : null;
    }
    if (message.type === 'GAME_MOVE') {
      client.seq = message.moveSeq;
      for (const effect of data.effects || []) client.board[effect[0]][effect[1]] = effect[2];
    }
    if (message.type === 'GAME_OVER') client.result = { ...data, winner: message.winner, message: message.message };
    if (message.type === 'ERROR') client.errors.push(message.message);
  };
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  client.send = message => socket.send(JSON.stringify(message));
  client.move = async (row, col) => {
    await sleep(350);
    client.send({ type: 'MOVE', row, col, moveSeq: client.seq + 1 });
  };
  return client;
}
async function guestJoin(client, name, room, count=4) {
  const response = await fetch(REST + '/api/guest', { method: 'POST' });
  assert.ok(response.ok);
  const identity = await response.json();
  client.send({ type: 'JOIN', gameType: 'DOTS_BOXES', playerName: name,
    guestToken: identity.token, playerCount: count, ...(room ? { roomId: room } : { createRoom: true }) });
}

try {
  let players=[];
  for(let side=1;side<=4;side++) {
    const client=await connect();players.push(client);await guestJoin(client,'点格'+side,players[0].room);
    await until(()=>!!client.room,'第'+side+'席入座');
  }
  await until(()=>players.every(p=>p.view?.kind==='DOTS_BOXES'),'四人开局');
  check('四席使用点格棋且公开40条合法边',players.every(p=>p.view.playerCount===4 && p.view.legalMoves.length===40));
  const spectator=await connect();spectator.send({type:'SPECTATE',roomId:players[0].room,playerName:'点格观众'});
  await until(()=>!!spectator.view,'观战');
  check('观众同步完整公开盘面',spectator.side===0 && spectator.view.legalMoves.length===40);
  await players[0].move(0,0);await until(()=>players[0].errors.length===1,'点坐标拒绝');players[0].errors.pop();
  check('点坐标不能画边也不改变手数',players[0].seq===0 && players[0].view.turn===1);
  const order=[[0,1],[1,0],[2,1],[0,3],[1,4],[2,3],[1,2]];
  for(let r=0;r<9;r++)for(let c=0;c<9;c++)if((r+c)%2===1 && !order.some(e=>e[0]===r&&e[1]===c))order.push([r,c]);
  const expected=Array.from({length:9},()=>Array(9).fill(0)),scores=[0,0,0,0];let extra=0,double=0;
  for(let index=0;index<order.length;index++) {
    const side=players[0].view.turn,actor=players.find(p=>p.side===side),[row,col]=order[index];
    if(index===7) {
      const indexOf=players.indexOf(actor),session=actor.session,id=actor.id;
      actor.socket.close();await until(()=>actor.socket.readyState===WebSocket.CLOSED,'真实断线');
      const restored=await connect();restored.send({type:'RECONNECT',sessionId:session});
      await until(()=>restored.seq===index && restored.view,'恢复连续回合');
      assert.equal(restored.id,id);assert.equal(restored.side,side);assert.deepEqual(restored.board,expected);
      players[indexOf]=restored;check('重连恢复围格得分和同一行动者',restored.view.continued && restored.view.lastCaptured===2);
    }
    const current=players.find(p=>p.side===side);await current.move(row,col);
    await until(()=>players.every(p=>p.seq===index+1)&&spectator.seq===index+1,'逐手广播');
    expected[row][col]=side;let gained=0;
    for(let r=1;r<9;r+=2)for(let c=1;c<9;c+=2)if(expected[r][c]===0&&expected[r-1][c]&&expected[r+1][c]&&expected[r][c-1]&&expected[r][c+1]){expected[r][c]=side;gained++;}
    scores[side-1]+=gained;if(gained)extra++;if(gained===2)double++;
    for(const p of [...players,spectator]) {assert.deepEqual(p.board,expected);assert.deepEqual(p.view.players.map(p=>p.score),scores);}
    if(index<39)assert.equal(players[0].view.turn,gained?side:side%4+1);
    if(index===0) {
      const next=players.find(p=>p.side===2),before=next.errors.length;await sleep(350);
      next.send({type:'MOVE',row:1,col:0,moveSeq:1});await until(()=>next.errors.length===before+1,'旧序号拒绝');next.errors.pop();
      check('重传旧序号不多画边',players.every(p=>p.seq===1)&&spectator.seq===1);
    }
  }
  await until(()=>players.every(p=>p.result)&&spectator.result,'自然终局');
  check('四人真实对局画完40边并占满16格',scores.reduce((a,b)=>a+b,0)===16 && players[0].view.remainingBoxes===0);
  check('真实经历一边两格和连续围格',double>0&&extra>1);
  check('所有玩家和观众得到同一终局',players.every(p=>p.result.winner===spectator.result.winner));
  check('结算包含围格说明且多人不计双人ELO',players[0].result.message.includes('围格')&&players[0].result.scores.every(p=>p.eloChange===0));
  const result=players[0].result;check('逐手效果与五列棋谱完整对齐',result.moveHistory.length===40&&result.moveEffects.length===40&&result.moveHistory.every(m=>m.length===5));
  const rebuilt=Array.from({length:9},()=>Array(9).fill(0));result.moveEffects.forEach(es=>es.forEach(e=>rebuilt[e[0]][e[1]]=e[2]));
  check('持久化前的逐手效果能完整还原终盘',JSON.stringify(rebuilt)===JSON.stringify(expected));
  const records=await(await fetch(REST+'/api/games?gameType=DOTS_BOXES&limit=10')).json();const record=records.find(r=>r.roomId===players[0].room);check('新玩法进入对局列表',!!record);
  const detail=await(await fetch(REST+'/api/games/'+record.id)).json();
  check('持久记录保留四席与围格效果',detail.gameType==='DOTS_BOXES'&&detail.participants.length===4&&JSON.stringify(detail.moveEffects)===JSON.stringify(result.moveEffects));
  const maxScore=Math.max(...scores),leaders=scores.map((score,index)=>score===maxScore?index+1:0).filter(Boolean);
  check('结算胜负与独立核算的最高占格数一致',detail.participants.every(p=>p.result===(leaders.includes(p.side)?'WIN':'LOSE')));
  players.forEach(p=>p.send({type:'RESTART'}));await until(()=>players.every(p=>p.seq===0&&p.view.legalMoves.length===40),'全员再开');
  check('再开清空所有边方格和积分',players.every(p=>p.board.flat().every(x=>x===0)&&p.view.players.every(p=>p.score===0)));
  const departing=players.find(p=>p.side===players[0].view.turn),departingSide=departing.side;departing.send({type:'LEAVE'});
  const remaining=players.filter(p=>p!==departing);await until(()=>remaining[0].view.players.find(p=>p.side===departingSide)?.active===false,'退出继续');
  const next=remaining.find(p=>p.side===remaining[0].view.turn),edge=next.view.legalMoves[0];await next.move(edge[0],edge[1]);await until(()=>remaining.every(p=>p.seq===1),'退出后继续画边');
  check('当前座位离场只跳过本人其余三席继续',remaining.every(p=>!p.result&&p.view.turn!==departingSide));
  const human=await connect();await guestJoin(human,'点格人机',null,4);await until(()=>!!human.room,'人机建房');
  for(let i=0;i<3;i++){human.send({type:'ADD_AI',data:'6'});await sleep(400);}
  await until(()=>human.view?.playerCount===4,'四席人机开局');
  const deadline=Date.now()+90000;
  while(!human.result&&Date.now()<deadline){if(human.view.turn===human.side){const before=human.seq,m=human.view.legalMoves[0];await human.move(m[0],m[1]);await until(()=>human.seq>before,'真人人机走边');}else await sleep(100);}
  check('真人加三台困难电脑自然完成40边',human.result?.moveHistory.length===40&&human.view.over&&human.view.players.reduce((n,p)=>n+p.score,0)===16);
  check('全程除预期非法请求外无协议错误',clients.every(c=>c.errors.length===0));
  console.log('[dots-boxes] PASS '+passed+'/'+passed);
} catch(error) {console.error('[dots-boxes] FAIL',error.stack);process.exitCode=1;}
finally {for(const c of clients){if(c.socket.readyState===WebSocket.OPEN)c.send({type:'LEAVE'});c.socket.close();}}
