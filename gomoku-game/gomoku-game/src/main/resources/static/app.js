// ============================================================
// 音效引擎
// ============================================================
const AudioCtx = window.AudioContext || window.webkitAudioContext;
let audioCtx = null;
function playSound(freq, duration, type) {
 try {
  if (!audioCtx) audioCtx = new AudioCtx();
  const osc = audioCtx.createOscillator();
  const gain = audioCtx.createGain();
  osc.type = type || 'sine';
  osc.frequency.value = freq;
  gain.gain.setValueAtTime(0.15, audioCtx.currentTime);
  gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + duration);
  osc.connect(gain);
  gain.connect(audioCtx.destination);
  osc.start();
  osc.stop(audioCtx.currentTime + duration);
 } catch(e) {}
}
function playStoneSound() { playSound(800, 0.08, 'sine'); playSound(1200, 0.05, 'sine'); }
function playWinSound() { playSound(523, 0.15, 'triangle'); setTimeout(()=>playSound(659, 0.15, 'triangle'), 150); setTimeout(()=>playSound(784, 0.3, 'triangle'), 300); }
function playLoseSound() { playSound(400, 0.2, 'sawtooth'); setTimeout(()=>playSound(300, 0.3, 'sawtooth'), 200); }

// ============================================================
// 游戏常量
// ============================================================
const BOARD_SIZE = 15;
const CELL = 40;
const PADDING = 30;
const CANVAS_SIZE = PADDING * 2 + CELL * (BOARD_SIZE - 1);

// ============================================================
// 游戏状态
// ============================================================
let ws = null;
let myId = null;
let myName = '';
let myStone = 0;   // 1=黑, 2=白
let roomId = '';
let gameState = 'LOBBY'; // LOBBY | WAITING | PLAYING | OVER | SPECTATING
let currentTurn = 1; // 1=黑, 2=白
let moveCount = 0;
let moveSeq = 0; // 落子序列号（防重复）
let board = Array.from({length: BOARD_SIZE}, () => new Array(BOARD_SIZE).fill(0));
let lastMove = null;
let winLine = null; // 获胜连线坐标 [[row,col],...]
let players = {};
let myWins = 0, myLosses = 0;
let mySessionId = localStorage.getItem('gomoku_session') || null;
let heartbeatTimer = null;
let reconnectAttempts = 0;
const MAX_RECONNECT = 8;
const HEARTBEAT_INTERVAL = 30000;
const RECONNECT_BASE_DELAY = 500;  // 指数退避基础延迟 500ms
const RECONNECT_MAX_DELAY = 30000; // 最大延迟 30s

// ============================================================
// 二进制协议引擎
// ============================================================
const PROTOCOL_MAGIC = 0xCAFE;
const PROTOCOL_VERSION = 0x01;
let useBinary = false; // 是否使用二进制协议（默认 JSON）

// 类型码映射
const BIN_TYPE = {
  GET_ROOMS: 1, JOIN: 2, SPECTATE: 3, ADD_AI: 4, MOVE: 5,
  CHAT: 6, RESTART: 7, LEAVE: 8, RECONNECT: 9, SURRENDER: 10, PING: 11,
  ROOM_LIST: 50, ROOM_INFO: 51, GAME_START: 52, GAME_SYNC: 53,
  GAME_MOVE: 54, GAME_OVER: 55, GAME_CHAT: 56, WAITING: 57,
  RESTART_REQUEST: 58, ERROR: 59
};
const BIN_TYPE_NAME = {};
Object.keys(BIN_TYPE).forEach(k => BIN_TYPE_NAME[BIN_TYPE[k]] = k);

function encodeBinary(msg) {
  const typeCode = BIN_TYPE[msg.type];
  if (typeCode === undefined) return null;

  const parts = [];
  // Header: magic(2) + version(1) + type(1)
  const header = new ArrayBuffer(4);
  const dv = new DataView(header);
  dv.setUint16(0, PROTOCOL_MAGIC);
  dv.setUint8(2, PROTOCOL_VERSION);
  dv.setUint8(3, typeCode);
  parts.push(new Uint8Array(header));

  // Payload by type
  switch (typeCode) {
    case BIN_TYPE.PING:
    case BIN_TYPE.GET_ROOMS:
    case BIN_TYPE.LEAVE:
    case BIN_TYPE.ADD_AI:
    case BIN_TYPE.RESTART:
    case BIN_TYPE.SURRENDER:
      break; // no payload
    case BIN_TYPE.JOIN:
      parts.push(encodeStr(msg.playerName || ''));
      parts.push(encodeNullableStr(msg.roomId));
      break;
    case BIN_TYPE.SPECTATE:
      parts.push(encodeStr(msg.playerName || ''));
      parts.push(encodeStr(msg.roomId || ''));
      break;
    case BIN_TYPE.MOVE:
      parts.push(new Uint8Array([msg.row, msg.col]));
      const seqBuf = new ArrayBuffer(4);
      new DataView(seqBuf).setInt32(0, msg.moveSeq || 0);
      parts.push(new Uint8Array(seqBuf));
      break;
    case BIN_TYPE.CHAT:
      parts.push(encodeStr(msg.message || ''));
      break;
    case BIN_TYPE.RECONNECT:
      parts.push(encodeStr(msg.sessionId || ''));
      break;
  }

  // 合并
  let totalLen = 0;
  for (const p of parts) totalLen += p.length;
  const result = new Uint8Array(totalLen);
  let offset = 0;
  for (const p of parts) { result.set(p, offset); offset += p.length; }
  return result.buffer;
}

