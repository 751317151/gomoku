import assert from 'node:assert/strict';
const HTTP=process.env.HTTP_URL||'http://127.0.0.1:19000';
const WS=process.env.WS_URL||'ws://127.0.0.1:19001';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function until(fn, label, timeout=12000) { const end=Date.now()+timeout; while(Date.now()<end) { if(fn())return; await sleep(30); } throw Error('Timeout: '+label); }
async function connect() {
  const ws=new WebSocket(WS);const c={ws,messages:[],view:null,session:null,side:0};
  ws.onmessage=e=>{const m=JSON.parse(String(e.data));c.messages.push(m);if(m.sessionId)c.session=m.sessionId;if(m.type==='GAME_START'||m.type==='GAME_SYNC'){c.side=m.stone||c.side;c.room=m.roomId;}
    if(m.data){try{const d=JSON.parse(m.data);c.view=d.view||d.roomData?.view||c.view;if(m.type==='WAITING')c.room=m.roomId;}catch{}}
    if(m.type==='WAITING')c.room=m.roomId;
  };await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});c.send=m=>ws.send(JSON.stringify(m));return c;
}
const clients=[];
try {
 assert.equal((await (await fetch(HTTP+'/actuator/health')).json()).status,'UP');
 const identity=await(await fetch(HTTP+'/api/guest',{method:'POST'})).json();
 assert(identity.token&&identity.id); const resumed=await(await fetch(HTTP+'/api/guest',{method:'POST',headers:{Authorization:'Bearer '+identity.token}})).json();assert.equal(resumed.id,identity.id);assert(!resumed.token);
 for(let i=0;i<8;i++){
  const c=await connect();clients.push(c);
  c.send({type:'JOIN',gameType:'HIGH_CARD',playerName:'seat-'+i,createRoom:i===0,playerCount:8,roomId:i?clients[0].room:undefined,guestToken:i===0?identity.token:undefined});
  await until(()=>c.room,'join '+i);
 }
 await until(()=>clients.every(c=>c.side>0),'eight player start');
 assert.equal(new Set(clients.map(c=>c.side)).size,8);
 console.log('PASS 8 seats start over real WebSockets');
 const spectator=await connect();clients.push(spectator);spectator.send({type:'SPECTATE',roomId:clients[0].room});await until(()=>spectator.view,'spectator');
 clients[7].send({type:'MOVE',action:10});await until(()=>clients[0].view.players.find(p=>p.side===8)?.answered,'hidden pick');
 assert(clients[0].view.players.every(p=>p.side===1||!('hand'in p)&&!('choice'in p)));assert(spectator.view.players.every(p=>!('hand'in p)&&!('choice'in p)));
 console.log('PASS opponent/spectator hidden information');
 const old=clients[7], token=old.session;assert(token);old.ws.close();await sleep(100);const replacement=await connect();clients[7]=replacement;replacement.send({type:'RECONNECT',sessionId:token});await until(()=>replacement.side===8,'seat 8 reconnect');assert.equal(replacement.view.players.find(p=>p.side===8).choice,10);
 console.log('PASS seat 8 reconnect');
 for(let round=1;round<=5;round++){
  for(let i=7;i>=0;i--){if(round===1&&i===7)continue;clients[i].send({type:'MOVE',action:round===1?10:11-round});}
  if(round<5) { await until(()=>clients[0].view.round===round+1,'round advance '+round).catch(e=>{ console.error(JSON.stringify(clients[0].view));console.error(clients.slice(0,8).map(c=>c.messages.filter(m=>m.type==='ERROR')));throw e;}); console.log('PASS round',round); }
 }
 await until(()=>clients.slice(0,8).every(c=>c.messages.some(m=>m.type==='GAME_OVER')),'game over');
 const over=clients[0].messages.find(m=>m.type==='GAME_OVER');assert.equal(JSON.parse(over.data).winningSides.length,8);
 assert.equal((await(await fetch(HTTP+'/api/games?gameType=HIGH_CARD')).json())[0].participants.length,8);
 console.log('PASS complete 8 player game, tied results and persistence');
 assert.equal((await fetch(HTTP+'/api/me/stats')).status,401);
 assert.equal((await(await fetch(HTTP+'/api/coach')).json()).available,false);
 console.log('PASS identity authentication and coach disabled status');
 const solo=await connect();clients.push(solo);
 solo.send({type:'JOIN',gameType:'HIGH_CARD',playerName:'solo-eight',createRoom:true,playerCount:8});
 await until(()=>solo.room,'solo room');
 for(let i=0;i<7;i++)solo.send({type:'ADD_AI',data:'4'});
 await until(()=>solo.side>0&&solo.view?.players?.length===8,'seven AI burst fill');
 console.log('PASS 8 player solo starts with seven consecutive AI requests');
 await until(()=>solo.view.players.filter(p=>p.side!==solo.side).every(p=>p.answered),'seven AI submit without human move');
 assert.equal(solo.view.players.find(p=>p.side===solo.side).answered,false);
 console.log('PASS simultaneous AI act independently before human submits');
 solo.send({type:'LEAVE'});
} finally { for(const c of clients)c.ws.close(); }
