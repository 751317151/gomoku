import assert from 'node:assert/strict';
const WS=process.env.WS_URL||'ws://127.0.0.1:19001';
const REST=process.env.REST_URL||'http://127.0.0.1:19000';
const clients=[], sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let passed=0;
function check(label,condition){assert.ok(condition,label);passed++;console.log('[gem-collection] PASS '+label);}
async function until(test,label,timeout=12000){const end=Date.now()+timeout;while(Date.now()<end){if(await test())return;await sleep(40);}throw Error('等待超时: '+label);}
async function connect(){
  const socket=new WebSocket(WS),client={socket,seq:0,syncs:0,errors:[],version:null,side:0,result:null};clients.push(client);
  socket.onmessage=event=>{
    const msg=JSON.parse(String(event.data));let data={};try{data=JSON.parse(msg.data||'{}');}catch{}
    if(msg.roomId)client.room=msg.roomId;if(msg.sessionId)client.session=msg.sessionId;
    if(['WAITING','GAME_START','GAME_SYNC'].includes(msg.type)&&msg.playerId)client.id=msg.playerId;
    if(['GAME_START','GAME_SYNC'].includes(msg.type))client.side=msg.stone||0;
    if(data.view)client.view=data.view;if(data.actionVersion)client.version=data.actionVersion;
    if(msg.type==='GAME_START'){client.seq=0;client.result=null;}
    if(msg.type==='GAME_MOVE'){client.seq=msg.moveSeq;client.effects=data.effects;client.timers=data.timers;}
    if(msg.type==='GAME_SYNC'){client.syncs++;client.seq=data.moveSeq;client.version=data.roomData?.actionVersion||client.version;}
    if(msg.type==='GAME_OVER')client.result={...data,winner:msg.winner};
    if(msg.type==='ERROR')client.errors.push(msg.message);
  };
  await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject;});
  client.send=msg=>socket.send(JSON.stringify(msg));
  client.action=async(action,version=client.version)=>{await sleep(350);client.send({type:'MOVE',action,actionVersion:version});};
  return client;
}
const player=(client,side)=>client.view.players.find(p=>p.side===side);
const bonus=collection=>collection.reduce((sum,n)=>sum+Math.floor(n/2)*3,0)+Math.min(...collection)*8;
try {
  const seats=[];
  for(let i=0;i<4;i++){
    const client=await connect(),guest=await(await fetch(REST+'/api/guest',{method:'POST'})).json();
    client.send({type:'JOIN',gameType:'GEM_AUCTION',playerCount:4,playerName:'收藏家'+(i+1),guestToken:guest.token,
      ...(i?{roomId:seats[0].room}:{createRoom:true})});
    await until(()=>client.room&&client.session,'入座');seats.push(client);
  }
  await until(()=>seats.every(c=>c.view?.lots?.length===5&&c.version),'四人开局');
  const watcher=await connect();watcher.send({type:'SPECTATE',roomId:seats[0].room});
  await until(()=>watcher.view?.lots?.length===5,'观战');
  const all=()=>[...seats,watcher],catalog=watcher.view.lots;
  check('四席和观众看到相同公开图录且三色齐全',all().every(c=>JSON.stringify(c.view.lots)===JSON.stringify(catalog))&&new Set(catalog.map(l=>l.color)).size===3);
  check('观众没有合法动作',watcher.view.legalActions.length===0);
  const plan=[[5,5,5,5,5],[5,0,1,0,1],[1,2,0,0,0],[0,0,0,0,0]];
  let expected=seats.map(()=>({coins:30,score:0,base:0,collection:[0,0,0]})),seq=0,oldVersion;
  const sync=async()=>until(()=>all().every(c=>c.seq===seq),'权威手数');
  for(let round=1;round<=5;round++){
    const version=seats[0].version;
    if(oldVersion){const c=seats[0],count=c.syncs;await c.action(5,oldVersion);await until(()=>c.syncs>count,'旧轮次拒绝');check('第 '+round+' 轮拒绝过期操作且手数不变',c.seq===seq);}
    for(let i=0;i<4;i++){
      let client=seats[i];await client.action(plan[i][round-1]);seq++;await sync();
      if(i===0){
        check('第 '+round+' 轮暗投保密且余额和逐手效果不泄漏',player(client,client.side).bid===5
          &&!('bid'in player(watcher,client.side))&&watcher.effects.length===0
          &&player(watcher,client.side).coins===expected[0].coins&&!client.timers.awaitingSides.includes(client.side));
        if(round===2){
          const count=client.syncs;await client.action(0,version);await until(()=>client.syncs>count,'重复出价拒绝');
          check('重复出价不会改写已提交决定',client.seq===seq&&player(client,client.side).bid===5);
          const before=JSON.stringify(client.view),session=client.session;client.socket.close();
          await until(()=>client.socket.readyState===WebSocket.CLOSED,'断线');
          const fresh=await connect();fresh.send({type:'RECONNECT',sessionId:session});
          await until(()=>fresh.view&&fresh.side===client.side,'重连');
          check('重连恢复图录收藏与本人暗投且不能重复行动',JSON.stringify(fresh.view)===before&&fresh.seq===seq&&fresh.view.legalActions.length===0);
          seats[i]=fresh;client=fresh;
        }
      }
    }
    const bids=plan.map(p=>p[round-1]),best=Math.max(...bids),lot=catalog[round-1];
    for(let i=0;i<4;i++){
      const e=expected[i],bid=bids[i],oldBonus=bonus(e.collection);
      const recovered=bid===0?Math.min(2,30-e.coins):0;e.coins+=recovered-bid;
      if(bid>0&&bid===best){e.base+=lot.value;e.collection[lot.color]++;}
      e.score=e.base+bonus(e.collection);
      const p=player(watcher,seats[i].side);
      assert.equal(p.coins,e.coins);assert.equal(p.gems,e.score);assert.equal(p.baseScore,e.base);
      assert.equal(p.bonusScore,bonus(e.collection));assert.deepEqual(p.collection,e.collection);
      assert.equal(p.lastRecovered,recovered);assert.equal(p.lastBonus,bonus(e.collection)-oldBonus);
    }
    check('第 '+round+' 轮中标流拍回收与收藏奖励按规则结算',all().every(c=>c.view.lastRound===round));
    oldVersion=version;
  }
  await until(()=>all().every(c=>c.result),'五轮自然终局');
  check('全图录收藏触发三色及同色奖励',player(watcher,seats[0].side).bonusScore>=11);
  check('四席和观众终局公开状态及胜者一致',seats.every(c=>JSON.stringify(c.view.players)===JSON.stringify(watcher.view.players)&&c.result.winner===watcher.result.winner));
  check('多人结算不改变双人ELO且每步有记录',watcher.result.scores.every(p=>p.eloChange===0)&&watcher.result.moveHistory.length===20&&watcher.result.moveEffects.length===20);
  const records=await(await fetch(REST+'/api/games?gameType=GEM_AUCTION&limit=10')).json();
  const record=records.find(r=>r.roomId===watcher.room);assert.ok(record);
  const detail=await(await fetch(REST+'/api/games/'+record.id)).json();
  check('持久化十列记录可核算全部得分和回收',detail.moveHistory.length===20&&detail.moveHistory.every(r=>r.length===10)
    &&seats.every((c,i)=>detail.moveHistory.filter(r=>r[0]===c.side).reduce((sum,r)=>sum+r[2],0)===expected[i].score)
    &&detail.moveHistory.some(r=>r[8]>=3)&&detail.moveHistory.some(r=>r[9]>0));
  for(const c of seats)c.send({type:'RESTART'});
  await until(()=>seats.every(c=>c.seq===0&&!c.view.over&&c.view.round===1),'再开');
  check('再开恢复金币并清空收藏和组合得分',seats.every(c=>c.view.players.every(p=>p.coins===30&&p.gems===0&&p.collection.every(n=>n===0))));
  seq=0;
  for(let i=1;i<4;i++){await seats[i].action(i===1?2:i===2?1:0);seq++;await sync();}
  seats[0].send({type:'LEAVE'});
  await until(()=>watcher.view.round===2,'待出价者离场后继续');
  check('一人离场释放暗投等待且其余三人继续',watcher.view.players.filter(p=>p.active).length===3&&!watcher.view.over&&player(watcher,seats[1].side).gems===3);
  const solo=await connect();solo.send({type:'JOIN',gameType:'GEM_AUCTION',playerCount:8,playerName:'八人收藏练习',createRoom:true});
  await until(()=>solo.room&&solo.session,'八人练习入座');
  for(let i=0;i<7;i++)solo.send({type:'ADD_AI',data:'6'});
  await until(()=>solo.side>0&&solo.view?.players?.length===8,'七台电脑补齐');
  check('八人练习真实房间可补满困难电脑',solo.view.lots.length===5);
  for(let round=1;round<=5;round++){
    await until(()=>solo.view.round===round&&solo.view.legalActions.includes(1),'真人竞拍窗口');
    await solo.action(1);
    await until(()=>solo.view.over||solo.view.round>round,'七台电脑独立完成本轮');
  }
  await until(()=>solo.result,'八人自然终局');
  check('七台困难电脑与真人完成五轮且预算合法',solo.seq===40&&solo.view.players.every(p=>p.coins>=0&&p.coins<=30)&&solo.result.moveHistory.length===40);
  check('全程无非预期协议错误',clients.every(c=>!c.errors.length));
  console.log('[gem-collection] PASS '+passed+'/'+passed);
}catch(error){console.error('[gem-collection] FAIL',error.stack);process.exitCode=1;}
finally{for(const c of clients){if(c.socket.readyState===WebSocket.OPEN)c.send({type:'LEAVE'});c.socket.close();}}