function encodeStr(str) {
  const encoder = new TextEncoder();
  const bytes = encoder.encode(str);
  const lenBuf = new ArrayBuffer(2);
  new DataView(lenBuf).setUint16(0, bytes.length);
  const result = new Uint8Array(2 + bytes.length);
  result.set(new Uint8Array(lenBuf), 0);
  result.set(bytes, 2);
  return result;
}

function encodeNullableStr(str) {
  if (str === null || str === undefined) return new Uint8Array([0]);
  const encoder = new TextEncoder();
  const bytes = encoder.encode(str);
  const lenBuf = new ArrayBuffer(2);
  new DataView(lenBuf).setUint16(0, bytes.length);
  const result = new Uint8Array(1 + 2 + bytes.length);
  result[0] = 1;
  result.set(new Uint8Array(lenBuf), 1);
  result.set(bytes, 3);
  return result;
}

function decodeBinary(arrayBuffer) {
  const data = new DataView(arrayBuffer);
  if (data.byteLength < 4) return null;
  const magic = data.getUint16(0);
  if (magic !== PROTOCOL_MAGIC) return null;
  const version = data.getUint8(2);
  const typeCode = data.getUint8(3);
  const typeName = BIN_TYPE_NAME[typeCode];
  if (!typeName) return null;

  const msg = { type: typeName };
  let offset = 4;

  function readStr() {
    if (offset + 2 > data.byteLength) return '';
    const len = data.getUint16(offset); offset += 2;
    if (offset + len > data.byteLength) return '';
    const bytes = new Uint8Array(arrayBuffer, offset, len);
    offset += len;
    return new TextDecoder().decode(bytes);
  }
  function readNullableStr() {
    if (offset + 1 > data.byteLength) return null;
    const flag = data.getUint8(offset); offset += 1;
    if (flag === 0) return null;
    return readStr();
  }

  switch (typeCode) {
    case BIN_TYPE.WAITING:
      msg.roomId = readNullableStr();
      msg.playerId = readNullableStr();
      msg.sessionId = readNullableStr();
      msg.message = readNullableStr();
      break;
    case BIN_TYPE.GAME_START:
      msg.roomId = readNullableStr();
      msg.playerId = readNullableStr();
      msg.stone = offset < data.byteLength ? data.getUint8(offset++) : 0;
      msg.message = readNullableStr();
      msg.data = readNullableStr();
      break;
    case BIN_TYPE.GAME_MOVE:
      msg.roomId = readNullableStr();
      msg.playerId = readNullableStr();
      msg.playerName = readNullableStr();
      msg.row = offset < data.byteLength ? data.getUint8(offset++) : 0;
      msg.col = offset < data.byteLength ? data.getUint8(offset++) : 0;
      msg.stone = offset < data.byteLength ? data.getUint8(offset++) : 0;
      if (offset + 4 <= data.byteLength) { msg.moveSeq = data.getInt32(offset); offset += 4; }
      msg.data = readNullableStr();
      break;
    case BIN_TYPE.GAME_OVER:
      msg.roomId = readNullableStr();
      msg.winner = readNullableStr();
      msg.message = readNullableStr();
      msg.data = readNullableStr();
      break;
    case BIN_TYPE.GAME_CHAT:
      msg.roomId = readNullableStr();
      msg.playerId = readNullableStr();
      msg.playerName = readNullableStr();
      msg.message = readNullableStr();
      break;
    case BIN_TYPE.GAME_SYNC:
      msg.roomId = readNullableStr();
      msg.playerId = readNullableStr();
      msg.sessionId = readNullableStr();
      msg.stone = offset < data.byteLength ? data.getUint8(offset++) : 0;
      msg.message = readNullableStr();
      msg.data = readNullableStr();
      break;
    case BIN_TYPE.ROOM_INFO:
    case BIN_TYPE.ROOM_LIST:
      msg.roomId = readNullableStr();
      msg.data = readNullableStr();
      break;
    case BIN_TYPE.RESTART_REQUEST:
      msg.roomId = readNullableStr();
      msg.playerName = readNullableStr();
      msg.message = readNullableStr();
      break;
    case BIN_TYPE.ERROR:
      msg.message = readNullableStr();
      break;
  }
  return msg;
}

// ============================================================
// 棋盘绘制
// ============================================================
const canvas = document.getElementById('board-canvas');
const ctx = canvas.getContext('2d');
canvas.width = CANVAS_SIZE;
canvas.height = CANVAS_SIZE;

function drawBoard() {
  // 木纹背景
  const grad = ctx.createLinearGradient(0, 0, CANVAS_SIZE, CANVAS_SIZE);
  grad.addColorStop(0, '#c9a55e');
  grad.addColorStop(0.5, '#d4b070');
  grad.addColorStop(1, '#b8914a');
  ctx.fillStyle = grad;
  ctx.fillRect(0, 0, CANVAS_SIZE, CANVAS_SIZE);

  // 木纹纹理
  ctx.strokeStyle = 'rgba(100,60,10,0.06)';
  ctx.lineWidth = 1;
  for (let i = 0; i < CANVAS_SIZE; i += 4) {
    ctx.beginPath();
    ctx.moveTo(0, i + Math.sin(i*0.05)*3);
    ctx.lineTo(CANVAS_SIZE, i + Math.sin(i*0.05+1)*3);
    ctx.stroke();
  }

  // 棋盘线
  ctx.strokeStyle = '#7a5c2e';
  ctx.lineWidth = 0.8;

  for (let i = 0; i < BOARD_SIZE; i++) {
    const x = PADDING + i * CELL;
    const y = PADDING + i * CELL;

    ctx.beginPath();
    ctx.moveTo(x, PADDING);
    ctx.lineTo(x, PADDING + (BOARD_SIZE-1)*CELL);
    ctx.stroke();

    ctx.beginPath();
    ctx.moveTo(PADDING, y);
    ctx.lineTo(PADDING + (BOARD_SIZE-1)*CELL, y);
    ctx.stroke();
  }

  // 边框
  ctx.strokeStyle = '#6a4c1e';
  ctx.lineWidth = 2;
  ctx.strokeRect(PADDING, PADDING, (BOARD_SIZE-1)*CELL, (BOARD_SIZE-1)*CELL);

  // 星位（天元和四个角星）
  const stars = [[7,7],[3,3],[3,11],[11,3],[11,11]];
  stars.forEach(([r,c]) => {
    ctx.fillStyle = '#6a4c1e';
    ctx.beginPath();
    ctx.arc(PADDING + c*CELL, PADDING + r*CELL, 4, 0, Math.PI*2);
    ctx.fill();
  });

  // 坐标标注
  ctx.fillStyle = 'rgba(80,50,15,0.7)';
  ctx.font = '10px JetBrains Mono, monospace';
  ctx.textAlign = 'center';
  const letters = 'ABCDEFGHJKLMNOP';
  for (let i = 0; i < BOARD_SIZE; i++) {
    ctx.fillText(letters[i], PADDING + i*CELL, PADDING - 10);
    ctx.fillText(i+1, PADDING - 18, PADDING + i*CELL + 4);
  }

  // 绘制所有棋子
  for (let r = 0; r < BOARD_SIZE; r++) {
    for (let c = 0; c < BOARD_SIZE; c++) {
      if (board[r][c] !== 0) {
        const isWin = winLine && winLine.some(p => p[0]===r && p[1]===c);
        drawStone(r, c, board[r][c], lastMove && lastMove[0]===r && lastMove[1]===c, isWin);
      }
    }
  }
}

function drawStone(row, col, stone, isLast=false, isWin=false) {
  const x = PADDING + col * CELL;
  const y = PADDING + row * CELL;
  const r = CELL * 0.44;

  ctx.save();

  // 阴影
  ctx.shadowColor = 'rgba(0,0,0,0.5)';
  ctx.shadowBlur = 8;
  ctx.shadowOffsetX = 2;
  ctx.shadowOffsetY = 3;

  // 棋子渐变
  const grad = ctx.createRadialGradient(x-r*0.3, y-r*0.3, 0, x, y, r);
  if (stone === 1) {
    grad.addColorStop(0, '#666');
    grad.addColorStop(0.4, '#222');
    grad.addColorStop(1, '#0a0a0a');
  } else {
    grad.addColorStop(0, '#ffffff');
    grad.addColorStop(0.5, '#eeebe4');
    grad.addColorStop(1, '#c8c4bc');
  }

  ctx.fillStyle = grad;
  ctx.beginPath();
  ctx.arc(x, y, r, 0, Math.PI*2);
  ctx.fill();
  ctx.shadowColor = 'transparent';

  // 高光
  const hiGrad = ctx.createRadialGradient(x-r*0.35, y-r*0.35, 0, x, y, r*0.8);
  hiGrad.addColorStop(0, stone===1 ? 'rgba(255,255,255,0.2)' : 'rgba(255,255,255,0.7)');
  hiGrad.addColorStop(1, 'transparent');
  ctx.fillStyle = hiGrad;
  ctx.beginPath();
  ctx.arc(x, y, r, 0, Math.PI*2);
  ctx.fill();

  // 最后落子标记
  if (isLast) {
    ctx.fillStyle = stone===1 ? 'rgba(255,80,80,0.9)' : 'rgba(220,60,60,0.9)';
    ctx.beginPath();
    ctx.arc(x, y, r*0.25, 0, Math.PI*2);
    ctx.fill();
  }

  // 获胜连线高亮：红色发光环
  if (isWin) {
    ctx.strokeStyle = 'rgba(255,60,60,0.9)';
    ctx.lineWidth = 3;
    ctx.shadowColor = 'rgba(255,60,60,0.8)';
    ctx.shadowBlur = 12;
    ctx.beginPath();
    ctx.arc(x, y, r + 3, 0, Math.PI*2);
    ctx.stroke();
    ctx.shadowColor = 'transparent';
    ctx.shadowBlur = 0;
  }

  ctx.restore();
}

// 落子预览
canvas.addEventListener('mousemove', function(e) {
  if (gameState !== 'PLAYING' || currentTurn !== myStone) return;
  const rect = canvas.getBoundingClientRect();
  const scaleX = canvas.width / rect.width;
  const scaleY = canvas.height / rect.height;
  const mx = (e.clientX - rect.left) * scaleX;
  const my = (e.clientY - rect.top) * scaleY;
  const col = Math.round((mx - PADDING) / CELL);
  const row = Math.round((my - PADDING) / CELL);

  drawBoard();
  if (row >= 0 && row < BOARD_SIZE && col >= 0 && col < BOARD_SIZE && board[row][col] === 0) {
    const x = PADDING + col * CELL;
    const y = PADDING + row * CELL;
    ctx.save();
    ctx.globalAlpha = 0.4;
    ctx.fillStyle = myStone === 1 ? '#111' : '#eee';
    ctx.beginPath();
    ctx.arc(x, y, CELL*0.44, 0, Math.PI*2);
    ctx.fill();
    ctx.restore();
  }
});

canvas.addEventListener('mouseleave', drawBoard);

// Touch support for mobile
canvas.addEventListener('touchstart', function(e) {
 e.preventDefault();
 if (gameState !== 'PLAYING' || currentTurn !== myStone) return;
 const touch = e.touches[0];
 const rect = canvas.getBoundingClientRect();
 const scaleX = canvas.width / rect.width;
 const scaleY = canvas.height / rect.height;
 const mx = (touch.clientX - rect.left) * scaleX;
 const my = (touch.clientY - rect.top) * scaleY;
 const col = Math.round((mx - PADDING) / CELL);
 const row = Math.round((my - PADDING) / CELL);

 if (row < 0 || row >= BOARD_SIZE || col < 0 || col >= BOARD_SIZE) return;
 if (board[row][col] !== 0) return;

 sendMove(row, col);
}, { passive: false });

canvas.addEventListener('click', function(e) {
  if (gameState !== 'PLAYING' || currentTurn !== myStone) return;
  const rect = canvas.getBoundingClientRect();
  const scaleX = canvas.width / rect.width;
  const scaleY = canvas.height / rect.height;
  const mx = (e.clientX - rect.left) * scaleX;
  const my = (e.clientY - rect.top) * scaleY;
  const col = Math.round((mx - PADDING) / CELL);
  const row = Math.round((my - PADDING) / CELL);

  if (row < 0 || row >= BOARD_SIZE || col < 0 || col >= BOARD_SIZE) return;
  if (board[row][col] !== 0) return;

  sendMove(row, col);
});

// ============================================================
// WebSocket 通信
// ============================================================
function joinGame() {
  const url = document.getElementById('ws-url').value.trim() || 'ws://localhost:8887';
  myName = document.getElementById('player-name').value.trim() || '匿名玩家';

  connect(url);
}

function connect(url) {
  try {
    ws = new WebSocket(url);
  } catch (e) {
    alert('WebSocket 地址无效：' + url);
    return;
  }

  ws.binaryType = 'arraybuffer'; // 支持接收二进制帧

  ws.onopen = () => {
    setStatus(true, '已连接');
    reconnectAttempts = 0;
    document.getElementById('reconnect-bar').classList.remove('show');

    // Start heartbeat
    if (heartbeatTimer) clearInterval(heartbeatTimer);
    heartbeatTimer = setInterval(() => {
      send({ type: 'PING' });
    }, HEARTBEAT_INTERVAL);

    if (mySessionId) {
      // Try reconnect first — but don't show error if it fails (first connection)
      send({ type: 'RECONNECT', sessionId: mySessionId });
    }

    document.getElementById('login-screen').style.display = 'none';
    showLobby();
    refreshRooms();
  };

  ws.onmessage = (e) => {
    try {
      let msg;
      if (typeof e.data === 'string') {
        // JSON 协议
        msg = JSON.parse(e.data);
      } else if (e.data instanceof ArrayBuffer) {
        // 二进制协议
        msg = decodeBinary(e.data);
        if (!msg) return;
      } else if (e.data instanceof Blob) {
        // Blob 模式（某些浏览器）
        e.data.arrayBuffer().then(buf => {
          const decoded = decodeBinary(buf);
          if (decoded) handleMessage(decoded);
        });
        return;
      } else {
        return;
      }
      handleMessage(msg);
    } catch(err) {
      console.error('消息解析失败', err);
    }
  };

  ws.onclose = () => {
    setStatus(false, '已断开');
    if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }

    // 指数退避重连：delay = min(BASE * 2^attempt, MAX_DELAY) + jitter
    if (mySessionId && reconnectAttempts < MAX_RECONNECT) {
      reconnectAttempts++;
      const exponentialDelay = Math.min(
        RECONNECT_BASE_DELAY * Math.pow(2, reconnectAttempts - 1),
        RECONNECT_MAX_DELAY
      );
      // 加随机抖动 ±20%，避免雷群效应
      const jitter = exponentialDelay * (0.8 + Math.random() * 0.4);
      const delay = Math.round(jitter);
      document.getElementById('reconnect-bar').classList.add('show');
      document.getElementById('reconnect-bar').textContent =
        `连接断开，${(delay/1000).toFixed(1)}秒后重连 (${reconnectAttempts}/${MAX_RECONNECT})...`;
      console.log(`[重连] 指数退避: attempt=${reconnectAttempts}, delay=${delay}ms`);
      setTimeout(() => {
        const url = document.getElementById('ws-url').value.trim() || 'ws://localhost:8887';
        connect(url);
      }, delay);
    } else {
      gameState = 'LOBBY';
      showLogin();
    }
  };

  ws.onerror = () => {
    setStatus(false, '连接失败');
  };
}

function showLogin() {
  document.getElementById('login-screen').style.display = 'flex';
  document.getElementById('lobby-screen').style.display = 'none';
  document.getElementById('game-screen').style.display = 'none';
}

function showLobby() {
  document.getElementById('login-screen').style.display = 'none';
  document.getElementById('lobby-screen').style.display = 'block';
  document.getElementById('game-screen').style.display = 'none';
  gameState = 'LOBBY';
}

function showGame() {
  document.getElementById('login-screen').style.display = 'none';
  document.getElementById('lobby-screen').style.display = 'none';
  document.getElementById('game-screen').style.display = 'grid';
  document.getElementById('chat-messages').innerHTML = '<div class="chat-msg system">欢迎来到五子棋对战！</div>';
  drawBoard();
}

function refreshRooms() {
  // 优先用 HTTP API，WebSocket 作为降级
  fetch('/api/rooms')
    .then(r => r.text())
    .then(jsonStr => {
      const msg = { type: 'ROOM_LIST', data: jsonStr };
      handleRoomList(msg);
    })
    .catch(() => {
      send({ type: 'GET_ROOMS' });
    });
}

function createRoom() {
  send({ type: 'JOIN', playerName: myName });
  showGame();
}

function joinSpecificRoom(id) {
  send({ type: 'JOIN', playerName: myName, roomId: id });
  showGame();
}

function spectateRoom(id) {
  send({ type: 'SPECTATE', playerName: myName, roomId: id });
  showGame();
}

function leaveRoom() {
  send({ type: 'LEAVE' });
  // 重置前端状态
  board = Array.from({length: BOARD_SIZE}, () => new Array(BOARD_SIZE).fill(0));
  players = {};
  moveCount = 0;
  lastMove = null;
  myStone = 0;
  currentTurn = 1;
  gameState = 'LOBBY';
  document.getElementById('gameover-overlay').classList.remove('show');
  document.getElementById('move-count').textContent = '落子数: 0';
  showLobby();
  drawBoard();
}

function addAI() {
  send({ type: 'ADD_AI' });
}

function send(obj) {
  if (!ws || ws.readyState !== WebSocket.OPEN) return;
  if (useBinary) {
    const binary = encodeBinary(obj);
    if (binary) {
      ws.send(binary);
      return;
    }
    // 二进制编码失败，降级到 JSON
  }
  ws.send(JSON.stringify(obj));
}

function sendMove(row, col) {
  moveSeq++;
  send({ type: 'MOVE', row, col, moveSeq });
}

function sendChat() {
  const input = document.getElementById('chat-input');
  const msg = input.value.trim();
  if (!msg) return;
  send({ type: 'CHAT', message: msg });
  input.value = '';
}

function surrender() {
  if (gameState !== 'PLAYING' || myId === 'SPECTATOR') return;
  showConfirm('确定要认输吗？', function() { send({ type: 'SURRENDER' }); });
}

function showConfirm(message, onOk) {
  var dlg = document.getElementById('confirm-dialog');
  document.getElementById('confirm-msg').textContent = message;
  var cancelBtn = document.getElementById('confirm-cancel');
  // 如果没有 onOk，说明是纯提示，只显示确定按钮
  cancelBtn.style.display = onOk ? 'inline-block' : 'none';
  dlg.classList.add('show');
  var okBtn = document.getElementById('confirm-ok');
  okBtn.textContent = onOk ? '确定' : '知道了';
  function cleanup() {
    dlg.classList.remove('show');
    okBtn.removeEventListener('click', onOkHandler);
    cancelBtn.removeEventListener('click', onCancel);
  }
  function onOkHandler() { cleanup(); if (onOk) onOk(); }
  function onCancel() { cleanup(); }
  okBtn.addEventListener('click', onOkHandler);
  cancelBtn.addEventListener('click', onCancel);
}

function dismissOverlay() {
  document.getElementById('gameover-overlay').classList.remove('show');
  // 在棋盘下方显示再来一局按钮
  const bar = document.getElementById('restart-bar');
  if (bar && myId !== 'SPECTATOR') bar.style.display = 'flex';
}

function requestRestart() {
  document.getElementById('gameover-overlay').classList.remove('show');
  document.getElementById('restart-bar').style.display = 'none';
  if (myId === 'SPECTATOR') return;

  if (gameState === 'OVER') {
    send({ type: 'RESTART' });
  } else if (gameState === 'WAITING') {
    board = Array.from({length: BOARD_SIZE}, () => new Array(BOARD_SIZE).fill(0));
    moveCount = 0;
    lastMove = null;
    winLine = null;
    document.getElementById('move-count').textContent = '落子数: 0';
    drawBoard();
  }
}

// ============================================================
// 消息处理
// ============================================================
function handleMessage(msg) {
  switch (msg.type) {
    case 'ROOM_LIST':
      handleRoomList(msg);
      break;

    case 'ROOM_INFO':
      handleRoomInfo(msg);
      break;

    case 'WAITING':
      gameState = 'WAITING';
      if (msg.playerId) myId = msg.playerId;
      if (msg.sessionId) {
        mySessionId = msg.sessionId;
        localStorage.setItem('gomoku_session', mySessionId);
      }
      // 重置棋盘画面和游戏状态，但保留 players（由 ROOM_INFO 更新）
      board = Array.from({length: BOARD_SIZE}, () => new Array(BOARD_SIZE).fill(0));
      moveCount = 0;
      lastMove = null;
      document.getElementById('gameover-overlay').classList.remove('show');
      document.getElementById('move-count').textContent = '落子数: 0';
      showSurrenderBtn();
      let wMsg = msg.message;
      if (!wMsg) {
        wMsg = (myId === 'SPECTATOR') ? '等待玩家加入...' : '等待对手加入...';
      }
      setStatusMsg(wMsg, 'waiting');
      addChat(wMsg, 'system');
      updatePlayerPanel();
      drawBoard();
      break;

    case 'GAME_START':
      handleGameStart(msg);
      break;

    case 'GAME_SYNC':
      handleGameSync(msg);
      break;

    case 'GAME_MOVE':
      handleGameMove(msg);
      break;

    case 'GAME_OVER':
      handleGameOver(msg);
      break;

    case 'GAME_CHAT':
      handleGameChat(msg);
      break;

    case 'RESTART_REQUEST':
      showConfirm(msg.message || '对手请求再来一局', function() {
        send({ type: 'RESTART' });
      });
      break;

    case 'ERROR':
      // Silently handle reconnect failure — clear stale session
      if (msg.message && msg.message.includes('重连')) {
        mySessionId = null;
        localStorage.removeItem('gomoku_session');
        break;
      }
      showConfirm(msg.message || '未知错误', null);
      if (msg.message === '房间内玩家已全部离开，房间解散' || msg.message.includes('失败') || msg.message.includes('不存在') || msg.message.includes('已满')) {
        showLobby();
      }
      break;
  }
}

function handleRoomInfo(msg) {
  if (msg.roomId) {
    roomId = msg.roomId;
    document.getElementById('room-id').textContent = 'ROOM: ' + roomId;
  }
  try {
    const data = JSON.parse(msg.data);
    players = {};
    if (data.players && Array.isArray(data.players)) {
      data.players.forEach(p => { players[p.id] = p; });
    }
    const spectatorEl = document.getElementById('spectator-count');
    if (spectatorEl) spectatorEl.textContent = '观众数: ' + (data.spectatorCount || 0);
    
    updatePlayerPanel();
  } catch(e) {
    console.error('handleRoomInfo error', e);
  }
}

function handleRoomList(msg) {
  const container = document.getElementById('room-list-container');
  try {
    const list = JSON.parse(msg.data);
    container.innerHTML = '';
    if (list.length === 0) {
      const empty = document.createElement('div');
      empty.style.cssText = 'color:var(--text-dim);grid-column:1/-1;text-align:center;padding:40px;';
      empty.textContent = '暂无游戏房间，你可以点击"快速开始"创建房间';
      container.appendChild(empty);
      return;
    }

    list.forEach(r => {
      const isPlaying = r.state === 'PLAYING';
      const isFull = r.playerCount >= 2;
      const stateClass = isPlaying ? 'state-playing' : 'state-waiting';
      const stateText = isPlaying ? '对战中' : '等待中';

      const card = document.createElement('div');
      card.className = 'room-card';

      const header = document.createElement('div');
      header.className = 'room-card-header';

      const idSpan = document.createElement('span');
      idSpan.className = 'room-card-id';
      idSpan.textContent = r.roomId;

      const stateSpan = document.createElement('span');
      stateSpan.className = 'room-card-state ' + stateClass;
      stateSpan.textContent = stateText;

      header.appendChild(idSpan);
      header.appendChild(stateSpan);

      const info = document.createElement('div');
      info.className = 'room-card-info';

      const playersText = r.players.length > 0 ? r.players.map(escHtml).join(' vs ') : '空房间';
      const countDiv = document.createElement('div');
      countDiv.style.cssText = 'font-size:0.75rem;margin-top:4px;';
      countDiv.textContent = '人数: ' + r.playerCount + '/2 | 观众: ' + r.spectatorCount;

      const playerDiv = document.createElement('div');
      playerDiv.textContent = '玩家: ';
      playerDiv.innerHTML = '玩家: ' + playersText;

      info.appendChild(playerDiv);
      info.appendChild(countDiv);

      const actions = document.createElement('div');
      actions.className = 'room-card-actions';

      if (!isFull && !isPlaying) {
        const joinBtn = document.createElement('button');
        joinBtn.className = 'btn-outline';
        joinBtn.textContent = '加入';
        const rid = r.roomId;
        joinBtn.onclick = function() { joinSpecificRoom(rid); };
        actions.appendChild(joinBtn);
      }

      const specBtn = document.createElement('button');
      specBtn.className = 'btn-outline';
      specBtn.style.cssText = 'border-color:#555;color:#ccc;';
      specBtn.textContent = '观战';
      const rid2 = r.roomId;
      specBtn.onclick = function() { spectateRoom(rid2); };
      actions.appendChild(specBtn);

      card.appendChild(header);
      card.appendChild(info);
      card.appendChild(actions);
      container.appendChild(card);
    });
  } catch(e) {
    console.error(e);
  }
}

function handleGameSync(msg) {
  roomId = msg.roomId;

  // 区分观战和断线重连
  if (msg.playerId && msg.stone) {
    myId = msg.playerId;
    myStone = msg.stone;
    if (msg.sessionId) {
      mySessionId = msg.sessionId;
      localStorage.setItem('gomoku_session', mySessionId);
    }
  } else {
    gameState = 'SPECTATING';
    myId = 'SPECTATOR';
  }
  
  try {
    const data = JSON.parse(msg.data);
    
    // 恢复玩家信息
    players = {};
    data.roomData.players.forEach(p => { players[p.id] = p; });
    currentTurn = data.roomData.currentTurn;
    document.getElementById('spectator-count').textContent = '观众数: ' + (data.roomData.spectatorCount || 0);
    
    // 恢复棋盘
    board = data.board;
    
    // 重新计算 moveCount
    moveCount = 0;
    for(let r=0; r<BOARD_SIZE; r++) {
        for(let c=0; c<BOARD_SIZE; c++) {
            if(board[r][c] !== 0) moveCount++;
        }
    }
  } catch(e) {}

  document.getElementById('room-id').textContent = 'ROOM: ' + roomId + (myId === 'SPECTATOR' ? ' (观战中)' : '');
  document.getElementById('move-count').textContent = '落子数: ' + moveCount;
  document.getElementById('gameover-overlay').classList.remove('show');

  updatePlayerPanel();
  setStatusMsg(myId === 'SPECTATOR' ? '观战中' : '重连成功', 'waiting');
  drawBoard();

  addChat(`系统：${msg.message}`, 'system');
}

function handleGameStart(msg) {
  myId = msg.playerId;
  myStone = msg.stone;
  roomId = msg.roomId;
  gameState = 'PLAYING';
  currentTurn = 1; // 黑棋先
  moveCount = 0;
  moveSeq = 0;
  board = Array.from({length: BOARD_SIZE}, () => new Array(BOARD_SIZE).fill(0));
  lastMove = null;
  winLine = null;

  // 解析房间数据
  try {
    const data = JSON.parse(msg.data);
    players = {};
    data.players.forEach(p => { players[p.id] = p; });
    currentTurn = data.currentTurn;
    document.getElementById('spectator-count').textContent = '观众数: ' + (data.spectatorCount || 0);
  } catch(e) {}

  document.getElementById('room-id').textContent = 'ROOM: ' + roomId;
  document.getElementById('gameover-overlay').classList.remove('show');
  document.getElementById('restart-bar').style.display = 'none';

  updatePlayerPanel();
  updateTurnStatus();
  showSurrenderBtn();
  drawBoard();

  addChat(`游戏开始！你执${myStone===1?'黑':'白'}棋，对手：${msg.message}`, 'system');
}

function showSurrenderBtn() {
  var surrenderRow = document.getElementById('surrender-row');
  if (surrenderRow) {
    surrenderRow.style.display = (gameState === 'PLAYING' && myId && myId !== 'SPECTATOR') ? 'block' : 'none';
  }
}

function handleGameMove(msg) {
  const r = msg.row, c = msg.col;
  board[r][c] = msg.stone;
  lastMove = [r, c];
  currentTurn = parseInt(msg.data) || (msg.stone === 1 ? 2 : 1);
  moveCount++;
  // 同步 moveSeq（以服务端为准）
  if (msg.moveSeq && msg.moveSeq > moveSeq) {
    moveSeq = msg.moveSeq;
  }

  document.getElementById('move-count').textContent = '落子数: ' + moveCount;
  updateTurnStatus();
  drawBoard();
}

function handleGameOver(msg) {
  gameState = 'OVER';
  showSurrenderBtn();
  const overlay = document.getElementById('gameover-overlay');
  const result = document.getElementById('gameover-result');
  const sub = document.getElementById('gameover-sub');
  const scores = document.getElementById('gameover-scores');

  const isSpectator = myId === 'SPECTATOR';
  const isDraw = msg.winner === 'draw';
  const isWin = msg.winner === myId;

  // 解析附加数据（分数 + 获胜连线）
  let winLineData = null;
  try {
    const data = JSON.parse(msg.data);
    winLineData = data.winLine || null;
    // 分数
    let scoresHtml = '';
    data.scores.forEach(p => {
      scoresHtml += `<div class="score-item">
        <div class="score-num">${p.wins}</div>
        <div class="score-label">${escHtml(p.name.substring(0,6))} 胜</div>
      </div>`;
    });
    scores.innerHTML = scoresHtml;
  } catch(e) { scores.innerHTML = ''; }

  // 高亮获胜连线
  if (winLineData) {
    winLine = winLineData;
    drawBoard();
  }

  if (isSpectator) {
    result.className = 'gameover-result ' + (isDraw ? 'draw' : 'win');
    if (isDraw) {
      result.textContent = '平  局';
      setStatusMsg('游戏结束：平局', 'draw');
    } else {
      const winnerName = players[msg.winner] ? players[msg.winner].name : '玩家';
      result.textContent = winnerName + ' 获胜';
      setStatusMsg('游戏结束：' + winnerName + ' 获胜', 'win');
    }
  } else {
    result.className = 'gameover-result ' + (isDraw ? 'draw' : isWin ? 'win' : 'lose');
    result.textContent = isDraw ? '平  局' : isWin ? '胜  利' : '失  败';
    setStatusMsg(isDraw ? '平局' : isWin ? '你赢了！' : '你输了', isDraw ? 'draw' : isWin ? 'win' : 'lose');
  }

  sub.textContent = msg.message || '';

  // 按钮控制：overlay 中有 btn-review 和 btn-restart 两个
  const restartBtn = overlay.querySelector('.btn-restart');
  const reviewBtn = overlay.querySelector('.btn-review');
  if (isSpectator) {
    // 观战者：只显示"查看棋盘"，隐藏"再来一局"
    if (reviewBtn) reviewBtn.style.display = 'inline-block';
    if (restartBtn) restartBtn.style.display = 'none';
  } else {
    // 对战者：两个都显示
    if (reviewBtn) reviewBtn.style.display = 'inline-block';
    if (restartBtn) restartBtn.style.display = 'inline-block';
  }

  // 新开局时清除 winLine
  overlay.classList.add('show');
}

function handleGameChat(msg) {
  const isMe = msg.playerId === myId;
  addChat(msg.message, isMe ? 'me' : 'other', msg.playerName, isMe);
}

// ============================================================
// UI 更新
// ============================================================
function updatePlayerPanel() {
  let blackP = null, whiteP = null;
  let unassigned = [];
  for (const p of Object.values(players)) {
    if (p.stone === 1) blackP = p;
    else if (p.stone === 2) whiteP = p;
    else unassigned.push(p);
  }

  if (!blackP && unassigned.length > 0) blackP = unassigned.shift();
  if (!whiteP && unassigned.length > 0) whiteP = unassigned.shift();

  if (!blackP && myId && myId !== 'SPECTATOR' && gameState === 'WAITING' && Object.keys(players).length === 0) {
    blackP = { id: myId, name: myName || '你', stone: 0, wins: myWins || 0 };
  }

  const isSinglePlayer = Object.keys(players).length === 1 && myId !== 'SPECTATOR';
  const aiBtnHtml = isSinglePlayer ? `<div style="margin-top:16px;"><button class="btn-outline" style="padding:6px 12px;font-size:0.8rem;" onclick="addAI()">添加电脑</button></div>` : '';

  const blackEl = document.getElementById('player-black');
  if (blackP) {
    const isMe = blackP.id === myId;
    blackEl.innerHTML = `<div class="player-card">
      <div class="player-card-top">
        <div class="stone-icon black"></div>
        <div class="player-name ${isMe ? 'you' : ''}">${escHtml(blackP.name)}${isMe ? ' (你)' : ''}</div>
      </div>
      <div class="player-stats">
        <span>胜 ${blackP.wins}</span>
      </div>
      <div id="turn-black" style="display:none">
        <span class="turn-badge">● 落子中</span>
      </div>
    </div>`;
  } else {
    blackEl.innerHTML = `<div class="waiting-slot">等待玩家...${aiBtnHtml}</div>`;
  }

  const whiteEl = document.getElementById('player-white');
  if (whiteP) {
    const isMe = whiteP.id === myId;
    whiteEl.innerHTML = `<div class="player-card">
      <div class="player-card-top">
        <div class="stone-icon white"></div>
        <div class="player-name ${isMe ? 'you' : ''}">${escHtml(whiteP.name)}${isMe ? ' (你)' : ''}</div>
      </div>
      <div class="player-stats">
        <span>胜 ${whiteP.wins}</span>
      </div>
      <div id="turn-white" style="display:none">
        <span class="turn-badge">● 落子中</span>
      </div>
    </div>`;
  } else {
    whiteEl.innerHTML = `<div class="waiting-slot">等待玩家...${aiBtnHtml}</div>`;
  }
  
  if (gameState === 'PLAYING') {
    updateTurnStatus();
  }
}

function updateTurnStatus() {
  // 显示/隐藏认输按钮
  var surrenderRow = document.getElementById('surrender-row');
  if (surrenderRow) {
    surrenderRow.style.display = (gameState === 'PLAYING' && myId && myId !== 'SPECTATOR') ? 'block' : 'none';
  }
  // 隐藏所有 turn badge
  ['black','white'].forEach(c => {
    const el = document.getElementById('turn-' + c);
    if (el) el.style.display = 'none';
  });

  const isMyTurn = currentTurn === myStone;
  const turnColor = currentTurn === 1 ? 'black' : 'white';
  const el = document.getElementById('turn-' + turnColor);
  if (el) el.style.display = 'block';

  if (isMyTurn) {
    setStatusMsg('你的回合，请落子', 'your-turn');
    canvas.style.cursor = 'crosshair';
  } else {
    setStatusMsg('等待对手落子...', 'opponent-turn');
    canvas.style.cursor = 'not-allowed';
  }
}

function setStatusMsg(text, cls) {
  const el = document.getElementById('status-msg');
  el.textContent = text;
  el.className = 'status-area ' + (cls || 'waiting');
}

function setStatus(connected, text) {
  document.getElementById('status-dot').className = 'status-dot' + (connected ? ' connected' : '');
  document.getElementById('status-text').textContent = text;
}

function addChat(text, type, name, isMe) {
  const container = document.getElementById('chat-messages');
  const div = document.createElement('div');

  if (type === 'system') {
    div.className = 'chat-msg system';
    div.textContent = text;
  } else {
    div.className = 'chat-msg';
    div.innerHTML = `<span class="msg-name ${type}">${name}：</span>${escHtml(text)}`;
  }

  container.appendChild(div);
  container.scrollTop = container.scrollHeight;
}

function escHtml(str) {
  return str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

// ============================================================
// 初始化
// ============================================================
drawBoard();

// 自动填充 WebSocket 地址
document.getElementById('ws-url').value = 'ws://' + window.location.hostname + ':8887';

// 回车加入
document.getElementById('player-name').addEventListener('keydown', e => {
  if (e.key === 'Enter') joinGame();
});
