function readPreference(key, fallback = null) {
  try { return localStorage.getItem(key) ?? fallback; } catch (_) { return fallback; }
}
function savePreference(key, value) { try { localStorage.setItem(key, value); } catch (_) {} }
let soundEnabled = readPreference('gomoku_sound', 'on') === 'on';
let guestToken = readPreference('gomoku_guest');
let authMode = 'guest';
let accountInfo = null;
let targetPlayers = 2;
function toggleSound() {
  soundEnabled = !soundEnabled; savePreference('gomoku_sound', soundEnabled ? 'on' : 'off');
  const button = document.getElementById('sound-toggle');
  button.textContent = '音效 ' + (soundEnabled ? '开' : '关');
  button.setAttribute('aria-pressed', String(soundEnabled));
}
function selectedPlayerCount(type = selectedGameType) {
  const module = GameModules[type];
  const select = document.getElementById('room-player-count');
  return module.maxPlayers > 2 ? Number(select.value || module.defaultPlayers) : 2;
}
function updatePlayerCountSelector() {
  const module = GameModules[selectedGameType];
  const select = document.getElementById('room-player-count');
  select.replaceChildren();
  for (let count = module.minPlayers || 2; count <= (module.maxPlayers || 2); count++) {
    const option = document.createElement('option'); option.value = count; option.textContent = count + ' 人';
    select.appendChild(option);
  }
  select.value = module.defaultPlayers || 2; select.disabled = (module.maxPlayers || 2) === 2;
}

// ============================================================
// 音效引擎
// ============================================================
const AudioCtx = window.AudioContext || window.webkitAudioContext;
let audioCtx = null;
function playSound(freq, duration, type) {
 if (!soundEnabled) return;
 try {
  if (!audioCtx) audioCtx = new AudioCtx();
  if (audioCtx.state === 'suspended') audioCtx.resume();
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
// 游戏状态
// ============================================================
let ws = null;
let myId = null;
let myName = '';
let myStone = 0;   // 1=黑, 2=白
let roomId = '';
let gameState = 'LOBBY'; // LOBBY | WAITING | PLAYING | OVER | SPECTATING
let currentTurn = 1; // 1=黑/先手方, 2=白/后手方
let moveCount = 0;
let moveSeq = 0; // 落子序列号（防重复）
let actionVersion = null; // 服务端下发的当前对局/轮次或局面版本
let selectedGameType = 'GOMOKU';  // 大厅选中的游戏类型
let currentGameType = 'GOMOKU';   // 当前所在房间的游戏类型
let selectedFrom = null;          // 象棋选中的起点 {row, col}
let cardAttackFrom = null;        // 卡牌：选中待攻击的己方随从下标
let nimSelectedPile = null;      // 取石子：选中待取子的堆号
// 卡牌：已点选、但还需要指定目标的法术牌下标（null = 未处于选目标状态）
let cardPendingTarget = null;
let board = [];                   // 当前棋盘（尺寸由 GameModule 决定）
let lastMove = null;
let winLine = null; // 获胜连线坐标 [[row,col],...]
let players = {};
let myWins = 0, myLosses = 0;
// 匹配体验：等待计时、同玩法等待提示与大厅玩法卡的人气标识
let latestMatchOverview = null;
let matchWaitStartedAt = 0;
let matchWaitTimer = null;
let matchRoomIsPublic = false;
let matchOverviewDebounce = null;
// ============ 会话持久化：标签页内 + 关闭后恢复 ============
// sessionStorage 只覆盖当前标签页；关闭再打开时用它 + localStorage 备份找回座位。
// 服务端会话与席位保留 10 分钟，备份超过这个窗口就不值得再试。
const SESSION_BACKUP_KEY = 'gomoku_session_backup';
const SESSION_BACKUP_TTL_MS = 10 * 60 * 1000;
let mySessionId = null;
try { mySessionId = sessionStorage.getItem('gomoku_session'); } catch (_) {}
if (!mySessionId) {
  try {
    const raw = localStorage.getItem(SESSION_BACKUP_KEY);
    if (raw) {
      const backup = JSON.parse(raw);
      if (backup && backup.id && Date.now() - backup.ts <= SESSION_BACKUP_TTL_MS) mySessionId = backup.id;
      else localStorage.removeItem(SESSION_BACKUP_KEY);
    }
  } catch (_) {}
}
function clearSessionBackup() {
  try { localStorage.removeItem(SESSION_BACKUP_KEY); } catch (_) {}
}
function clearPersistedSession() {
  try { sessionStorage.removeItem('gomoku_session'); } catch (_) {}
  clearSessionBackup();
}
function backupSessionForRecovery() {
  // 只在页面即将关闭时写备份：多标签场景下「最后关闭的标签」决定恢复目标。
  try {
    if (!mySessionId || gameState === 'LOBBY') return;
    localStorage.setItem(SESSION_BACKUP_KEY, JSON.stringify({ id: mySessionId, ts: Date.now() }));
  } catch (_) {}
}
window.addEventListener('pagehide', backupSessionForRecovery);
window.addEventListener('beforeunload', backupSessionForRecovery);
/** 邀请链接里的房号，登录成功后自动加入 */
let pendingJoinCode = '';
let heartbeatTimer = null;
let reconnectAttempts = 0;
const MAX_RECONNECT = 8;
const HEARTBEAT_INTERVAL = 30000;

// 计时器状态
let timerData = { stepTime: 30, blackTotal: 300, whiteTotal: 300 };

// 回放状态
let replayMode = false;
let replayMoves = [];
let replayEffects = []; // 与 replayMoves 对齐的每手效果
let replayIndex = 0;
let replayPlaying = false;
let replayTimer = null;
let replaySpeed = 1000;
let savedBoard = null; // 回放前的棋盘快照
const RECONNECT_BASE_DELAY = 500;  // 指数退避基础延迟 500ms
const RECONNECT_MAX_DELAY = 30000; // 最大延迟 30s

// 排行榜
let selectedLbType = 'GOMOKU';
// 最近对局的套牌筛选（仅卡牌页签有意义；空字符串 = 全部）
let selectedRecordDeck = '';

// 当前游戏模块
function mod() { return window.GameModules[currentGameType]; }

// 非棋盘玩法（RPG / 卡牌）的战斗状态快照，由服务端 view 字段驱动。
// 服务端已按观察者视角裁剪过（卡牌只给自己手牌），前端不做权限判断。
let rpgView = null;

/** 收集所有 html 型玩法声明的面板 id（去重，保持声明顺序） */
function htmlPanelIds() {
  const ids = [];
  Object.keys(GameModules).forEach(function (k) {
    const mm = GameModules[k];
    if (!mm || !mm.html) return;
    const id = mm.panel || 'rpg-panel';
    if (ids.indexOf(id) < 0) ids.push(id);
  });
  return ids;
}

function initGameModule(type) {
  actionVersion = null;
  currentGameType = GameModules[type] ? type : 'GOMOKU';
  const m = mod();
  const isHtml = !!m.html;
  targetPlayers = m.defaultPlayers || 2;

  // 非棋盘玩法没有盘面：画布与 HTML 面板互斥显示。
  // 这里必须先切显示再设尺寸 —— 对 display:none 的元素取不到布局，
  // 尺寸会算成 0，之后所有绘制都会静默失效。
  canvas.style.display = isHtml ? 'none' : '';
  // 每种 html 型玩法有自己的面板 id（RPG → rpg-panel，卡牌 → card-panel …）。
  // 这里从 GameModules 反推全部面板 id，加新玩法时只需在模块里声明 panel，
  // 不用回头改这个函数。
  const panelId = isHtml ? (m.panel || 'rpg-panel') : null;
  htmlPanelIds().forEach(function (id) {
    const el = document.getElementById(id);
    if (!el) return;
    el.style.display = (isHtml && id === panelId) ? 'flex' : 'none';
  });

  if (isHtml) {
    board = null;
    rpgView = null;
    selectedFrom = null;
    cardAttackFrom = null;
    nimSelectedPile = null;
    cardPendingTarget = null;
  } else {
    // 支持长方形棋盘（象棋/四子棋）
    canvas.width = m.canvasWidth ? m.canvasWidth() : m.canvasSize();
    canvas.height = m.canvasHeight ? m.canvasHeight() : m.canvasSize();
    board = m.initialBoard();
    selectedFrom = null;
  }
  timerData = { stepTime: m.stepTime, blackTotal: m.totalTime, whiteTotal: m.totalTime };
}

// ============================================================
// 二进制协议引擎
// ============================================================
const PROTOCOL_MAGIC = 0xCAFE;
const PROTOCOL_VERSION = 0x01;
let useBinary = false; // 是否使用二进制协议（默认 JSON）

// 类型码映射（与服务端 GameProtocol 对齐）
const BIN_TYPE = {
  GET_ROOMS: 1, JOIN: 2, SPECTATE: 3, ADD_AI: 4, MOVE: 5,
  CHAT: 6, RESTART: 7, LEAVE: 8, RECONNECT: 9, SURRENDER: 10,
  PASS: 12, PING: 11,
  ROOM_LIST: 50, ROOM_INFO: 51, GAME_START: 52, GAME_SYNC: 53,
  GAME_MOVE: 54, GAME_OVER: 55, GAME_CHAT: 56, WAITING: 57,
  RESTART_REQUEST: 58, TIMER_SYNC: 59, ERROR: 60, PLAYER_STATUS: 61
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
    case BIN_TYPE.PASS:
    case BIN_TYPE.RESTART:
    case BIN_TYPE.SURRENDER:
      break; // no payload
    case BIN_TYPE.JOIN:
      parts.push(encodeStr(msg.playerName || ''));
      parts.push(encodeNullableStr(msg.roomId));
      parts.push(encodeNullableStr(msg.gameType));
      parts.push(encodeNullableStr(msg.deck));
      parts.push(encodeNullableStr(msg.deckCards));
      parts.push(new Uint8Array([msg.createRoom ? 1 : 0, msg.playerCount || 0]));
      break;
    case BIN_TYPE.SPECTATE:
      parts.push(encodeStr(msg.playerName || ''));
      parts.push(encodeStr(msg.roomId || ''));
      break;
    case BIN_TYPE.ADD_AI:
      parts.push(encodeNullableStr(msg.data));
      parts.push(encodeNullableStr(msg.deck));
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
      msg.gameType = readNullableStr();
      msg.message = readNullableStr();
      msg.data = readNullableStr();
      if (offset < data.byteLength) msg.sessionId = readNullableStr();
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
      msg.gameType = readNullableStr();
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
      if (offset < data.byteLength) msg.playerId = readNullableStr();
      break;
    case BIN_TYPE.TIMER_SYNC:
      msg.roomId = readNullableStr();
      msg.data = readNullableStr();
      break;
    case BIN_TYPE.PLAYER_STATUS:
      msg.roomId = readNullableStr();
      msg.playerId = readNullableStr();
      msg.message = readNullableStr();
      break;
    case BIN_TYPE.ERROR:
      msg.message = readNullableStr();
      // 可选附加数据（如落子被拒时服务端回传的当前手数）
      if (offset < data.byteLength) msg.data = readNullableStr();
      break;
  }
  return msg;
}

// ============================================================
// 棋盘画布（渲染分派到 GameModules）
// ============================================================
const canvas = document.getElementById('board-canvas');
const ctx = canvas.getContext('2d');

function drawBoard() {
  const m = mod();
  // 非棋盘玩法走 HTML 面板，没有画布可画。
  // 模块用 render: 'renderCardPanel' 声明自己的渲染函数，
  // 这里按名字查全局函数，避免每加一个玩法就多一条 if 分支。
  if (m.html) {
    const fn = m.render ? window[m.render] : null;
    if (typeof fn === 'function') fn();
    else renderRpgPanel();
    return;
  }
  m.draw(ctx, board, {
    lastMove,
    winLine,
    myStone,
    currentTurn,
    selected: selectedFrom,
    hint: gameState === 'PLAYING'
  });
}

// ============================================================
// 回合制 RPG 面板渲染
// ============================================================
function renderRpgPanel() {
  const roundEl = document.getElementById('rpg-round');
  const actionsEl = document.getElementById('rpg-actions');
  const logEl = document.getElementById('rpg-log');
  if (!roundEl || !actionsEl || !logEl) return;

  // 还没收到服务端状态（例如刚进房间）：给占位，避免面板空着像坏了
  if (!rpgView || !rpgView.fighters) {
    roundEl.className = 'rpg-round';
    roundEl.textContent = '等待战斗开始…';
    document.getElementById('rpg-fighter-1').className = 'rpg-fighter';
    document.getElementById('rpg-fighter-1').innerHTML = '<div class="rpg-name">等待中…</div>';
    document.getElementById('rpg-fighter-2').className = 'rpg-fighter';
    document.getElementById('rpg-fighter-2').innerHTML = '<div class="rpg-name">等待中…</div>';
    actionsEl.innerHTML = '';
    logEl.innerHTML = '';
    return;
  }

  const v = rpgView;
  const sides = mod().sides || [];

  v.fighters.forEach((f, i) => {
    const el = document.getElementById('rpg-fighter-' + (i + 1));
    if (!el) return;
    const hpPct = f.maxHp > 0 ? Math.max(0, Math.round(f.hp / f.maxHp * 100)) : 0;
    const mpPct = f.maxMp > 0 ? Math.max(0, Math.round(f.mp / f.maxMp * 100)) : 0;
    const isActive = !v.over && v.turn === f.side;
    const isMe = myStone === f.side;
    const iconCls = sides[f.side - 1] ? sides[f.side - 1].icon : 'red';

    // 状态徽标：防御/灼烧/药水，没有状态时这块留空占位（保持两卡等高）
    const chips = [];
    if (f.shield) chips.push('<span class="rpg-chip shield">防御中</span>');
    if (f.burn > 0) chips.push('<span class="rpg-chip burn">灼烧 ' + f.burn + '</span>');
    if (f.potions > 0) chips.push('<span class="rpg-chip potion">药水 ×' + f.potions + '</span>');

    el.className = 'rpg-fighter' + (isActive ? ' active' : '') + (f.alive ? '' : ' dead');
    el.innerHTML =
      '<div class="rpg-name"><span class="stone-icon ' + iconCls + '"></span>' +
        '<span style="overflow:hidden;text-overflow:ellipsis">' + escHtml(f.name) + '</span>' +
        (isMe ? '<span class="me">(你)</span>' : '') +
      '</div>' +
      '<div class="rpg-bar hp"><i style="width:' + hpPct + '%"></i></div>' +
      '<div class="rpg-bar-text"><span>HP ' + f.hp + '/' + f.maxHp + '</span><span>' + hpPct + '%</span></div>' +
      '<div class="rpg-bar mp"><i style="width:' + mpPct + '%"></i></div>' +
      '<div class="rpg-bar-text"><span>MP ' + f.mp + '/' + f.maxMp + '</span><span>' + mpPct + '%</span></div>' +
      '<div class="rpg-chips">' + chips.join('') + '</div>';
  });

  roundEl.className = 'rpg-round' + (v.suddenDeath ? ' sudden' : '');
  roundEl.textContent = '第 ' + v.round + ' 回合' + (v.suddenDeath ? ' · 决战阶段（伤害提升、治疗减半）' : '');

  // 只有轮到自己、且在比赛中才允许点技能；服务端仍会二次校验
  const canAct = gameState === 'PLAYING' && !v.over
    && currentTurn === myStone && myId !== 'SPECTATOR';
  actionsEl.innerHTML = (v.actions || []).map(function (a) {
    const disabled = !canAct || !a.available;
    const cost = a.mpCost > 0 ? '<span class="c">MP ' + a.mpCost + '</span>' : '';
    return '<button class="rpg-action" data-action="' + a.index + '"' + (disabled ? ' disabled' : '') + '>' +
      '<span class="t">' + escHtml(a.label) + '</span>' +
      '<span class="d">' + escHtml(a.desc) + '</span>' + cost +
      '</button>';
  }).join('');

  logEl.innerHTML = (v.log || []).map(function (line) {
    return '<div>' + escHtml(line) + '</div>';
  }).join('');
  logEl.scrollTop = logEl.scrollHeight;
}

/**
 * 攻击交互高亮。
 *
 * 两阶段：先点我方「本回合未攻击」的随从，再点目标（敌方随从或敌方英雄）。
 * 之所以不在渲染时直接绑定点击，是因为面板每次状态更新都会重建 DOM。
 */
function applyCardAttackHighlight(v, mine, foe, myTurn) {
  const hintEl = document.getElementById('card-attack-hint');
  const endBtn = document.getElementById('card-endturn');
  if (endBtn) endBtn.disabled = !myTurn;

  const myMinions = (mine && mine.board) || [];
  const foeMinions = (foe && foe.board) || [];
  // 可攻击判定以服务端 canAttack 为准（已含召唤失调/已攻击/是否存活）。
  // 前端不要自己重算 —— 规则一旦调整（如加冲锋），两边会不一致。
  const canAttackIdx = myMinions
    .map((m, i) => (m.canAttack === false ? -1 : i))
    .filter((i) => i >= 0);

  const myBoardEl = document.getElementById('card-board-1');
  const foeBoardEl = document.getElementById('card-board-2');
  if (!myBoardEl || !foeBoardEl) return;

  // 自己的随从：可攻击的加高亮，已选中的加选中态
  [...myBoardEl.children].forEach((el, i) => {
    el.classList.toggle('attack-ready', myTurn && canAttackIdx.includes(i) && cardAttackFrom === null);
    el.classList.toggle('targetable', cardAttackFrom === i);
  });

  // 正在为法术选目标时，合法目标也要高亮（火球→敌方、鼓舞→己方）
  if (cardPendingTarget !== null) {
    const card = (mine && (mine.hand || []).find(c => c.index === cardPendingTarget)) || null;
    const isBuff = card && card.label === '鼓舞';
    if (isBuff) {
      [...myBoardEl.children].forEach((el) => el.classList.add('targetable'));
    } else {
      [...foeBoardEl.children].forEach((el) => el.classList.add('targetable'));
      const foeHeroEl = document.getElementById('card-hero-2');
      if (foeHeroEl) foeHeroEl.classList.add('targetable');
    }
  }

  const choosingTarget = cardAttackFrom !== null || cardPendingTarget !== null;
  // 敌方随从作为目标
  [...foeBoardEl.children].forEach((el) => {
    el.classList.toggle('targetable', choosingTarget);
  });
  // 敌方英雄作为目标
  const foeHero = document.getElementById('card-hero-2');
  if (foeHero) foeHero.classList.toggle('targetable', choosingTarget);

  if (hintEl) {
    if (!myTurn) hintEl.textContent = '';
    else if (cardPendingTarget !== null) {
      const card = (mine && (mine.hand || []).find(c => c.index === cardPendingTarget)) || null;
      hintEl.textContent = (card && card.label === '鼓舞')
        ? '鼓舞：点一个己方随从强化'
        : '火球术：点敌方随从或英雄';
    }
    else if (choosingTarget) hintEl.textContent = '已选随从 → 点敌方随从或英雄发起攻击';
    else if (canAttackIdx.length > 0) hintEl.textContent = '可点击己方随从发动攻击';
    else hintEl.textContent = '';
  }
}

// 技能栏用事件委托：每次渲染都重建按钮，逐个绑监听会漏
document.getElementById('rpg-actions').addEventListener('click', function (e) {
  const btn = e.target.closest ? e.target.closest('.rpg-action') : null;
  if (!btn || btn.disabled) return;
  const idx = parseInt(btn.dataset.action, 10);
  if (!isNaN(idx)) sendAction(idx);
});

// ============================================================
// 卡牌对战面板渲染
// ============================================================
// 服务端下发的 view 已按视角裁剪：只包含「我方手牌内容 + 双方公开信息」。
// 前端据此渲染，不需要也不应该再做任何权限判断。
function renderCardPanel() {
  const turnEl = document.getElementById('card-turn');
  const handEl = document.getElementById('card-hand');
  const logEl = document.getElementById('card-log');
  if (!turnEl || !handEl || !logEl) return;

  if (!rpgView || !rpgView.players) {
    turnEl.textContent = '等待战斗开始…';
    turnEl.className = 'card-turn';
    document.getElementById('card-hero-1').innerHTML = '<div class="card-hero-name">等待中…</div>';
    document.getElementById('card-hero-2').innerHTML = '<div class="card-hero-name">等待中…</div>';
    document.getElementById('card-board-1').innerHTML = '';
    document.getElementById('card-board-2').innerHTML = '';
    handEl.innerHTML = '';
    logEl.innerHTML = '';
    return;
  }

  const v = rpgView;
  // 观战者 myStone = 0，找不到"自己"这一方。
  // 服务端对观战视角（viewerSide=0）本就不下发任何 hand 内容 —— 这是正确的隐藏信息；
  // 但前端之前因此把整个手牌区渲染成空，观战者看不到场上其实有多少张牌。
  const isSpectator = myId === 'SPECTATOR' || myStone === 0;
  const mine = isSpectator ? null : v.players.find(function (p) { return p.side === myStone; });
  const foe = isSpectator ? null : v.players.find(function (p) { return p.side !== myStone; });

  renderCardHero('card-hero-1', v.players[0], v.turn === 1);
  renderCardHero('card-hero-2', v.players[1], v.turn === 2);

  document.getElementById('card-board-1').innerHTML = renderMinions(v.players[0]);
  document.getElementById('card-board-2').innerHTML = renderMinions(v.players[1]);

  const myTurn = gameState === 'PLAYING' && !v.over && currentTurn === myStone && myId !== 'SPECTATOR';
  turnEl.className = 'card-turn' + (myTurn ? ' my-turn' : '');
  turnEl.textContent = v.over ? '本局结束'
    : (myTurn ? '你的回合 · 选择一张牌打出' : '等待对手出牌…') + '（牌库剩余 ' + v.deckLeft + '）';

  // 观战者：没有手牌内容可看，改为展示双方手牌张数与牌库剩余（都是公开信息）
  if (isSpectator) {
    const p1 = v.players[0] || {};
    const p2 = v.players[1] || {};
    handEl.innerHTML =
      '<div class="card-spectator">' +
        '<div class="cs-item"><span class="cs-name">' + escHtml(p1.name || '红方') + '</span>' +
          '<span class="cs-stat">手牌 ' + (p1.handCount || 0) + ' 张</span></div>' +
        '<div class="cs-item"><span class="cs-name">' + escHtml(p2.name || '蓝方') + '</span>' +
          '<span class="cs-stat">手牌 ' + (p2.handCount || 0) + ' 张</span></div>' +
        '<div class="cs-note">观战中 · 看不到双方手牌内容</div>' +
      '</div>';

    // 回合提示：告诉观战者现在轮到谁
    const turnName = (v.players.find(p => p.side === v.turn) || {}).name || ('第 ' + v.turn + ' 方');
    turnEl.className = 'card-turn';
    turnEl.textContent = v.over ? '本局结束' : ('观战中 · 轮到 ' + turnName);

    if (logEl) {
      logEl.innerHTML = (v.log || []).map(function (line) {
        return '<div>' + escHtml(line) + '</div>';
      }).join('');
      logEl.scrollTop = logEl.scrollHeight;
    }
    // 观战者不参与高亮/选目标
    const hintEl = document.getElementById('card-attack-hint');
    if (hintEl) hintEl.textContent = '';
    const endBtn = document.getElementById('card-endturn');
    if (endBtn) endBtn.disabled = true;
    return;
  }

  // 手牌：只有自己的 view 才带 hand 字段
  const hand = (mine && mine.hand) || [];
  handEl.innerHTML = hand.map(function (c) {
    const disabled = !myTurn || !c.playable;
    const stats = c.minion
      ? '<span class="c-stats"><span class="a">' + c.atk + '</span>/<span class="h">' + c.hp + '</span></span>'
      : '';
    const pending = (cardPendingTarget === c.index) ? ' pending-target' : '';
    const targetHint = c.needsTarget ? '<span class="c-needs">需指定目标</span>' : '';
    return '<div class="card-item' + (disabled ? ' disabled' : '') + pending + '" data-hand="' + c.index + '">' +
      '<div class="c-title"><span>' + escHtml(c.label) + '</span>' +
        '<span class="c-cost">' + c.cost + '</span></div>' +
      '<div class="c-desc">' + escHtml(c.desc) + '</div>' + stats + targetHint + '</div>';
  }).join('');

  logEl.innerHTML = (v.log || []).map(function (line) {
    return '<div>' + escHtml(line) + '</div>';
  }).join('');
  logEl.scrollTop = logEl.scrollHeight;

  // 随从/英雄的高亮必须在 innerHTML 重建之后才生效
  applyCardAttackHighlight(v, mine, foe, myTurn);
}

function renderCardHero(elId, p, isActive) {
  const el = document.getElementById(elId);
  if (!el || !p) return;
  const isMe = p.side === myStone;
  const icon = (mod().sides[p.side - 1] || {}).icon || 'red';
  const dead = p.hp <= 0;
  el.className = 'card-hero' + (isActive && !dead ? ' active' : '') + (dead ? ' dead' : '');
  el.innerHTML =
    '<div class="card-hero-name"><span class="stone-icon ' + icon + '"></span>' +
      '<span style="overflow:hidden;text-overflow:ellipsis">' + escHtml(p.name) + '</span>' +
      (isMe ? '<span class="me">(你)</span>' : '') + '</div>' +
    '<div class="card-hero-stats">' +
      '<span class="card-hp">♥ ' + p.hp + '/' + p.maxHp + '</span>' +
      '<span class="card-mana">◆ ' + p.mana + '/' + p.maxMana + '</span>' +
      '<span>手牌 ' + p.handCount + '</span>' +
    '</div>';
}

function renderMinions(p) {
  if (!p || !p.board || p.board.length === 0) return '';
  return p.board.map(function (m) {
    // 关键词徽标：嘲讽（盾）与召唤失调（沙漏）
    // 召唤失调在服务端由 canAttack 统一判断 —— 非冲锋随从召唤当回合不能攻击，
    // 这里显式标出来，否则玩家会以为「点了没反应」是 bug。
    let badges = '';
    if (m.divineShield) badges += '<span class="kw shield" title="圣盾：抵挡下一次伤害">圣</span>';
    if (m.taunt) badges += '<span class="kw taunt" title="嘲讽：对手必须先攻击它">盾</span>';
    if (m.charge) badges += '<span class="kw charge" title="冲锋：召唤当回合即可攻击">冲</span>';
    if (m.battlecry) badges += '<span class="kw battlecry" title="战吼：召唤时立即触发效果">吼</span>';
    if (m.deathrattle) badges += '<span class="kw deathrattle" title="亡语：死亡时触发效果">亡</span>';
    if (m.lifesteal) badges += '<span class="kw lifesteal" title="吸血：造成伤害时恢复等量生命">吸</span>';
    if (m.combo) {
      badges += '<span class="kw combo' + (m.comboActive ? ' active' : '')
        + '" title="连击：本回合已出过别的牌则 +2 攻击' + (m.comboActive ? '（已激活）' : '') + '">连</span>';
    }
    if (m.canAttack === false && !m.attacked) {
      badges += '<span class="kw sick" title="召唤失调：本回合刚上场，下回合才能攻击">哑</span>';
    }
    const cls = 'card-minion' + (m.taunt ? ' has-taunt' : '') + (m.divineShield ? ' has-shield' : '');
    return '<div class="' + cls + '" title="' + escHtml(m.label) + '">' +
      '<span class="n">' + escHtml(m.label) + '</span>' +
      '<span class="s"><span class="a">' + m.atk + '</span>/<span class="h">' + m.hp + '</span></span>' +
      badges +
    '</div>';
  }).join('');
}

// 己方随从点击 -> 选中为攻击者（再点一次取消）
document.getElementById('card-board-1').addEventListener('click', function (e) {
  const el = e.target.closest ? e.target.closest('.card-minion') : null;
  if (!el) return;
  if (gameState !== 'PLAYING' || currentTurn !== myStone || myId === 'SPECTATOR') return;
  const idx = [...el.parentElement.children].indexOf(el);
  if (idx < 0) return;

  // 先处理"正在为法术选目标"的情况（鼓舞指定己方随从）
  if (cardPendingTarget !== null) {
    const me = rpgView.players.find(p => p.side === myStone);
    const card = (me.hand || []).find(c => c.index === cardPendingTarget);
    if (card && card.label === '鼓舞') {
      sendActionWithTarget(cardPendingTarget, myStone, idx);
      cardPendingTarget = null;
      return;
    }
    // 火球不能指向己方，忽略这次点击
    return;
  }

  cardAttackFrom = (cardAttackFrom === idx) ? null : idx;
  drawBoard();
});

// 敌方目标点击：敌方随从 -> 打随从；敌方英雄 -> 打英雄
document.getElementById('card-board-2').addEventListener('click', function (e) {
  const el = e.target.closest ? e.target.closest('.card-minion') : null;
  if (!el) return;
  const idx = [...el.parentElement.children].indexOf(el);
  if (idx < 0) return;

  // 正在为火球选目标（敌方随从）
  if (cardPendingTarget !== null) {
    sendActionWithTarget(cardPendingTarget, 3 - myStone, idx);
    cardPendingTarget = null;
    return;
  }
  if (cardAttackFrom === null) return;
  sendAttack(cardAttackFrom, idx);
});

document.getElementById('card-hero-2').addEventListener('click', function () {
  // 正在为火球选目标（敌方英雄）
  if (cardPendingTarget !== null) {
    sendActionWithTarget(cardPendingTarget, 3 - myStone, -1);
    cardPendingTarget = null;
    return;
  }
  if (cardAttackFrom === null) return;
  sendAttack(cardAttackFrom, -1); // -1 = 打英雄
});

/** 发送攻击指令：action 用 -2 标记，row=随从下标，col=目标下标 */
function sendAttack(minionIndex, targetIndex) {
  if (gameState !== 'PLAYING' || currentTurn !== myStone) return;
  cardAttackFrom = null;
  send({ type: 'MOVE', action: -2, row: minionIndex, col: targetIndex });
}

/** 结束回合：交出攻击并换手 */
function sendEndTurn() {
  if (gameState !== 'PLAYING' || currentTurn !== myStone || myId === 'SPECTATOR') return;
  cardAttackFrom = null;
  send({ type: 'END_TURN' });
}

// 手牌点击 -> 按手牌下标出牌（与 RPG 技能栏同样的 action 通道）
document.getElementById('card-hand').addEventListener('click', function (e) {
  const item = e.target.closest ? e.target.closest('.card-item') : null;
  if (!item || item.classList.contains('disabled')) return;
  const idx = parseInt(item.dataset.hand, 10);
  if (isNaN(idx)) return;

  cardAttackFrom = null;
  const me = rpgView && rpgView.players.find(p => p.side === myStone);
  const card = me && (me.hand || []).find(c => c.index === idx);

  // 需要选目标的牌（火球/鼓舞）：先进入"点目标"模式，不立即发送。
  // 直接发出去会因为缺目标被服务端拒绝，玩家只会看到"没反应"。
  if (card && card.needsTarget) {
    cardPendingTarget = (cardPendingTarget === idx) ? null : idx;
    drawBoard();
    return;
  }

  cardPendingTarget = null;
  sendAction(idx);
});

/** 发送带目标的出牌指令 */
function sendActionWithTarget(actionIndex, targetSide, targetIndex) {
  if (gameState !== 'PLAYING' || currentTurn !== myStone || myId === 'SPECTATOR') return;
  send({ type: 'MOVE', action: actionIndex, targetSide: targetSide, targetIndex: targetIndex, moveSeq });
}

// ============================================================
// 取石子博弈面板
// ============================================================
function renderNimPanel() {
  const pilesEl = document.getElementById('nim-piles');
  const turnEl = document.getElementById('nim-turn');
  const takeEl = document.getElementById('nim-take-btns');
  const logEl = document.getElementById('nim-log');
  if (!pilesEl || !turnEl || !takeEl || !logEl) return;

  if (!rpgView || !rpgView.piles) {
    pilesEl.innerHTML = '';
    turnEl.textContent = '等待开始…';
    takeEl.innerHTML = '';
    logEl.innerHTML = '';
    return;
  }

  const v = rpgView;
  const myTurn = gameState === 'PLAYING' && !v.over && currentTurn === myStone && myId !== 'SPECTATOR';
  const unoPending = v.unoPendingSide === myStone;
  const drawnPending = v.drawnPendingSide === myStone;
  // 三堆石子
  pilesEl.innerHTML = v.piles.map(function (n, i) {
    const selectable = myTurn && n > 0;
    const cls = 'nim-pile'
      + (selectable ? ' selectable' : '')
      + (nimSelectedPile === i ? ' selected' : '')
      + (n === 0 ? ' empty' : '');
    // 用小圆点表示剩余石子，超过 10 颗只显示数量
    const dots = n <= 10 ? '●'.repeat(n) : '●'.repeat(10) + '…';
    return '<div class="' + cls + '" data-pile="' + i + '">' +
      '<div class="nim-pile-label">第 ' + (i + 1) + ' 堆</div>' +
      '<div class="nim-count">' + n + '</div>' +
      '<div class="nim-stones">' + dots + '</div>' +
    '</div>';
  }).join('');

  // 观战者 myStone=0，myTurn 永远 false。原来会显示「等待对手取子…」，
  // 从观战者角度这句话没有意义（他没有「对手」）。改为点明轮到谁。
  const isSpectator = myId === 'SPECTATOR' || myStone === 0;
  turnEl.className = 'nim-turn' + (myTurn ? ' my-turn' : '');
  if (v.over) {
    turnEl.textContent = '本局结束';
  } else if (isSpectator) {
    const who = v.players && v.players.find(p => p.side === v.turn);
    turnEl.textContent = '观战中 · 轮到 ' + (who ? who.name : ('第 ' + v.turn + ' 方'))
      + '（剩余 ' + v.total + ' 颗）';
  } else {
    turnEl.textContent = myTurn
      ? '你的回合：选一堆，再选拿走的数量'
      : '等待对手取子…（剩余 ' + v.total + ' 颗）';
  }

  // 拿走数量按钮：选中某堆后，1..该堆数量
  if (isSpectator) {
    // 观战者不能取子，不要显示操作引导
    takeEl.innerHTML = '<span style="color:#555;font-size:0.72rem">观战中不可操作</span>';
  } else if (!myTurn || nimSelectedPile === null || nimSelectedPile < 0 || v.piles[nimSelectedPile] <= 0) {
    takeEl.innerHTML = '<span style="color:#555;font-size:0.72rem">先点一堆石子</span>';
  } else {
    const max = v.piles[nimSelectedPile];
    let html = '';
    for (let k = 1; k <= max; k++) {
      html += '<button class="nim-take" data-take="' + k + '">' + k + '</button>';
    }
    takeEl.innerHTML = html;
  }

  logEl.innerHTML = (v.log || []).map(function (line) {
    return '<div>' + escHtml(line) + '</div>';
  }).join('');
  logEl.scrollTop = logEl.scrollHeight;
}

// 点堆 -> 选中；点数量 -> 出招
document.getElementById('nim-piles').addEventListener('click', function (e) {
  const el = e.target.closest ? e.target.closest('.nim-pile') : null;
  if (!el || !el.classList.contains('selectable')) return;
  const idx = parseInt(el.dataset.pile, 10);
  if (isNaN(idx)) return;
  nimSelectedPile = (nimSelectedPile === idx) ? null : idx;
  drawBoard();
});

document.getElementById('nim-take-btns').addEventListener('click', function (e) {
  const btn = e.target.closest ? e.target.closest('.nim-take') : null;
  if (!btn || btn.disabled) return;
  const take = parseInt(btn.dataset.take, 10);
  if (isNaN(take) || nimSelectedPile === null) return;
  // 复用 MOVE 通道：row = 堆号，col = 拿走数量
  sendMove(nimSelectedPile, take);
  nimSelectedPile = null;
});

// ============================================================
// 答题对战面板
// ============================================================
function renderQuizPanel() {
  const progressEl = document.getElementById('quiz-progress');
  const scoreEl = document.getElementById('quiz-score');
  const qEl = document.getElementById('quiz-question');
  const optsEl = document.getElementById('quiz-options');
  const statusEl = document.getElementById('quiz-status');
  const logEl = document.getElementById('quiz-log');
  if (!progressEl || !qEl || !optsEl) return;

  if (!rpgView || !rpgView.players) {
    progressEl.textContent = '等待开始…';
    scoreEl.textContent = '';
    qEl.textContent = '等待出题…';
    optsEl.innerHTML = '';
    if (statusEl) statusEl.textContent = '';
    if (logEl) logEl.innerHTML = '';
    return;
  }

  const v = rpgView;
  const mine = v.players.find(function (p) { return p.side === myStone; });
  const foe = v.players.find(function (p) { return p.side !== myStone; });

  // 题号 + 主题：主题让玩家知道「这局在考什么」，也避免同主题连出时显得重复
  const cat = (v.question && v.question.category) ? ' · ' + v.question.category : '';
  progressEl.textContent = '第 ' + v.round + ' / ' + v.totalRounds + ' 题' + cat;
  scoreEl.textContent = (v.players[0] ? v.players[0].score : 0) + ' 分'
    + (v.players[0] && v.players[0].streak ? ' · 连击 ' + v.players[0].streak : '')
    + '  :  ' + (v.players[1] ? v.players[1].score : 0) + ' 分'
    + (v.players[1] && v.players[1].streak ? ' · 连击 ' + v.players[1].streak : '');

  if (v.question) {
    qEl.textContent = v.question.text;
  } else {
    qEl.textContent = v.over ? '全部题目已答完' : '等待出题…';
  }

  const revealed = !!v.revealed;
  const myAnswered = !!(mine && mine.answered);
  const canAnswer = gameState === 'PLAYING' && !v.over && !myAnswered
    && myStone > 0 && myId !== 'SPECTATOR';

  const opts = (v.question && v.question.options) || [];
  const letters = ['A', 'B', 'C', 'D'];
  optsEl.innerHTML = opts.map(function (text, i) {
    let cls = 'quiz-opt';
    // 揭晓后：正确答案标绿；自己选错的标红；其余淡化
    if (revealed) {
      if (v.correctIndex === i) cls += ' correct';
      else if (mine && mine.choice === i) cls += ' wrong';
      else cls += ' dim';
    } else if (mine && mine.choice === i) {
      cls += ' mine';
    }
    const disabled = (!canAnswer) ? ' disabled' : '';
    return '<button class="' + cls + '" data-opt="' + i + '"' + disabled + '>' +
      '<span class="tag">' + letters[i] + '</span><span>' + escHtml(text) + '</span></button>';
  }).join('');

  // 当前题提交状态与上一题结果分开，下一题可立即作答。
  const isSpectator = myId === 'SPECTATOR' || myStone === 0;
  let status = '';
  if (isSpectator) {
    if (v.over) status = '本局结束';
    else if (revealed) {
      status = '已揭晓' + (v.correctIndex != null
        ? '（正确答案：' + letters[v.correctIndex] + '）' : '');
    } else {
      const pendingNames = v.players.filter(p => !p.answered).map(p => p.name);
      const answeredNames = v.players.filter(p => p.answered).map(p => p.name);
      status = answeredNames.length > 0
        ? ('观战中 · ' + answeredNames.join('、') + ' 已作答，等待 ' + pendingNames.join('、'))
        : ('观战中 · 双方同时作答');
    }
  } else if (v.over) status = '本局结束';
  else if (revealed) status = '已揭晓' + (v.correctIndex != null
    ? '（正确答案：' + letters[v.correctIndex] + '）' : '');
  else if (myAnswered) status = '你已作答，等待对手…';
  else if (canAnswer) status = foe && foe.answered ? '对手已作答，等你选择' : '请选择你的答案';
  else status = '等待中…';
  if (!v.over && v.lastCorrect != null) status += ' · 上一题答案：' + letters[v.lastCorrect];
  if (statusEl) {
    statusEl.textContent = status;
    statusEl.className = 'quiz-status' + (canAnswer ? ' my-turn' : '');
  }

  if (logEl) {
    logEl.innerHTML = (v.log || []).map(function (line) {
      return '<div>' + escHtml(line) + '</div>';
    }).join('');
    logEl.scrollTop = logEl.scrollHeight;
  }
}

// 骰子竞速：所有玩家同时选策略，服务端揭晓后更新积分。
function renderDiceRacePanel() {
  const progress = document.getElementById('dice-progress');
  const score = document.getElementById('dice-score');
  const status = document.getElementById('dice-status');
  const choicesEl = document.getElementById('dice-choices');
  const playersEl = document.getElementById('dice-players');
  const logEl = document.getElementById('dice-log');
  if (!progress || !choicesEl || !rpgView || !rpgView.players) return;
  const v = rpgView;
  progress.textContent = '第 ' + v.round + ' / ' + v.totalRounds + ' 轮';
  score.textContent = (v.players || []).map(p => p.name + ' ' + p.score).join('  ·  ');
  const me = v.players.find(p => p.side === myStone);
  const waiting = !v.over && myStone > 0 && me && !me.answered && myId !== 'SPECTATOR';
  status.textContent = v.over ? '本局结束' : waiting ? '选择你的策略，所有人提交后一起揭晓' : '等待其他玩家选择…';
  const labels = ['稳健', '冒险', '追击'];
  const desc = ['1d6，稳定拿分', '2d6，掷出 1 会爆掉', '落后时 +3，领先时 -1'];
  choicesEl.innerHTML = labels.map((label, i) => {
    let cls = 'dice-choice';
    if (v.revealed) cls += ' revealed';
    if (me && me.choice === i) cls += ' mine';
    return '<button class="' + cls + '" data-choice="' + i + '"' + (waiting ? '' : ' disabled') + '>'
      + '<strong>' + label + '</strong><small>' + desc[i] + '</small></button>';
  }).join('');
  playersEl.innerHTML = v.players.map(p => '<div class="dice-player-row"><b>' + escHtml(p.name) + '</b><span>'
    + p.score + ' 分' + (p.lastRoundScore != null ? '（上轮 +' + p.lastRoundScore + '，掷出 ' + p.lastRoll + '）' : '')
    + (p.answered ? ' · 已选' : '') + '</span></div>').join('');
  logEl.innerHTML = (v.log || []).map(line => '<div>' + escHtml(line) + '</div>').join('');
  logEl.scrollTop = logEl.scrollHeight;
}

function renderGemAuctionPanel() {
  const progress = document.getElementById('gem-progress'), score = document.getElementById('gem-score');
  const status = document.getElementById('gem-status'), bidsEl = document.getElementById('gem-bids');
  const playersEl = document.getElementById('gem-players'), logEl = document.getElementById('gem-log');
  if (!progress || !bidsEl || !rpgView || !rpgView.players) return;
  const v = rpgView, me = v.players.find(p => p.side === myStone);
  progress.textContent = '第 ' + v.round + ' / ' + v.totalRounds + ' 轮';
  score.textContent = '本轮宝石 ' + v.gemValue + ' 分';
  const waiting = !v.over && myStone > 0 && me && !me.answered && myId !== 'SPECTATOR';
  status.textContent = v.over ? '本局结束' : waiting ? '选择出价，所有人提交后揭晓' : '等待其他玩家暗投…';
  bidsEl.innerHTML = Array.from({length: 11}, (_, i) => '<button class="dice-choice gem-bid" data-bid="' + i + '"' + (waiting && i <= (me?.coins || 0) ? '' : ' disabled') + '><strong>' + i + '</strong><small>金币</small></button>').join('');
  playersEl.innerHTML = v.players.map(p => '<div class="dice-player-row"><b>' + escHtml(p.name) + '</b><span>' + p.gems + ' 宝石分 · ' + p.coins + ' 金币' + (p.answered ? ' · 已出价' : '') + ((v.revealed && p.bid != null) ? ' · 出价 ' + p.bid : '') + '</span></div>').join('');
  logEl.innerHTML = (v.log || []).map(line => '<div>' + escHtml(line) + '</div>').join(''); logEl.scrollTop = logEl.scrollHeight;
}

document.getElementById('gem-bids').addEventListener('click', function (e) {
  const btn = e.target.closest ? e.target.closest('.gem-bid') : null;
  if (!btn || btn.disabled) return;
  const bid = parseInt(btn.dataset.bid, 10); if (!isNaN(bid)) sendAction(bid);
});

document.getElementById('dice-choices').addEventListener('click', function (e) {
  const btn = e.target.closest ? e.target.closest('.dice-choice') : null;
  if (!btn || btn.disabled) return;
  const choice = parseInt(btn.dataset.choice, 10);
  if (!isNaN(choice)) sendAction(choice);
});

// 选项点击 -> 按选项下标提交（action 通道）
document.getElementById('quiz-options').addEventListener('click', function (e) {
  const btn = e.target.closest ? e.target.closest('.quiz-opt') : null;
  if (!btn || btn.disabled) return;
  const idx = parseInt(btn.dataset.opt, 10);
  if (!isNaN(idx)) sendAction(idx);
});

// 从消息 data 里提取非棋盘玩法的状态快照
function applyViewData(dataStr) {
  try {
    const data = JSON.parse(dataStr);
    if (data && data.view) rpgView = data.view;
    if (data && data.timers) timerData = data.timers;
    if (data && data.actionVersion) actionVersion = data.actionVersion;
    return data;
  } catch (e) {
    return null;
  }
}

// 落子预览
canvas.addEventListener('mousemove', function(e) {
  if (gameState !== 'PLAYING' || currentTurn !== myStone) return;
  const rect = canvas.getBoundingClientRect();
  const scaleX = canvas.width / rect.width;
  const scaleY = canvas.height / rect.height;
  const mx = (e.clientX - rect.left) * scaleX;
  const my = (e.clientY - rect.top) * scaleY;
  const hit = mod().hitTest(mx, my, board);
  drawBoard();
  // 经 normalizeMove 换算成真实落点（四子棋会把任意格吸附到该列重力落点）
  const target = hit ? normalizeMove(hit) : null;
  if (target && mod().isLegal(board, target.row, target.col, myStone)) {
    mod().drawGhost(ctx, target.row, target.col, myStone);
  }
});

canvas.addEventListener('mouseleave', drawBoard);

// Touch support for mobile — 区分点击和滑动
let touchStartX = 0, touchStartY = 0, touchMoved = false;

canvas.addEventListener('touchstart', function(e) {
  if (gameState !== 'PLAYING' || currentTurn !== myStone) return;
  const touch = e.touches[0];
  touchStartX = touch.clientX;
  touchStartY = touch.clientY;
  touchMoved = false;
}, { passive: true });

canvas.addEventListener('touchmove', function(e) {
  touchMoved = true;
}, { passive: true });

canvas.addEventListener('touchend', function(e) {
  if (gameState !== 'PLAYING' || currentTurn !== myStone) return;
  if (touchMoved) return; // 滑动不落子
  const touch = e.changedTouches[0];
  tryMoveFromPixel(touch.clientX, touch.clientY);
});

canvas.addEventListener('click', function(e) {
  if (gameState !== 'PLAYING' || currentTurn !== myStone) return;
  tryMoveFromPixel(e.clientX, e.clientY);
});

function tryMoveFromPixel(clientX, clientY) {
  // 非棋盘玩法不通过画布交互（面板按钮直接发行动）
  if (mod().html) return;
  const rect = canvas.getBoundingClientRect();
  const scaleX = canvas.width / rect.width;
  const scaleY = canvas.height / rect.height;
  const mx = (clientX - rect.left) * scaleX;
  const my = (clientY - rect.top) * scaleY;
  const hit = mod().hitTest(mx, my, board);
  if (!hit) return;

  // 象棋：两阶段交互（先选己方棋子，再点目标点）
  if (currentGameType === 'XIANGQI') {
    const v = board[hit.row][hit.col];
    const isOwn = v !== 0 && Math.floor(v / 10) === myStone;
    if (isOwn) {
      selectedFrom = (selectedFrom && selectedFrom.row === hit.row && selectedFrom.col === hit.col)
        ? null : hit; // 点已选中的子 = 取消选择
      drawBoard();
      return;
    }
    if (!selectedFrom) return; // 未选子，点空处/对方棋子无效
    sendMove(hit.row, hit.col, selectedFrom.row, selectedFrom.col);
    selectedFrom = null;
    drawBoard();
    return;
  }

  // 统一换算真实落点：四子棋点该列任意格都吸附到重力落点
  const target = normalizeMove(hit);
  if (!target) return;
  if (!mod().isLegal(board, target.row, target.col, myStone)) return;
  sendMove(target.row, target.col);
}

// 把用户点击的格子换算成最终要提交的落点。
// 多数游戏「点哪就是哪」；四子棋受重力约束，点该列任意格都落到底部空位。
function normalizeMove(hit) {
  const m = mod();
  return m.normalizeMove ? m.normalizeMove(board, hit.row, hit.col) : hit;
}

// ============================================================
// WebSocket 通信
// ============================================================
// 自动检测 WebSocket 地址：
//   生产环境(nginx): wss://domain/ws 或 ws://domain/ws
//   本地开发:        ws://localhost:9001（直接连 Netty）
function getWsUrl() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const wsPort = document.getElementById('ws-url').dataset.port || '9001';
  // HTTPS 页面必须通过反向代理的 /ws 走 WSS，避免混合内容被浏览器拦截。
  // 本地 localhost 的 HTTPS 调试也走 Caddy/Nginx，而不是直连明文 9001。
  if (location.protocol === 'https:') {
    return 'wss://' + location.host + '/ws';
  }
  // 页面端口 === WS 端口 → 直接连 Netty（本地或同端口部署）
  if (location.port === wsPort) {
    return proto + '//' + location.host;
  }
  // 本地 HTTP 开发 / Docker 直连（默认 HTTP 9000）：直接连 Netty 9001
  if (location.hostname === 'localhost' || location.hostname === '127.0.0.1'
      || location.port === '9000') {
    return proto + '//' + location.hostname + ':' + wsPort;
  }
  // HTTP 80 或其它端口：视为反向代理，同源 /ws 转发到 Netty
  return proto + '//' + location.host + '/ws';
}

// ============ 账号体系 ============
const authHeaders = () => guestToken ? { Authorization: 'Bearer ' + guestToken } : {};

function setAccountHint(text, isError) {
  const hint = document.getElementById('account-hint');
  if (hint) { hint.textContent = text || ''; hint.style.color = isError ? 'var(--red)' : 'var(--text-dim)'; }
}

function setLoginStatus(text, isError) {
  const el = document.getElementById('login-sub-status');
  if (el) { el.textContent = text || ''; el.style.color = isError ? 'var(--red)' : 'var(--green)'; }
}

function switchAuthTab(mode) {
  authMode = mode;
  ['guest', 'login', 'register'].forEach(m => {
    const tab = document.getElementById('tab-' + m);
    if (tab) tab.classList.toggle('active', m === mode);
  });
  const fields = document.getElementById('account-fields');
  const button = document.getElementById('join-button');
  const pw = document.getElementById('account-password');
  if (mode === 'guest') {
    fields.hidden = true;
    button.textContent = '进入大厅';
  } else {
    fields.hidden = false;
    button.textContent = mode === 'register' ? '注册并进入' : '登录并进入';
    if (pw) pw.autocomplete = mode === 'register' ? 'new-password' : 'current-password';
  }
  setAccountHint(mode === 'register'
    ? '用当前游客身份注册，历史战绩与等级会完整保留。'
    : (mode === 'login' ? '登录已有账号，换设备也能找回战绩。' : ''));
  setLoginStatus('');
}

/** 页面加载时恢复登录态：有账号 token 就把它显示出来 */
async function restoreAccount() {
  if (!guestToken) return;
  try {
    const response = await fetch('/api/account/me', { headers: authHeaders() });
    if (!response.ok) return;
    accountInfo = await response.json();
    document.getElementById('player-name').value = accountInfo.nickname || accountInfo.username;
    document.getElementById('account-state').textContent = '已登录：' + (accountInfo.nickname || accountInfo.username);
    document.getElementById('account-logout').hidden = false;
    document.getElementById('player-name').readOnly = true;
    switchAuthTab('guest');
    setAccountHint('账号已登录，直接进入即可延续战绩。');
  } catch (_) { /* 网络异常时仍可按游客进入 */ }
}

/** 注册或登录，成功后把返回的 token 作为统一身份凭证 */
async function authenticateAccount() {
  const username = (document.getElementById('account-username').value || '').trim();
  const password = document.getElementById('account-password').value || '';
  if (!username || !password) {
    setAccountHint('请输入用户名和密码', true);
    return false;
  }
  if (authMode === 'register' && (password.length < 8 || !/[^A-Za-z0-9]/.test(password))) {
    setAccountHint('建议密码 8 位以上并包含符号/数字，提高账号安全', true);
  }
  const endpoint = authMode === 'register' ? '/api/account/register' : '/api/account/login';
  const body = { username, password, nickname: document.getElementById('player-name').value.trim() };
  try {
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
      body: JSON.stringify(body)
    });
    const data = await response.json();
    if (!response.ok) {
      setAccountHint(data.message || '操作失败，请重试', true);
      return false;
    }
    guestToken = data.token;
    savePreference('gomoku_guest', guestToken);
    accountInfo = data;
    document.getElementById('player-name').value = data.nickname || data.username;
    document.getElementById('account-state').textContent = '已登录：' + (data.nickname || data.username);
    document.getElementById('account-logout').hidden = false;
    document.getElementById('player-name').readOnly = true;
    const successMessage = authMode === 'register' ? '注册成功，战绩已保留' : '登录成功';
    // 凭证已落 localStorage，切回游客态：否则按钮仍是「注册并进入」，再点会重复注册报 409。
    switchAuthTab('guest');
    setLoginStatus(successMessage);
    // 身份变了（游客升级为账号或换号登录），好友列表按新身份重取
    loadFriends();
    return true;
  } catch (_) {
    setAccountHint('连接失败，请稍后重试', true);
    return false;
  }
}

async function accountLogout() {
  guestToken = null;
  accountInfo = null;
  try { localStorage.removeItem('gomoku_guest'); } catch (_) {}
  document.getElementById('account-state').textContent = '当前：游客身份（战绩仅存在本浏览器）';
  document.getElementById('account-logout').hidden = true;
  document.getElementById('player-name').readOnly = false;
  switchAuthTab('guest');
  setAccountHint('已退出账号，接下来为游客身份。');
  // 退出账号回到游客身份，好友面板按游客身份重取
  loadFriends();
}

async function joinGame() {
  if (authMode !== 'guest') {
    if (!await authenticateAccount()) return;
  }
  const url = document.getElementById('ws-url').value.trim() || getWsUrl();
  myName = document.getElementById('player-name').value.trim() || '匿名玩家';

  savePreference('gomoku_name', myName);
  try {
    const response = await fetch('/api/guest', { method: 'POST', headers: guestToken ? { Authorization: 'Bearer ' + guestToken } : {} });
    if (!response.ok) throw new Error('身份服务不可用');
    const identity = await response.json();
    if (identity.token) { guestToken = identity.token; savePreference('gomoku_guest', guestToken); }
    connect(url);
  } catch (_) { setStatusMsg('游客身份创建失败，请稍后重试', 'error'); document.getElementById('login-sub-status').textContent = '游客身份创建失败，请稍后重试'; }
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
    setStatus(true, '连接中...');

    // Start heartbeat
    if (heartbeatTimer) clearInterval(heartbeatTimer);
    heartbeatTimer = setInterval(() => {
      send({ type: 'PING' });
    }, HEARTBEAT_INTERVAL);

    if (mySessionId) {
      send({ type: 'RECONNECT', sessionId: mySessionId });
    }
    // 大厅身份绑定：告诉服务端「我在大厅」——好友在线状态与实时推送（FRIEND_EVENT）都依赖它。
    // 带 guestToken 的消息走 JSON 路径；旧客户端不发这条也不受影响（服务端轮询兜底）。
    if (guestToken) send({ type: 'HALL_BIND', playerName: myName, guestToken });

    // 先发探测消息，等服务端确认后再进入（防止 IP 限流等场景）
    send({ type: 'GET_ROOMS' });
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
    // 对局中（mySessionId）与「只在大厅」（有 guestToken）都要重连：
    // 此前只有对局中会重连，纯大厅用户一掉线就被踢回登录页，等再点进来好友都下线了。
    if ((mySessionId || guestToken) && reconnectAttempts < MAX_RECONNECT) {
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
        const url = document.getElementById('ws-url').value.trim() || getWsUrl();
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
  stopMatchWait(true);
  // 回到大厅时立刻刷新战绩（刚打完的那局应该马上出现在列表里）
  loadMyStats();
  loadDailyProgress();
  loadPlayerProgress();
  loadOnlineStats();
  loadRecentGames();
  loadLeaderboard();
  loadFriends();
  loadMatchOverview();
  loadAnnouncements();
  loadEvents();
}

function showGame() {
  document.getElementById('login-screen').style.display = 'none';
  document.getElementById('lobby-screen').style.display = 'none';
  document.getElementById('game-screen').style.display = 'grid';
  document.getElementById('chat-messages').innerHTML = '<div class="chat-msg system">欢迎来到' + mod().name + '对战！</div>';
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

// ============ 运营公告（大厅横幅） ============

const ANNOUNCE_DISMISS_KEY = 'gomokuDismissedAnnouncements';
let lastActiveAnnouncements = [];

function dismissedAnnouncementIds() {
  try {
    const raw = JSON.parse(localStorage.getItem(ANNOUNCE_DISMISS_KEY) || '[]');
    return Array.isArray(raw) ? raw : [];
  } catch (_) {
    return [];
  }
}

function announceLevelMeta(level) {
  return ({
    important: { label: '重要', cls: 'important' },
    maintenance: { label: '维护', cls: 'maintenance' }
  })[level] || { label: '公告', cls: 'info' };
}

function loadAnnouncements() {
  fetch('/api/announcements')
    .then(r => r.ok ? r.json() : null)
    .then(body => { if (body) renderAnnouncements(body.announcements || []); })
    .catch(() => { /* 公告加载失败不阻塞大厅 */ });
}

function renderAnnouncements(list) {
  lastActiveAnnouncements = list;
  const board = document.getElementById('announce-board');
  if (!board) return;
  const dismissed = dismissedAnnouncementIds();
  const visible = list.filter(a => dismissed.indexOf(a.id) === -1);
  if (!visible.length) {
    board.style.display = 'none';
    board.innerHTML = '';
    return;
  }
  board.style.display = 'flex';
  board.innerHTML = visible.map(a => {
    const meta = announceLevelMeta(a.level);
    return '<div class="announce-item announce-' + meta.cls + '" data-announce-id="' + escHtml(a.id) + '">'
      + '<span class="announce-level">' + meta.label + '</span>'
      + '<div class="announce-content">'
      + '<div class="announce-title">' + escHtml(a.title) + '</div>'
      + '<div class="announce-body">' + escHtml(a.body) + '</div>'
      + '</div>'
      + '<button class="announce-close" title="关闭公告" aria-label="关闭公告"'
      + ' onclick="dismissAnnouncement(\'' + escHtml(a.id) + '\')">×</button>'
      + '</div>';
  }).join('');
}

/** 关闭公告：按 id 记住，刷新页面也不再骚扰；之后管理员重新上线的同 id 公告仍保持关闭 */
function dismissAnnouncement(id) {
  const ids = dismissedAnnouncementIds().filter(x => x !== id);
  ids.push(id);
  try { localStorage.setItem(ANNOUNCE_DISMISS_KEY, JSON.stringify(ids.slice(-100))); } catch (_) {}
  renderAnnouncements(lastActiveAnnouncements);
}

// ============ 限时活动（大厅加成提示） ============

let lastActiveEvents = [];

function loadEvents() {
  fetch('/api/events')
    .then(r => r.ok ? r.json() : null)
    .then(body => { if (body) renderEvents(body.events || [], body.serverTime || Date.now()); })
    .catch(() => { /* 活动加载失败不阻塞大厅 */ });
}

/** 用服务端时间算剩余时长，避免各端时钟不一致把「还剩 2 天」显示成「已结束」 */
function eventRemainingText(endsAt, serverTime) {
  const ms = endsAt - serverTime;
  if (ms <= 0) return '即将结束';
  const minutes = Math.floor(ms / 60000);
  if (minutes < 60) return '剩 ' + Math.max(1, minutes) + ' 分钟';
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return '剩 ' + hours + ' 小时';
  return '剩 ' + Math.floor(hours / 24) + ' 天';
}

function renderEvents(list, serverTime) {
  lastActiveEvents = list;
  const strip = document.getElementById('event-strip');
  if (!strip) return;
  if (!list.length) {
    strip.style.display = 'none';
    strip.innerHTML = '';
    return;
  }
  strip.style.display = 'flex';
  strip.innerHTML = list.map(e =>
    '<span class="event-chip" data-event-id="' + escHtml(e.id) + '">'
    + '⚡ ' + escHtml(e.gameLabel || '全部玩法') + ' 经验 +' + (e.xpPercent || 0) + '%'
    + '<small>' + escHtml(eventRemainingText(e.endsAt, serverTime)) + '</small></span>'
  ).join('');
}

/**
 * 快速匹配：优先进入同玩法、同人数的公开等待房间；没有就自动开一间公开房。
 * createRoom=false 是关键 —— true 会强制私有房，永远匹配不到人。
 */
// ============ PWA：安装入口与离线外壳 ============
let deferredInstallPrompt = null;

function installPwa() {
  if (!deferredInstallPrompt) return;
  const prompt = deferredInstallPrompt;
  deferredInstallPrompt = null;
  prompt.prompt();
  prompt.userChoice.finally(() => {
    const btn = document.getElementById('pwa-install');
    if (btn) btn.style.display = 'none';
  });
}

window.addEventListener('beforeinstallprompt', function (e) {
  e.preventDefault();
  deferredInstallPrompt = e;
  const btn = document.getElementById('pwa-install');
  if (btn) btn.style.display = '';
});

window.addEventListener('appinstalled', function () {
  const btn = document.getElementById('pwa-install');
  if (btn) btn.style.display = 'none';
});

if ('serviceWorker' in navigator
    && (location.protocol === 'https:' || location.hostname === 'localhost' || location.hostname === '127.0.0.1')) {
  window.addEventListener('load', function () {
    navigator.serviceWorker.register('/sw.js').catch(function () {});
  });
}

function showOnboarding() {
  const el = document.getElementById('onboard-overlay');
  if (el) el.classList.add('show');
}

function dismissOnboarding() {
  const el = document.getElementById('onboard-overlay');
  if (el) el.classList.remove('show');
  savePreference('gomoku_onboarded', '1');
}

function startOnboardingSolo() {
  dismissOnboarding();
  startSoloGame('GOMOKU');
}

function maybeShowOnboarding() {
  if (readPreference('gomoku_onboarded', '')) return;
  setTimeout(showOnboarding, 600);
}

function quickMatch() {
  matchRoomIsPublic = true;
  send({ type: 'JOIN', playerName: myName, gameType: selectedGameType, createRoom: false, playerCount: selectedPlayerCount() });
  initGameModule(selectedGameType);
  showGame();
  setStatusMsg('正在匹配同玩法的在线玩家…', 'waiting');
}

function createRoom() {
  // createRoom=true：强制新建私有房间，不把我匹配进别人的等待房间。
  // 之前这里只是普通 JOIN，点「创建房间」有可能直接坐进陌生人的房间。
  matchRoomIsPublic = false;
  send({ type: 'JOIN', playerName: myName, gameType: selectedGameType, createRoom: true, playerCount: selectedPlayerCount() });
  initGameModule(selectedGameType);
  showGame();
}

/**
 * 匹配总览：大厅玩法卡显示「N 人等待 / N 桌进行中」的数据来源。
 * 只读接口，不维护排队状态；快速匹配仍由服务端 JOIN 原子完成。
 */
function loadMatchOverview() {
  fetch('/api/match/overview')
    .then(r => r.json())
    .then(o => {
      latestMatchOverview = o;
      updateGameActivityBadges();
    })
    .catch(() => {});
}

function scheduleMatchOverview() {
  if (matchOverviewDebounce) return;
  matchOverviewDebounce = setTimeout(function () {
    matchOverviewDebounce = null;
    loadMatchOverview();
  }, 250);
}

function gameActivityText(type) {
  const g = latestMatchOverview && (latestMatchOverview.games || [])
    .find(x => x.gameType === type);
  if (!g) return '';
  if ((g.waitingPlayers || 0) > 0) return '⚡ ' + g.waitingPlayers + ' 人等待';
  if ((g.playingRooms || 0) > 0) return '▶ ' + g.playingRooms + ' 桌进行中';
  return '';
}

function updateGameActivityBadges() {
  document.querySelectorAll('#game-selector [data-activity]').forEach(el => {
    const text = gameActivityText(el.dataset.activity);
    el.textContent = text;
    el.style.display = text ? '' : 'none';
  });
  renderMatchWaitHint();
}

/** 进入等待房后启动助手：已等待时长 + 同玩法等待提示 + 补电脑/邀请/返回 */
function startMatchWait() {
  stopMatchWait(true);
  matchWaitStartedAt = Date.now();
  const bar = document.getElementById('match-helper');
  if (bar) bar.style.display = '';
  renderMatchWait();
  matchWaitTimer = setInterval(renderMatchWait, 1000);
  loadMatchOverview();
}

function stopMatchWait(hide) {
  if (matchWaitTimer) {
    clearInterval(matchWaitTimer);
    matchWaitTimer = null;
  }
  matchWaitStartedAt = 0;
  if (hide) {
    const bar = document.getElementById('match-helper');
    if (bar) bar.style.display = 'none';
  }
}

function renderMatchWait() {
  if (gameState !== 'WAITING' || myId === 'SPECTATOR' || !matchWaitStartedAt) {
    stopMatchWait(true);
    return;
  }
  const elapsed = Math.max(0, Math.floor((Date.now() - matchWaitStartedAt) / 1000));
  const el = document.getElementById('match-elapsed');
  if (el) {
    el.textContent = '匹配中 · 已等待 ' + Math.floor(elapsed / 60) + ':'
      + String(elapsed % 60).padStart(2, '0');
  }
  renderMatchWaitHint();
}

function renderMatchWaitHint() {
  const hint = document.getElementById('match-hint');
  if (!hint || gameState !== 'WAITING') return;
  if (!matchRoomIsPublic) {
    hint.textContent = '私人房间等待中 · 把房号发给好友，或补齐电脑立即开局';
    return;
  }
  const g = latestMatchOverview && (latestMatchOverview.games || [])
    .find(x => x.gameType === currentGameType);
  if (!g) {
    hint.textContent = '正在寻找同玩法的在线玩家…';
    return;
  }
  // 总览包含自己所在的公开房，展示「还有别人」时先减掉自己这一份
  const humans = Math.max(0, (g.waitingPlayers || 0) - 1);
  const rooms = Math.max(0, (g.waitingRooms || 0) - 1);
  if (humans <= 0) {
    hint.textContent = '暂时没有别人在等这个玩法 · 邀请好友，或补齐电脑立即开局';
  } else {
    hint.textContent = '大厅还有 ' + rooms + ' 间等待房 · ' + humans + ' 人同玩法在等';
  }
}

/** 等待房里一键补齐空座：让任何玩法都能在没人时立刻开打 */
function fillSeatsWithAI() {
  if (gameState !== 'WAITING' || myId === 'SPECTATOR') return;
  const empty = Math.max(0, (targetPlayers || 2) - Object.keys(players).length);
  if (empty <= 0) return;
  for (let i = 0; i < empty; i++) {
    setTimeout(function () { send({ type: 'ADD_AI', data: '4' }); }, i * 300);
  }
  setStatusMsg('正在补入 ' + empty + ' 个电脑…', 'waiting');
  addChat('补入 ' + empty + ' 个电脑开局', 'system');
}

/** 房号归一化：允许「1cbf9a」「ROOM-1cbf9a」「 1CBF9A 」 */
function normalizeRoomCode(raw) {
  const s = String(raw || '').trim().toUpperCase();
  if (!s) return '';
  return s.startsWith('ROOM-') ? s : 'ROOM-' + s;
}

/** 大厅「加入房间」：按房号进好友的房间 */
function joinByCode() {
  const input = document.getElementById('join-code');
  const code = normalizeRoomCode(input && input.value);
  if (!code) {
    setStatusMsg('请输入房间号', 'error');
    return;
  }
  doJoinByCode(code);
}

/** 按归一化后的房号加入（邀请链接也会走这里） */
function doJoinByCode(code) {
  matchRoomIsPublic = false;
  send({ type: 'JOIN', playerName: myName, gameType: selectedGameType, roomId: code });
  initGameModule(selectedGameType);
  showGame();
}

/** 复制邀请文案（房号 + 可点击链接） */
function copyInvite() {
  if (!roomId) return;
  const code = roomId.replace(/^ROOM-/, '');
  const url = location.origin + '/?room=' + encodeURIComponent(code) + '&game=' + currentGameType;
  const text = '来下棋：房间号 ' + code + '\n' + url;
  const done = function () { setStatusMsg('邀请已复制，发给好友即可', 'waiting'); };
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(done, function () { fallbackCopy(text); done(); });
  } else {
    fallbackCopy(text);
    done();
  }
}

/** 剪贴板不可用时的兜底（http 非 localhost / 旧浏览器） */
function fallbackCopy(text) {
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  try { document.execCommand('copy'); } catch (e) {}
  document.body.removeChild(ta);
}

function joinSpecificRoom(id, gameType) {
  matchRoomIsPublic = false;
  send({ type: 'JOIN', playerName: myName, roomId: id, gameType: gameType || selectedGameType });
  initGameModule(gameType || selectedGameType);
  showGame();
}

function spectateRoom(id, gameType) {
  send({ type: 'SPECTATE', playerName: myName, roomId: id });
  initGameModule(gameType || selectedGameType);
  showGame();
}

function leaveRoom() {
  stopMatchWait(true);
  if (replayMode) exitReplay();
  send({ type: 'LEAVE' });
  clearSessionBackup();
  // 重置前端状态
  initGameModule(selectedGameType);
  players = {};
  moveCount = 0;
  lastMove = null;
  myStone = 0;
  currentTurn = 1;
  winLine = null;
  gameState = 'LOBBY';
  document.getElementById('gameover-overlay').classList.remove('show');
  document.getElementById('move-count').textContent = '落子数: 0';
  // 回到大厅后隐藏邀请入口（房间已离开）
  const inviteBtnLeave = document.getElementById('invite-btn');
  if (inviteBtnLeave) inviteBtnLeave.style.display = 'none';
  showLobby();
  drawBoard();
}

function addAI() {
  const sel = document.getElementById('ai-difficulty');
  const difficulty = sel ? sel.value : '4';
  send({ type: 'ADD_AI', data: difficulty });
}

function send(obj) {
  if (!ws || ws.readyState !== WebSocket.OPEN) return;
  if (obj.type === 'JOIN' && guestToken) obj.guestToken = guestToken;
  if (actionVersion && ['MOVE', 'PASS', 'END_TURN'].includes(obj.type)) obj.actionVersion = actionVersion;
  if (useBinary && !obj.actionVersion && !obj.guestToken && obj.action === undefined && obj.type !== 'END_TURN') {
    const binary = encodeBinary(obj);
    if (binary) {
      ws.send(binary);
      return;
    }
    // 二进制编码失败，降级到 JSON
  }
  ws.send(JSON.stringify(obj));
}

function sendMove(row, col, fromRow, fromCol) {
  moveSeq++;
  const msg = { type: 'MOVE', row, col, moveSeq };
  if (fromRow !== undefined && fromRow >= 0) {
    msg.fromRow = fromRow;
    msg.fromCol = fromCol;
  }
  send(msg);
}

// 非棋盘玩法：按行动序号出招（RPG 技能栏按下标发送）
function sendAction(actionIndex) {
  if (gameState !== 'PLAYING' || myStone <= 0 || myId === 'SPECTATOR') return;
  if (currentGameType === 'QUIZ' || currentGameType === 'HIGH_CARD' || currentGameType === 'DICE_RACE' || currentGameType === 'GEM_AUCTION') {
    const mine = rpgView?.players?.find(p => p.side === myStone);
    if (!mine || mine.answered || mine.active === false || rpgView.over) return;
  } else if (currentTurn !== myStone) return;
  send({ type: 'MOVE', action: actionIndex, moveSeq });
}

function sendUnoCatch() {
  if (gameState !== 'PLAYING' || myStone <= 0 || myId === 'SPECTATOR') return;
  if (currentGameType !== 'UNO' || !rpgView || !rpgView.unoCatchable || rpgView.unoPendingSide === myStone) return;
  send({ type: 'MOVE', action: 103, moveSeq });
}

function sendPass() {
  if (gameState !== 'PLAYING' || currentTurn !== myStone) return;
  send({ type: 'PASS' });
}

function sendQuickChat(text) {
  if (!['PLAYING', 'WAITING', 'OVER'].includes(gameState)) return;
  if (myId === 'SPECTATOR') return;
  send({ type: 'CHAT', message: text });
}

/** 大厅实时人气：在线人数与对局数，让玩家判断现在是否有人一起玩 */
function loadOnlineStats() {
  fetch('/api/stats')
    .then(r => r.json())
    .then(s => {
      const el = document.getElementById('online-count');
      if (!el) return;
      const players = Number(s.totalPlayers || 0);
      const playing = Number(s.playingRooms || 0);
      el.textContent = '在线 ' + players + ' · 对局 ' + playing;
    })
    .catch(() => {});
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
    initGameModule(currentGameType);
    moveCount = 0;
    lastMove = null;
    winLine = null;
    document.getElementById('move-count').textContent = '落子数: 0';
    drawBoard();
  }
}

// ============================================================
// 大厅：游戏选择
// ============================================================
const GAME_CATEGORY = {
  GOMOKU: 'board', REVERSI: 'board', GO9: 'board', XIANGQI: 'board', CONNECT4: 'board',
  CARD: 'card', UNO: 'card', HIGH_CARD: 'card', BLACKJACK: 'card',
  DICE_RACE: 'party', GEM_AUCTION: 'party',
  RPG: 'duel', NIM: 'duel', QUIZ: 'duel'
};
const CATEGORY_LABELS = { ALL: '全部', board: '棋盘', card: '卡牌', party: '派对', duel: '对战' };
let selectedCategory = 'ALL';

function buildGameSelector() {
  const container = document.getElementById('game-selector');
  if (!container) return;
  const types = Object.keys(GameModules).filter(type => selectedCategory === 'ALL' || GAME_CATEGORY[type] === selectedCategory);
  const catBox = document.getElementById('game-categories');
  if (catBox) {
    catBox.innerHTML = '';
    Object.keys(CATEGORY_LABELS).forEach(cat => {
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'game-category' + (cat === selectedCategory ? ' selected' : '');
      chip.textContent = CATEGORY_LABELS[cat];
      chip.setAttribute('role', 'tab');
      chip.setAttribute('aria-selected', String(cat === selectedCategory));
      chip.onclick = function () {
        selectedCategory = cat;
        // 切分类时保持「当前选中玩法」可见，避免建房/人机按钮指向隐藏卡片
        const inCategory = Object.keys(GameModules).filter(t => cat === 'ALL' || GAME_CATEGORY[t] === cat);
        if (!inCategory.includes(selectedGameType) && inCategory.length) selectedGameType = inCategory[0];
        buildGameSelector();
        updatePlayerCountSelector();
        refreshRooms();
      };
      catBox.appendChild(chip);
    });
  }
  container.innerHTML = '';
  types.forEach(type => {
    const m = GameModules[type];
    const selected = type === selectedGameType;
    const card = document.createElement('div');
    card.className = 'game-card' + (selected ? ' selected' : '');
    card.dataset.gameType = type;
    // 选中卡片直接放「人机对战」按钮 —— 这是单机模式的唯一入口。
    // 之前只能先「快速开始」进房间、再等对手、再找「添加电脑」按钮，
    // 而那个按钮因为渲染 bug 从来没出现过，等于一个人完全没法开局。
    card.innerHTML = `<div class="game-card-icon">${m.icon}</div>
      <div class="game-card-name">${m.name}</div>
      <div class="game-card-desc">${m.footer}</div>
      <span class="game-card-activity" data-activity="${type}" style="display:none"></span>
      ${selected ? `<button class="btn-solo" data-solo="${type}">▶ 人机对战</button>` : ''}`;
    card.onclick = function (e) {
      const soloBtn = e.target.closest ? e.target.closest('[data-solo]') : null;
      if (soloBtn) {
        e.stopPropagation();
        startSoloGame(soloBtn.dataset.solo);
        return;
      }
      selectedGameType = type;
      buildGameSelector();
      updatePlayerCountSelector();
      refreshRooms();
    };
    container.appendChild(card);
  });
  updateGameActivityBadges();
}

/**
 * 单机模式：建房 + 立刻加入电脑，一步开局。
 *
 * 服务端本来就有 ADD_AI，但之前要玩家手动点两次（快速开始 → 添加电脑）。
 * 这里把「选难度」也一起收进来，默认中等。
 */
/** 大厅头部的「人机对战」：用当前选中的玩法直接开局 */
function startSoloQuick() {
  startSoloGame(selectedGameType || 'GOMOKU');
}

function startSoloGame(type) {
  // 先用界面内弹层选「难度」（卡牌再选套牌），再开局。
  askSoloDifficulty(type, function (difficulty, deck, deckCards) {
    selectedGameType = type;
    matchRoomIsPublic = false;
    initGameModule(type);
    showGame();
    setStatusMsg('正在准备人机对战…', 'waiting');
    addChat('人机对战：正在创建房间…' + (deck ? '（套牌：' + deckLabel(deck) + '）' : ''), 'system');

    // JOIN 里带 deck：套牌必须开局前定下来（服务端建库时读取）。
    const joinMsg = { type: 'JOIN', playerName: myName, gameType: type, createRoom: true, playerCount: selectedPlayerCount(type) };
    if (deck) joinMsg.deck = deck;
    // 自定义套牌：把配比 JSON 一起带上（服务端会独立校验）
    if (deckCards) joinMsg.deckCards = JSON.stringify(deckCards);
    send(joinMsg);
    setTimeout(function () {
      const aiMsg = { type: 'ADD_AI', data: String(difficulty) };
      // AI 也给它一套套牌，否则永远均衡，风格对抗就没了
      if (deck) aiMsg.deck = counterDeck(deck);
      const count = selectedPlayerCount(type) - 1;
      for (let i = 0; i < count; i++) setTimeout(() => send(aiMsg), i * 350);
    }, 500);
  });
}

// ============================================================
// 自定义套牌编辑器
// ============================================================
// 卡牌清单必须与服务端 Card 枚举保持一致：名字（枚举名）用于传输，
// 中文名/费用用于展示。服务端会用「枚举名」反查校验，写错会被拒。
const DECK_CARDS = [
  { key: 'FOOTMAN',     label: '步兵',     cost: 1, atk: 1, hp: 2, desc: '1/2' },
  { key: 'ARCHER',      label: '弓箭手',   cost: 2, atk: 2, hp: 1, desc: '2/1 冲锋' },
  { key: 'KNIGHT',      label: '骑士',     cost: 3, atk: 3, hp: 3, desc: '3/3' },
  { key: 'GUARDIAN',    label: '守卫',     cost: 4, atk: 2, hp: 5, desc: '2/5 嘲讽' },
  { key: 'DRAGON',      label: '幼龙',     cost: 6, atk: 6, hp: 5, desc: '6/5' },
  { key: 'FIREBALL',    label: '火球术',   cost: 3, atk: 0, hp: 0, desc: '造成 4 点伤害' },
  { key: 'HEAL',        label: '治疗术',   cost: 2, atk: 0, hp: 0, desc: '恢复 5 点生命' },
  { key: 'CLEAVE',      label: '横扫',     cost: 5, atk: 0, hp: 0, desc: '敌方随从各 3 伤' },
  { key: 'BUFF',        label: '鼓舞',     cost: 2, atk: 0, hp: 0, desc: '一个随从 +2/+2' },
  { key: 'BERSERKER',   label: '狂战士',   cost: 4, atk: 4, hp: 3, desc: '4/3 战吼 2 伤' },
  { key: 'BONE_DRAGON', label: '骨龙',     cost: 5, atk: 4, hp: 4, desc: '4/4 亡语 3 伤' },
  { key: 'SHIELD_GUARD',label: '圣盾卫兵', cost: 4, atk: 3, hp: 2, desc: '3/2 圣盾嘲讽' },
  { key: 'SKELETON',    label: '骷髅',     cost: 3, atk: 2, hp: 2, desc: '2/2 亡语召唤' },
  { key: 'VAMPIRE',      label: '吸血鬼',   cost: 4, atk: 3, hp: 4, desc: '3/4 吸血' },
  { key: 'VAMPIRE_LORD', label: '血族领主', cost: 7, atk: 6, hp: 6, desc: '6/6 吸血' },
  { key: 'SWORDSMAN',    label: '剑客',     cost: 3, atk: 2, hp: 4, desc: '2/4 连击 +2 攻' },
  { key: 'ASSASSIN',     label: '刺客',     cost: 5, atk: 3, hp: 3, desc: '3/3 连击 冲锋' },
  { key: 'SHIELD_BEARER',label: '持盾者',   cost: 2, atk: 1, hp: 4, desc: '1/4 嘲讽' },
  { key: 'WAR_TITAN',    label: '战争泰坦', cost: 8, atk: 9, hp: 9, desc: '9/9 圣盾嘲讽' },
  { key: 'NECROMANCER',  label: '死灵法师', cost: 6, atk: 5, hp: 5, desc: '5/5 亡语召唤 3/3' },
  { key: 'BLOOD_QUEEN',  label: '血皇后',   cost: 7, atk: 5, hp: 6, desc: '5/6 吸血连击' },
];
const MAX_COPIES = 6;
const DECK_SIZE = 30;

let deckDraft = {}; // { FOOTMAN: 6, ... }

// ============================================================
// 套牌本地保存
// ============================================================
// 用 localStorage 记住玩家上次配的套牌：每次打开编辑器都从空白开始，
// 等于让玩家重复劳动一遍（30 次点击），体验很差。
const DECK_STORAGE_KEY = 'gomoku_custom_deck_v1';

/**
 * 读取上次保存的配比。
 *
 * 必须做**结构校验**：localStorage 是用户可编辑的，也可能残留旧版本格式。
 * 读到一个坏数据就让整个编辑器崩掉（或开局被服务端拒）是不可接受的，
 * 所以任何异常/unrecognized 字段都直接当作"没有保存过"。
 */
function loadSavedDeck() {
  try {
    const raw = localStorage.getItem(DECK_STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return {};

    const known = new Set(DECK_CARDS.map(c => c.key));
    const result = {};
    let total = 0;
    for (const [key, val] of Object.entries(parsed)) {
      if (!known.has(key)) continue;                 // 忽略未知卡（旧版本残留）
      const n = Math.floor(Number(val));
      if (!Number.isFinite(n) || n <= 0) continue;    // 忽略非法数量
      const capped = Math.min(n, MAX_COPIES);         // 夹到单卡上限
      if (total + capped > DECK_SIZE) break;          // 夹到总数上限
      result[key] = capped;
      total += capped;
    }
    return result;
  } catch (e) {
    return {}; // 坏数据一律当作没存过
  }
}

/** 保存配比（空配比则清除记录） */
function saveDeck(draft) {
  try {
    const total = Object.values(draft || {}).reduce((a, b) => a + b, 0);
    if (total <= 0) {
      localStorage.removeItem(DECK_STORAGE_KEY);
      return;
    }
    localStorage.setItem(DECK_STORAGE_KEY, JSON.stringify(draft));
  } catch (e) {
    // 隐私模式/配额满：保存失败不影响本局，静默忽略
  }
}

/** 渲染编辑器（首次打开或计数变化时调用） */
function renderDeckEditor() {
  const grid = document.getElementById('de-cards');
  const countEl = document.getElementById('de-count');
  const startBtn = document.getElementById('de-start');
  if (!grid) return;

  let total = 0;
  Object.values(deckDraft).forEach(n => { total += n; });

  grid.innerHTML = DECK_CARDS.map(c => {
    const n = deckDraft[c.key] || 0;
    return `<div class="de-card${n > 0 ? ' in-deck' : ''}" data-key="${c.key}">
      <div class="de-top">
        <span class="de-name">${escHtml(c.label)}</span>
        <span class="de-n${n === 0 ? ' zero' : ''}">${n}</span>
      </div>
      <div class="de-meta">${c.cost} 费 · ${escHtml(c.desc)}</div>
    </div>`;
  }).join('');

  if (countEl) {
    countEl.textContent = total + ' / ' + DECK_SIZE;
    countEl.className = 'de-count' + (total === DECK_SIZE ? ' full' : (total > DECK_SIZE ? ' over' : ''));
  }
  // 只有正好 30 张才允许开始
  if (startBtn) startBtn.disabled = total !== DECK_SIZE;
}

/** 只更新某张卡的计数显示（避免整格重建吞掉快速点击） */
function updateDeckCard(key) {
  const el = document.querySelector(`.de-card[data-key="${key}"]`);
  if (!el) return;
  const n = deckDraft[key] || 0;
  el.classList.toggle('in-deck', n > 0);
  const nEl = el.querySelector('.de-n');
  if (nEl) {
    nEl.textContent = n;
    nEl.classList.toggle('zero', n === 0);
  }
}

/** 更新总数与开始按钮可用性 */
function updateDeckCounter() {
  const countEl = document.getElementById('de-count');
  const startBtn = document.getElementById('de-start');
  let total = 0;
  Object.values(deckDraft).forEach(n => { total += n; });
  if (countEl) {
    countEl.textContent = total + ' / ' + DECK_SIZE;
    countEl.className = 'de-count' + (total === DECK_SIZE ? ' full' : (total > DECK_SIZE ? ' over' : ''));
  }
  if (startBtn) startBtn.disabled = total !== DECK_SIZE;
}

/** 绑定编辑器交互（只绑一次） */
function bindDeckEditor(onStart) {
  const grid = document.getElementById('de-cards');
  const resetBtn = document.getElementById('de-reset');
  const startBtn = document.getElementById('de-start');
  if (!grid || grid.dataset.bound === '1') return;
  grid.dataset.bound = '1';

  // 左键 +1，右键 -1（比加减两个按钮省空间，移动端长按也能用）
  grid.addEventListener('click', function (e) {
    const el = e.target.closest ? e.target.closest('.de-card') : null;
    if (!el) return;
    const key = el.dataset.key;
    const cur = deckDraft[key] || 0;
    let total = 0;
    Object.values(deckDraft).forEach(n => { total += n; });
    if (cur >= MAX_COPIES) return;      // 单卡上限
    if (total >= DECK_SIZE) return;     // 总数上限
    deckDraft[key] = cur + 1;
    // 只更新这卡的计数与总数，不重建整个网格。
    // 之前是整格重建：快速连点会命中已被替换的旧节点，点击被吞掉
    // （实测连点 6 次只加上了 1 次）。
    updateDeckCard(key);
    updateDeckCounter();
    saveDeck(deckDraft);
  });

  grid.addEventListener('contextmenu', function (e) {
    const el = e.target.closest ? e.target.closest('.de-card') : null;
    if (!el) return;
    e.preventDefault();
    const key = el.dataset.key;
    const cur = deckDraft[key] || 0;
    if (cur <= 0) return;
    if (cur === 1) delete deckDraft[key];
    else deckDraft[key] = cur - 1;
    updateDeckCard(key);
    updateDeckCounter();
    saveDeck(deckDraft);
  });

  if (resetBtn) resetBtn.onclick = function () {
    deckDraft = {};
    saveDeck(deckDraft); // 清空时一并删掉本地记录，避免下次又恢复回来
    renderDeckEditor();
  };

  if (startBtn) startBtn.onclick = function () {
    let total = 0;
    Object.values(deckDraft).forEach(n => { total += n; });
    if (total !== DECK_SIZE) return;
    onStart(deckDraft);
  };
}

/** 套牌名 → 中文标签 */
function deckLabel(deck) {
  return ({ BALANCED: '均衡', AGGRO: '快攻', CONTROL: '控制' })[deck] || deck;
}

/**
 * 给 AI 选一套与玩家不同的风格：
 * 快攻对控制、控制对快攻，让每局的对抗关系是明确的。
 */
function counterDeck(myDeck) {
  if (myDeck === 'AGGRO') return 'CONTROL';
  if (myDeck === 'CONTROL') return 'AGGRO';
  return 'AGGRO';
}

/**
 * 单机开局前的配置弹层：先选难度，卡牌玩法再选套牌。
 *
 * 回调签名 (difficulty, deck)；非卡牌玩法的 deck 为 null。
 * 交互：点难度项 = 选中；卡牌玩法会展开套牌区，点套牌即开局；
 * 非卡牌玩法点难度即直接开局（不额外多一步）。
 */
function askSoloDifficulty(type, onConfirm) {
  const dlg = document.getElementById('difficulty-dialog');
  if (!dlg) {
    onConfirm(4, null);
    return;
  }

  const isCard = type === 'CARD';
  const nameEl = document.getElementById('difficulty-game');
  const mod = GameModules[type];
  if (nameEl && mod) nameEl.textContent = mod.name;

  const deckSection = document.getElementById('deck-section');
  const diffOpts = [...dlg.querySelectorAll('.difficulty-opt')];
  const deckOpts = [...dlg.querySelectorAll('.deck-opt')];

  let chosenDiff = 4;
  let chosenDeck = 'BALANCED';
  let customDeckPayload = null;

  function cleanup() {
    dlg.classList.remove('show');
    dlg.onclick = null;
    diffOpts.forEach(function (b) { b.onclick = null; b.classList.remove('selected'); });
    deckOpts.forEach(function (b) { b.onclick = null; });
    if (deckSection) deckSection.style.display = 'none';
    const editorEl2 = document.getElementById('deck-editor');
    if (editorEl2) editorEl2.style.display = 'none';
    const cancelEl = document.getElementById('difficulty-cancel');
    if (cancelEl) cancelEl.onclick = null;
  }

  function finish() {
    const deck = isCard ? chosenDeck : null;
    const cards = (deck === 'CUSTOM') ? customDeckPayload : null;
    cleanup();
    onConfirm(chosenDiff, deck, cards);
  }

  const editorEl = document.getElementById('deck-editor');

  // 套牌项：预组选中即开局；「自定义」展开编辑器
  deckOpts.forEach(function (b) {
    b.classList.toggle('selected', b.dataset.deck === 'BALANCED');
    b.onclick = function () {
      const picked = b.dataset.deck || 'BALANCED';
      if (picked === 'CUSTOM') {
        deckOpts.forEach(function (x) { x.classList.toggle('selected', x === b); });
        if (editorEl) editorEl.style.display = 'block';
        // 恢复上次保存的配比：让玩家从"上次那套"继续改，
        // 而不是每次重新点 30 下（原来直接清空，重复劳动很明显）。
        deckDraft = loadSavedDeck();
        bindDeckEditor(function (draft) {
          chosenDeck = 'CUSTOM';
          customDeckPayload = Object.assign({}, draft);
          finish();
        });
        renderDeckEditor();
        return;
      }
      if (editorEl) editorEl.style.display = 'none';
      chosenDeck = picked;
      finish();
    };
  });

  diffOpts.forEach(function (b) {
    b.classList.toggle('selected', Number(b.dataset.diff) === 4);
    b.onclick = function () {
      chosenDiff = Number(b.dataset.diff) || 4;
      diffOpts.forEach(function (x) { x.classList.toggle('selected', x === b); });
      if (isCard) {
        // 卡牌还要选套牌，展开第二步
        if (deckSection) deckSection.style.display = 'block';
      } else {
        finish();
      }
    };
  });

  if (deckSection) deckSection.style.display = 'none';

  const cancelEl = document.getElementById('difficulty-cancel');
  if (cancelEl) cancelEl.onclick = cleanup;
  dlg.onclick = function (e) { if (e.target === dlg) cleanup(); };

  dlg.classList.add('show');
}

// ============================================================
// 消息处理
// ============================================================
function handleMessage(msg) {
  switch (msg.type) {
    case 'ROOM_LIST':
      // 收到 ROOM_LIST 说明连接已恢复：无论此前停在登录页还是大厅，都复位重连计数
      reconnectAttempts = 0;
      document.getElementById('reconnect-bar').classList.remove('show');
      setStatus(true, '已连接');
      // 首次收到 ROOM_LIST 说明连接合法，正式进入大厅
      if (document.getElementById('login-screen').style.display !== 'none') {
        document.getElementById('login-screen').style.display = 'none';
        showLobby();
        buildGameSelector();
        updatePlayerCountSelector();
        loadLeaderboard();
        maybeShowOnboarding();
        // 邀请链接（/?room=CODE）：登录成功后直接进房，不用再点一次
        if (pendingJoinCode) {
          const code = pendingJoinCode;
          pendingJoinCode = '';
          doJoinByCode(code);
        }
      }
      handleRoomList(msg);
      break;

    case 'ROOM_INFO':
      handleRoomInfo(msg);
      break;

    case 'WAITING':
      handleWaiting(msg);
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

    case 'PLAYER_STATUS':
      // 玩家断线/重连通知（不重置棋盘）
      if (msg.message) {
        setStatusMsg(msg.message, 'waiting');
        addChat(msg.message, 'system');
      }
      break;

    case 'RESTART_REQUEST':
      if (msg.playerId ? msg.playerId === myId : msg.playerName === myName) { addChat(msg.message, 'system'); break; }
      showConfirm(msg.message || '对手请求再来一局', function() {
        send({ type: 'RESTART' });
      });
      break;

    case 'TIMER_SYNC':
      handleTimerSync(msg);
      break;

    case 'FRIEND_EVENT':
      // 好友事件实时提醒（上线/下线/收到申请/申请通过）；旧前端无此分支会静默忽略
      handleFriendEvent(msg);
      break;

    case 'DIRECT_MSG':
      handleDirectMsg(msg);
      break;

    case 'ERROR':
      // Silently handle reconnect failure — clear stale session
      if (msg.message && msg.message.includes('重连')) {
        mySessionId = null;
        clearPersistedSession();
        break;
      }
      // IP 限流：停止重连，留在登录页
      if (msg.message && msg.message.includes('连接数超限')) {
        reconnectAttempts = MAX_RECONNECT; // 阻止指数退避重连
        showConfirm(msg.message, null);
        showLogin();
        break;
      }
      // 私聊未送达（对方不在线/非好友/过长）：状态栏内联提示，不弹模态打断
      if (msg.message && msg.message.includes('私聊')) {
        setStatusMsg(msg.message, 'error');
        break;
      }
      // 举报相关错误：回填到举报弹窗里，方便直接改了再提交
      if (msg.message && /举报|电脑玩家|被封禁|禁言/.test(msg.message)) {
        const hint = document.getElementById('report-hint');
        if (hint && document.getElementById('report-modal').classList.contains('show')) {
          hint.textContent = msg.message;
        } else {
          setStatusMsg(msg.message, 'error');
        }
        break;
      }
      // 落子被拒：用服务端回传的真实手数校准本地 moveSeq
      // （否则本地序号一旦超前，后续每一步都会被服务端判为 seq 不匹配而永久失败）
      if (msg.message === '无效落子' && msg.data !== null && msg.data !== undefined) {
        const serverSeq = parseInt(msg.data, 10);
        if (!isNaN(serverSeq)) moveSeq = serverSeq;
        setStatusMsg('该落子无效，已同步手数，请重试', 'opponent-turn');
        break;
      }
      showConfirm(msg.message || '未知错误', null);
      if (msg.message === '房间内玩家已全部离开，房间解散' || msg.message.includes('失败') || msg.message.includes('不存在') || msg.message.includes('已满')) {
        showLobby();
      }
      break;
  }
}

function handleWaiting(msg) {
  gameState = 'WAITING';
  if (msg.playerId) myId = msg.playerId;
  const waitingType = msg.gameType || currentGameType;
  const waitingCapacity = waitingType === currentGameType ? targetPlayers : null;
      if (msg.sessionId) {
        mySessionId = msg.sessionId;
        sessionStorage.setItem('gomoku_session', mySessionId); clearSessionBackup();
      }
      // 重置棋盘画面和游戏状态，但保留 players（由 ROOM_INFO 更新）

  // 等待中把房号显示出来，并给一个复制入口 —— 这是「邀请好友」的唯一时机
  if (msg.roomId) {
    roomId = msg.roomId;
    const ridEl = document.getElementById('room-id');
    if (ridEl) ridEl.textContent = '房号: ' + roomId.replace(/^ROOM-/, '');
  }
  const inviteBtn = document.getElementById('invite-btn');
  if (inviteBtn) inviteBtn.style.display = '';

  initGameModule(waitingType);
  if (waitingCapacity) targetPlayers = waitingCapacity;
  moveCount = 0;
  lastMove = null;
  winLine = null;
  document.getElementById('gameover-overlay').classList.remove('show');
  document.getElementById('move-count').textContent = '落子数: 0';
  showSurrenderBtn();
  let wMsg = msg.message;
  if (!wMsg) {
    wMsg = (myId === 'SPECTATOR') ? '等待玩家加入...' : '等待对手加入...';
  }
  if (gameState === 'WAITING' && roomId && myId !== 'SPECTATOR') {
    wMsg += '（房号 ' + roomId.replace(/^ROOM-/, '') + '，点「复制邀请」发给好友）';
  }
  setStatusMsg(wMsg, 'waiting');
  addChat(wMsg, 'system');
  updatePlayerPanel();
  drawBoard();
  if (myId === 'SPECTATOR') stopMatchWait(true);
  else startMatchWait();
}

function handleRoomInfo(msg) {
  if (msg.roomId) {
    roomId = msg.roomId;
    document.getElementById('room-id').textContent = '房号: ' + roomId.replace(/^ROOM-/, '');
  }
  try {
    const data = JSON.parse(msg.data);
    if (data.gameType && GameModules[data.gameType]) {
      if (data.gameType !== currentGameType) initGameModule(data.gameType);
    }
    targetPlayers = data.targetPlayers || targetPlayers;
    if (data.actionVersion) actionVersion = data.actionVersion;
    if (data.view) rpgView = data.view;
    players = {};
    if (data.players && Array.isArray(data.players)) {
      data.players.forEach(p => { players[p.id] = p; });
    }
    renderSpectatorLine(data.spectatorCount, data.spectators);

    updatePlayerPanel();
  } catch(e) {
    console.error('handleRoomInfo error', e);
  }
}

function handleRoomList(msg) {
  if (gameState === 'LOBBY') scheduleMatchOverview();
  const container = document.getElementById('room-list-container');
  try {
    const list = JSON.parse(msg.data);
    // 只显示当前选中游戏类型的房间
    const filtered = list.filter(r => !r.gameType || r.gameType === selectedGameType);
    container.innerHTML = '';
    if (filtered.length === 0) {
      const empty = document.createElement('div');
      empty.style.cssText = 'color:var(--text-dim);grid-column:1/-1;text-align:center;padding:40px;';
      empty.textContent = '「' + GameModules[selectedGameType].name + '」暂无房间，点击"快速开始"创建';
      container.appendChild(empty);
      return;
    }

    filtered.forEach(r => {
      const isWaiting = r.state === 'WAITING';
      const isPlaying = r.state === 'PLAYING';
      const isFinished = r.state === 'FINISHED';
      const isFull = r.playerCount >= (r.targetPlayers || 2);
      // 三种状态要分开：之前只判 PLAYING/其他，FINISHED 被显示成「等待中」，
      // 于是大厅里会出现「等待中 · 人数 2/2」这种自相矛盾的房间。
      const stateClass = isFinished ? 'state-finished' : (isPlaying ? 'state-playing' : 'state-waiting');
      const stateText = isFinished ? '已结束' : (isPlaying ? '对战中' : '等待中');
      const gameName = GameModules[r.gameType] ? GameModules[r.gameType].name : (r.gameType || '五子棋');

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

      // 队伍徽标：只显示队伍短码，不暴露成员身份或其它队伍信息
      if (Array.isArray(r.partyIds) && r.partyIds.length) {
        const partySpan = document.createElement('span');
        partySpan.className = 'room-card-party';
        partySpan.textContent = '队伍 ' + r.partyIds
          .map(id => String(id).replace(/^PTY-/, '')).join(' / ');
        header.appendChild(partySpan);
      }

      const info = document.createElement('div');
      info.className = 'room-card-info';

      const playersText = r.players.length > 0 ? r.players.map(escHtml).join(' vs ') : '空房间';
      const countDiv = document.createElement('div');
      countDiv.style.cssText = 'font-size:0.75rem;margin-top:4px;';
      countDiv.textContent = gameName + ' | 人数: ' + r.playerCount + '/' + (r.targetPlayers || 2) + ' | 观众: ' + r.spectatorCount;

      const playerDiv = document.createElement('div');
      playerDiv.textContent = '玩家: ';
      playerDiv.innerHTML = '玩家: ' + playersText;

      info.appendChild(playerDiv);
      info.appendChild(countDiv);

      const actions = document.createElement('div');
      actions.className = 'room-card-actions';

      // 只有真正的等待房能加入：FINISHED 也不可（服务端会拒绝，按钮不该先撒谎）
      if (isWaiting && !isFull) {
        const joinBtn = document.createElement('button');
        joinBtn.className = 'btn-outline';
        joinBtn.textContent = '加入';
        const rid = r.roomId;
        const rtype = r.gameType;
        joinBtn.onclick = function() { joinSpecificRoom(rid, rtype); };
        actions.appendChild(joinBtn);
      }

      const specBtn = document.createElement('button');
      specBtn.className = 'btn-outline';
      specBtn.style.cssText = 'border-color:#555;color:#ccc;';
      specBtn.textContent = '观战';
      const rid2 = r.roomId;
      const rtype2 = r.gameType;
      specBtn.onclick = function() { spectateRoom(rid2, rtype2); };
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
  if (msg.gameType) initGameModule(msg.gameType);
  let restoredResult = null;
  if (msg.sessionId) {
    mySessionId = msg.sessionId;
    sessionStorage.setItem('gomoku_session', mySessionId); clearSessionBackup();
  }

  // 区分观战和断线重连
  if (msg.playerId && msg.stone) {
    myId = msg.playerId;
    myStone = msg.stone;
    if (msg.sessionId) {
      mySessionId = msg.sessionId;
      sessionStorage.setItem('gomoku_session', mySessionId); clearSessionBackup();
    }
  } else {
    gameState = 'SPECTATING';
    myId = 'SPECTATOR';
    myStone = 0;
  }

  try {
    const data = JSON.parse(msg.data);

    if (myId !== 'SPECTATOR') gameState = data.state === 'PLAYING' ? 'PLAYING' : data.state === 'FINISHED' ? 'OVER' : 'WAITING';
    // 恢复玩家信息
    players = {};
    targetPlayers = data.roomData.targetPlayers || targetPlayers;
    actionVersion = data.roomData.actionVersion || null;
    data.roomData.players.forEach(p => { players[p.id] = p; });
    currentTurn = data.roomData.currentTurn;
    renderSpectatorLine(data.roomData.spectatorCount, data.roomData.spectators);

    // 非棋盘玩法没有盘面，view 才是真正的状态来源
    if (data.roomData.view) rpgView = data.roomData.view;
    if (data.view) rpgView = data.view;

    // 恢复棋盘（RPG 这类玩法 board 为 null，不能直接解引用）
    board = data.board || (mod().html ? null : mod().initialBoard());
    restoredResult = data.gameOver || null;
    if (data.timers) {
      timerData = data.timers;
    }

    // 重新计算 moveCount
    moveCount = 0;
    if (board) {
      for(let r=0; r<board.length; r++) {
          for(let c=0; c<board[r].length; c++) {
              if(board[r][c] !== 0) moveCount++;
          }
      }
    }
    if (Number.isInteger(data.moveCount)) moveCount = data.moveCount;
    moveSeq = Number.isInteger(data.moveSeq) ? data.moveSeq : moveCount;
  } catch(e) {}

  document.getElementById('room-id').textContent = '房号: ' + roomId.replace(/^ROOM-/, '') + (myId === 'SPECTATOR' ? ' (观战中)' : '');
  document.getElementById('move-count').textContent = '落子数: ' + moveCount;
  document.getElementById('gameover-overlay').classList.remove('show');
  document.getElementById('restart-bar').style.display = 'none';

  // 重连成功：隐藏登录页
  reconnectAttempts = 0;
  document.getElementById('reconnect-bar').classList.remove('show');
  document.getElementById('login-screen').style.display = 'none';
  showGame();
  setStatus(true, '已连接');

  updatePlayerPanel();
  updateTimerDisplay();
  showSurrenderBtn();
  setStatusMsg(myId === 'SPECTATOR' ? '观战中' : (msg.message || '已同步局面'), 'waiting');
  drawBoard();

  addChat(`系统：${msg.message}`, 'system');
  if (restoredResult) handleGameOver(restoredResult);
}

function handleGameStart(msg) {
  myId = msg.playerId;
  myStone = msg.stone;
  if (msg.sessionId) { mySessionId = msg.sessionId; sessionStorage.setItem('gomoku_session', mySessionId); clearSessionBackup(); }
  roomId = msg.roomId;
  // 开局后不再需要邀请入口
  const inviteBtnStart = document.getElementById('invite-btn');
  if (inviteBtnStart) inviteBtnStart.style.display = 'none';
  if (msg.gameType) initGameModule(msg.gameType); else initGameModule(currentGameType);
  gameState = 'PLAYING';
  lastGameOverEvent = null;
  stopMatchWait(true);
  currentTurn = 1; // 先手方（黑）
  moveCount = 0;
  moveSeq = 0;
  lastMove = null;
  winLine = null;

  // 解析房间数据
  try {
    const data = JSON.parse(msg.data);
    targetPlayers = data.targetPlayers || targetPlayers;
    actionVersion = data.actionVersion || null;
    players = {};
    data.players.forEach(p => { players[p.id] = p; });
    currentTurn = data.currentTurn;
    renderSpectatorLine(data.spectatorCount, data.spectators);
    // 非棋盘玩法：开局就带上完整战斗状态，面板不用等第一手才出内容
    if (data.view) rpgView = data.view;
  } catch(e) {}

  document.getElementById('room-id').textContent = '房号: ' + roomId.replace(/^ROOM-/, '');
  document.getElementById('gameover-overlay').classList.remove('show');
  document.getElementById('restart-bar').style.display = 'none';
  document.getElementById('replay-bar').style.display = 'none';
  replayMode = false;

  // 组队由服务端直接下发 GAME_START，不经过客户端 JOIN；此时队员仍在大厅。
  // 先显示对局区域，再渲染座位与棋盘；已有对局页面保留聊天记录。
  if (document.getElementById('game-screen').style.display === 'none') showGame();
  updatePlayerPanel();
  updateTurnStatus();
  showSurrenderBtn();
  drawBoard();

  const sideLabel = mod().sides?.[(myStone || 1) - 1]?.label || '座位 ' + myStone;
  addChat(`游戏开始！你执${sideLabel}方，对手：${msg.message}`, 'system');

}

function showSurrenderBtn() {
  var surrenderRow = document.getElementById('surrender-row');
  if (surrenderRow) {
    surrenderRow.style.display = (gameState === 'PLAYING' && myId && myId !== 'SPECTATOR') ? 'block' : 'none';
  }
}

function handleGameMove(msg) {
  const m = mod();
  const r = msg.row, c = msg.col;

  // 非棋盘玩法（RPG）：盘面在 view 里，不碰 board，也不做落子音效
  if (m.html) {
    const data = applyViewData(msg.data);
    if (data && typeof data.turn === 'number') currentTurn = data.turn;
    moveCount++;
    if (typeof msg.moveSeq === 'number' && msg.moveSeq > 0) moveSeq = msg.moveSeq;
    document.getElementById('move-count').textContent = '手数: ' + moveCount;
    updateTurnStatus();
    drawBoard();
    return;
  }

  // 停一手广播（围棋 PASS：row === -2）
  if (r === -2) {
    const passer = players[msg.playerId];
    addChat((passer ? passer.name : msg.playerName || '对手') + ' 停一手', 'system');
    lastMove = null;
  } else if (m.usePieceValues) {
    // 棋子移动类游戏（象棋）：盘面由 effects 权威更新（清起点 + 落终点）
    lastMove = [r, c];
    moveCount++;
  } else {
    board[r][c] = msg.stone;
    lastMove = [r, c];
    moveCount++;
  }

  // 解析轮次 + 效果（提子/翻转/棋子移动/自动停一手）
  try {
    const data = JSON.parse(msg.data);
    currentTurn = data.turn;
    if (data.timers) timerData = data.timers;
    if (data.actionVersion) actionVersion = data.actionVersion;
    if (data.effects && data.effects.length) {
      const autoPassSide = m.applyEffects(board, data.effects);
      if (autoPassSide) {
        const sideName = mod().sides ? mod().sides[autoPassSide - 1].label + '方' : (autoPassSide === 1 ? '黑方' : '白方');
        addChat(sideName + '无合法落子，自动停一手', 'system');
      }
    }
  } catch(e) {
    // 兼容旧协议：data 为纯数字轮次
    currentTurn = parseInt(msg.data) || (msg.stone === 1 ? 2 : 1);
  }

  // 同步 moveSeq：服务端是唯一权威，无论大小都以它为准。
  // 若只在「服务端更大」时同步，一旦本地序号超前（丢过响应/重发过消息），
  // 后续每一步都会被判 seq 不匹配而永久失败。
  if (typeof msg.moveSeq === 'number' && msg.moveSeq > 0) {
    moveSeq = msg.moveSeq;
  }

  document.getElementById('move-count').textContent = '手数: ' + moveCount;
  updateTurnStatus();
  drawBoard();
  if (r !== -2) playStoneSound();
}

/** 本局结算时生效的限时活动（由 GAME_OVER 下发，用于在结算页解释额外经验） */
let lastGameOverEvent = null;

function handleGameOver(msg) {
  gameState = 'OVER';
  showSurrenderBtn();
  const overlay = document.getElementById('gameover-overlay');
  const result = document.getElementById('gameover-result');
  const sub = document.getElementById('gameover-sub');
  const scores = document.getElementById('gameover-scores');

  // 复盘按钮只对棋盘玩法有意义：RPG 没有盘面，文字战报在「最近对局」里看
  const enterReplayBtn = document.getElementById('btn-enter-replay');
  if (enterReplayBtn) enterReplayBtn.style.display = mod().html ? 'none' : '';

  const isSpectator = myId === 'SPECTATOR';
  const overData = JSON.parse(msg.data || '{}');
  lastGameOverEvent = overData.event || null;
  const winningSides = overData.winningSides || [];
  const drawingSides = overData.drawingSides || [];
  const isPush = drawingSides.includes(myStone);
  const decisiveNoWinner = overData.decisiveNoWinner === true;
  const isDraw = isPush || (msg.winner === 'draw' && winningSides.length === 0 && !decisiveNoWinner);
  const isWin = !isPush && (msg.winner === myId || winningSides.includes(myStone));

  // 解析附加数据（分数 + 获胜连线 + ELO + 对局记录）
  let winLineData = null;
  let eloChange = 0;
  try {
    const data = JSON.parse(msg.data);
    winLineData = data.winLine || null;
    eloChange = data.eloChange || 0;
    // 保存对局记录（用于回放）
    if (data.moveHistory && data.moveHistory.length > 0) {
      replayMoves = data.moveHistory;
      replayEffects = data.moveEffects || [];
    }
    // 分数（含 ELO）
    let scoresHtml = '';
    data.scores.forEach(p => {
      const changeStr = p.eloChange ? (p.eloChange > 0 ? `+${p.eloChange}` : `${p.eloChange}`) : '';
      scoresHtml += `<div class="score-item">
        <div class="score-num">${p.wins}</div>
        <div class="score-label">${escHtml(p.name.substring(0,6))} 胜${changeStr ? ' <span style="color:' + (p.eloChange>0?'#4caf50':'#f44336') + '">' + changeStr + '</span>' : ''}</div>
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
    } else if (decisiveNoWinner) {
      result.textContent = '庄家获胜';
      setStatusMsg('游戏结束：庄家获胜', 'lose');
    } else {
      const winnerName = Object.values(players).filter(p => winningSides.includes(p.stone)).map(p => p.name).join('、') || players[msg.winner]?.name || '玩家';
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

  // 游戏结束音效
  if (!isSpectator) {
    if (isDraw) {} else if (isWin) playWinSound(); else playLoseSound();
  }

  // 非棋盘玩法（RPG/卡牌）的 HTML 面板不会随终局自动刷新，
  // 否则会一直停在「你的回合、请出牌」，看起来像还能继续操作。
  // 重画一次，让回合提示与手牌可用性跟着 gameState=OVER 一起失效。
  if (mod().html) drawBoard();

  overlay.classList.add('show');

  // 结算成长反馈：先记下终局前的快照，等成长与任务都刷新后再对比，
  // 让玩家在结算页直接看到「这一局得到了什么」，而不是自己去翻面板。
  const beforeProgress = progressSnapshot;
  const beforeQuests = lastQuestStatus ? questDigest(lastQuestStatus) : null;
  Promise.all([loadDailyProgress(), refreshProgressForRewards(beforeProgress, 2)]).then(function () {
    renderGameoverRewards(beforeProgress, beforeQuests);
  });
}

/** 终局查询可能早于存档索引（服务端已先存档再广播，这里再兜一层），没看到经验就短暂重试 */
function refreshProgressForRewards(before, attemptsLeft) {
  return loadPlayerProgress().then(function () {
    const gained = before && progressSnapshot && progressSnapshot.xp > before.xp;
    if (!gained && before && attemptsLeft > 0) {
      return new Promise(function (resolve) { setTimeout(resolve, 250); })
        .then(function () { return refreshProgressForRewards(before, attemptsLeft - 1); });
    }
  });
}

function renderGameoverRewards(beforeProgress, beforeQuests) {
  const box = document.getElementById('gameover-rewards');
  if (!box) return;
  box.innerHTML = '';
  if (!guestToken || myId === 'SPECTATOR') return;

  const chips = [];
  const after = progressSnapshot;
  if (beforeProgress && after && after.xp > beforeProgress.xp) {
    chips.push('<span class="reward-chip">本局 +' + (after.xp - beforeProgress.xp) + ' 经验</span>');
    if (lastGameOverEvent && lastGameOverEvent.xpPercent > 0) {
      chips.push('<span class="reward-chip reward-event">活动「' + escHtml(lastGameOverEvent.title || '限时活动')
        + '」+' + lastGameOverEvent.xpPercent + '% 经验</span>');
    }
    if (after.level > beforeProgress.level) {
      chips.push('<span class="reward-chip reward-levelup">升级 Lv.' + after.level + ' · ' + escHtml(after.title) + '</span>');
    }
    const beforeIds = {};
    beforeProgress.unlocked.forEach(a => { beforeIds[a.id] = true; });
    const fresh = after.unlocked.filter(a => !beforeIds[a.id]);
    fresh.slice(0, 2).forEach(a => chips.push(
      '<span class="reward-chip reward-achievement">解锁成就「' + escHtml(a.name) + '」</span>'));
    if (fresh.length > 2) {
      chips.push('<span class="reward-chip reward-achievement">另有 ' + (fresh.length - 2) + ' 项成就达成</span>');
    }
  }
  const quests = lastQuestStatus;
  if (beforeQuests && quests) {
    if ((quests.claimableXp || 0) > beforeQuests.claimableXp) {
      chips.push('<span class="reward-chip reward-quest">每日任务可领取 +'
        + ((quests.claimableXp || 0) - beforeQuests.claimableXp) + ' 经验</span>');
    } else if ((quests.todayGames || 0) > beforeQuests.todayGames) {
      chips.push('<span class="reward-chip reward-quest">每日任务 ' + Math.min(quests.todayGames, 3) + '/3 局</span>');
    }
  }
  box.innerHTML = chips.join('');
}

function handleGameChat(msg) {
  // 举报确认走的是房间内 GAME_CHAT 通道，先让举报弹窗给出反馈
  if (/举报/.test(msg.message || '')) handleReportFeedback(msg);
  // 离线私聊回执：提示已保存，并标记该条为「待送达」而不是发送失败
  if (/离线消息/.test(msg.message || '')) {
    setStatusMsg(msg.message, 'waiting');
    return;
  }
  const isMe = msg.playerId === myId;
  addChat(msg.message, isMe ? 'me' : 'other', msg.playerName, isMe);
}

// ============================================================
// UI 更新
// ============================================================
function updatePlayerPanel() {
  const container = document.getElementById('player-seats');
  if (!container) return;
  container.innerHTML = '';
  const seated = Object.values(players);
  for (let side = 1; side <= targetPlayers; side++) {
    const player = seated.find(p => p.stone === side) || seated.filter(p => !p.stone)[side - 1];
    const slot = document.createElement('div');
    slot.id = side === 1 ? 'player-black' : side === 2 ? 'player-white' : 'player-seat-' + side;
    const label = (mod().sides || [])[side - 1]?.label || '座位 ' + side;
    if (player) {
      const total = timerData.seatTotals?.[side - 1] ?? mod().totalTime;
      // 队伍徽标：同队队员显示同一个 PTY 短码，一眼看出谁是一起来的
      const partyChip = player.partyId
        ? '<span class="seat-party" title="队伍 ' + escHtml(player.partyId) + '">' + escHtml(String(player.partyId).replace(/^PTY-/, '队 ')) + '</span>'
        : '';
      slot.innerHTML = `<div class="player-card"><div class="player-card-top">
        <span class="seat-number">${side}</span><div class="player-name ${player.id === myId ? 'you' : ''}">${escHtml(player.name)}${player.id === myId ? '（你）' : ''}</div>
        <span class="timer-display" id="timer-${side}">${formatTime(total)}</span></div>
        <div class="player-stats">${partyChip}${escHtml(label)} · 胜 ${player.wins || 0}${player.tier ? ' · <span class="seat-tier">' + escHtml(player.tier) + '</span>' : ''}${targetPlayers === 2 ? ' · ELO ' + (player.elo || 1200) : ''}</div>
        <div id="turn-${side}" style="display:none"><span class="turn-badge">● 行动中</span><span id="step-timer-${side}" class="timer-display">${timerData.stepTime}s</span></div></div>`;
    } else {
      slot.innerHTML = `<div class="waiting-slot">${escHtml(label)} · 等待玩家</div>`;
      if (gameState === 'WAITING' && myId !== 'SPECTATOR') {
        const button = document.createElement('button'); button.className = 'btn-outline add-ai-seat';
        button.textContent = '添加电脑'; button.onclick = addAI; slot.firstChild.appendChild(button);
      }
    }
    container.appendChild(slot);
  }
  if (gameState === 'PLAYING') updateTurnStatus();
  else updateTimerDisplay();
}

function updateTurnStatus() {
  // 显示/隐藏认输按钮
  var surrenderRow = document.getElementById('surrender-row');
  if (surrenderRow) {
    surrenderRow.style.display = (gameState === 'PLAYING' && myId && myId !== 'SPECTATOR') ? 'block' : 'none';
  }
  // 停一手按钮：仅围棋且轮到自己
  var passBtn = document.getElementById('pass-btn');
  if (passBtn) {
    passBtn.style.display = (currentGameType === 'GO9' && gameState === 'PLAYING'
      && currentTurn === myStone && myId !== 'SPECTATOR') ? 'inline-block' : 'none';
  }
  // 隐藏所有 turn badge
  Array.from({ length: targetPlayers }, (_, i) => i + 1).forEach(s => {
    const el = document.getElementById('turn-' + s);
    if (el) el.style.display = 'none';
  });

  const simultaneous = currentGameType === 'HIGH_CARD' || currentGameType === 'QUIZ' || currentGameType === 'DICE_RACE' || currentGameType === 'GEM_AUCTION';
  const pendingSides = simultaneous && rpgView?.players
    ? rpgView.players.filter(p => p.active !== false && !p.answered).map(p => p.side)
    : [currentTurn];
  const isMyTurn = pendingSides.includes(myStone);
  pendingSides.forEach(side => {
    const turnEl = document.getElementById('turn-' + side);
    if (turnEl) turnEl.style.display = 'block';
  });

  if (isMyTurn) {
    const hint = simultaneous ? '同时选择中 · 提交后停止计时'
      : currentGameType === 'GO9' ? '你的回合（点击落子或停一手）'
      : currentGameType === 'XIANGQI' ? '你的回合（先点己方棋子，再点目标点）'
      : mod().html ? '你的回合，选择一个行动' : '你的回合，请落子';
    setStatusMsg(hint, 'your-turn');
    if (!mod().html) canvas.style.cursor = 'crosshair';
  } else {
    setStatusMsg(simultaneous
      ? myId === 'SPECTATOR' ? '观战中 · 等待玩家提交' : '已提交或已退出，等待其他玩家'
      : '等待对方行棋...', 'opponent-turn');
    if (!mod().html) canvas.style.cursor = 'not-allowed';
  }
  // 大厅/等待态下页脚展示当前游戏说明
  const footer = document.getElementById('board-footer');
  if (footer) footer.textContent = mod().footer;
  updateTimerDisplay();
}

/**
 * 渲染观战者行。
 *
 * 原来只有「观众数: N」—— 玩家看不到是谁在看，观战者也互相不可见。
 * 现在把昵称一并显示；人数多时只列前几位，避免把侧栏挤爆。
 */
function renderSpectatorLine(count, names) {
  const el = document.getElementById('spectator-count');
  if (!el) return;
  const n = count || 0;
  if (n === 0) {
    el.textContent = '观众: 0';
    el.title = '';
    return;
  }
  const list = Array.isArray(names) ? names : [];
  const shown = list.slice(0, 4).map(escHtml).join('、');
  const more = list.length > 4 ? ' 等 ' + list.length + ' 人' : '';
  el.textContent = '观众: ' + n + (shown ? '（' + shown + more + '）' : '');
  // 完整名单放 tooltip，避免侧栏过长
  el.title = list.join('、');
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
// 计时器
// ============================================================
function formatTime(seconds) {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return m + ':' + (s < 10 ? '0' : '') + s;
}

function getTimerClass(seconds) {
  if (seconds <= 10) return 'danger';
  if (seconds <= 30) return 'warning';
  return 'normal';
}

function handleTimerSync(msg) {
  try {
    const data = JSON.parse(msg.data);
    timerData.stepTime = data.stepTime;
    timerData.blackTotal = data.blackTotal;
    timerData.whiteTotal = data.whiteTotal;
    timerData.seatTotals = data.seatTotals || [data.blackTotal, data.whiteTotal];
    timerData.seatSteps = data.seatSteps;
    timerData.awaitingSides = data.awaitingSides;
    // 仅更新计时 DOM，不重建整个面板
    updateTimerDisplay();
  } catch(e) {}
}

function updateTimerDisplay() {
  const totals = Object.fromEntries((timerData.seatTotals || [timerData.blackTotal, timerData.whiteTotal]).map((time, i) => [i + 1, time]));
  Array.from({ length: targetPlayers }, (_, i) => i + 1).forEach(s => {
    const el = document.getElementById('timer-' + s);
    if (el) {
      const seconds = totals[s] ?? mod().totalTime;
      el.textContent = formatTime(seconds);
      el.className = 'timer-display ' + getTimerClass(seconds);
    }
  });
  // 步时
  Array.from({ length: targetPlayers }, (_, i) => i + 1).forEach(s => {
    const el = document.getElementById('step-timer-' + s);
    if (el) {
      const seconds = timerData.seatSteps?.[s - 1] ?? timerData.stepTime;
      el.textContent = seconds + 's';
      el.className = 'timer-display ' + ((timerData.awaitingSides || [currentTurn]).includes(s) ? getTimerClass(seconds) : '');
    }
  });
}

// ============================================================
// 回放（按游戏重建：moveHistory + moveEffects）
// ============================================================
function enterReplay() {
  if (replayMoves.length === 0) return;
  // 非棋盘玩法没有盘面可回放，复盘走对局记录弹窗里的文字战报
  if (!board) { alert('该玩法没有棋盘，请在「最近对局」里查看文字战报'); return; }
  document.getElementById('gameover-overlay').classList.remove('show');
  document.getElementById('restart-bar').style.display = 'none';

  replayMode = true;
  replayIndex = 0;
  replayPlaying = false;
  savedBoard = board.map(r => [...r]); // 快照当前棋盘

  // 清空棋盘用于回放
  initGameModule(currentGameType);
  lastMove = null;
  winLine = null;
  drawBoard();

  const slider = document.getElementById('replay-slider');
  slider.max = replayMoves.length;
  slider.value = 0;
  document.getElementById('replay-step-text').textContent = '0/' + replayMoves.length;
  document.getElementById('replay-play-btn').textContent = '▶';
  document.getElementById('replay-bar').style.display = 'block';
}

function exitReplay() {
  replayMode = false;
  replayPlaying = false;
  if (replayTimer) { clearInterval(replayTimer); replayTimer = null; }
  document.getElementById('replay-bar').style.display = 'none';

  // 恢复棋盘
  if (savedBoard) {
    board = savedBoard;
    savedBoard = null;
  }
  drawBoard();

  // 游戏已结束时显示再来一局按钮
  if (gameState === 'OVER' && myId !== 'SPECTATOR') {
    document.getElementById('restart-bar').style.display = 'flex';
  }
}

function replayStep(dir) {
  if (!replayMode) return;
  const newIndex = Math.max(0, Math.min(replayMoves.length, replayIndex + dir));
  replayIndex = newIndex;
  replayDraw();
}

function replaySeek(val) {
  if (!replayMode) return;
  replayIndex = parseInt(val);
  replayDraw();
}

function replayDraw() {
  const m = mod();
  board = m.initialBoard(); // 从初始局面开始重建（黑白棋含中央四子）
  lastMove = null;
  for (let i = 0; i < replayIndex; i++) {
    const mv = replayMoves[i];
    if (mv[0] < 0) continue; // 停一手
    if (!m.usePieceValues) {
      board[mv[0]][mv[1]] = mv[2];
    }
    // 应用该手效果（提子/翻转/棋子移动）
    const effects = replayEffects[i];
    if (effects && effects.length) {
      m.applyEffects(board, effects);
    }
    lastMove = [mv[0], mv[1]];
  }
  winLine = null;

  document.getElementById('replay-slider').value = replayIndex;
  document.getElementById('replay-step-text').textContent = replayIndex + '/' + replayMoves.length;
  drawBoard();
}

function toggleReplayPlay() {
  if (replayPlaying) {
    replayPlaying = false;
    if (replayTimer) { clearInterval(replayTimer); replayTimer = null; }
    document.getElementById('replay-play-btn').textContent = '▶';
  } else {
    if (replayIndex >= replayMoves.length) {
      replayIndex = 0;
      replayDraw();
    }
    replayPlaying = true;
    document.getElementById('replay-play-btn').textContent = '⏸';
    replayTimer = setInterval(() => {
      if (replayIndex >= replayMoves.length) {
        toggleReplayPlay();
        return;
      }
      replayIndex++;
      replayDraw();
      playStoneSound();
    }, replaySpeed);
  }
}

function changeReplaySpeed() {
  replaySpeed = parseInt(document.getElementById('replay-speed').value);
  if (replayPlaying) {
    clearInterval(replayTimer);
    replayTimer = setInterval(() => {
      if (replayIndex >= replayMoves.length) { toggleReplayPlay(); return; }
      replayIndex++;
      replayDraw();
      playStoneSound();
    }, replaySpeed);
  }
}

// ============================================================
// 战绩：我的统计 + 最近对局 + 独立复盘弹窗
// ============================================================
let rmMoves = [];         // 复盘棋谱
let rmEffects = [];       // 与棋谱对齐的每手效果
let rmGameType = 'GOMOKU';
let rmShareId = null;
let rmIndex = 0;
let rmTimer = null;
let rmPlaying = false;
const RM_SPEED = 350;

const GAME_TYPE_LABEL = {
  GOMOKU: '五子棋',
  REVERSI: '黑白棋',
  GO9: '围棋',
  XIANGQI: '象棋',
  CONNECT4: '四子棋',
  RPG: '回合制对战',
  // 之前漏了这三个，记录页会显示「CARD 复盘」这种英文标题
  CARD: '卡牌对战',
  NIM: '取石子',
  HIGH_CARD: '比大小',
  UNO: 'UNO 对战',
  QUIZ: '答题对战',
  BLACKJACK: '21 点'
  ,DICE_RACE: '骰子竞速'
  ,GEM_AUCTION: '宝石拍卖'
};

function gameTypeLabel(type) {
  return GAME_TYPE_LABEL[type] || type || '';
}

/** 时间戳 → MM-DD HH:mm */
function formatStamp(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  const p = n => (n < 10 ? '0' + n : '' + n);
  return p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}

/** 加载「我的战绩」（按当前选中的排行榜游戏页签统计） */
function loadMyStats() {
  if (!myName) return;
  fetch(guestToken ? '/api/me/stats?gameType=' + selectedLbType : '/api/stats/player/' + encodeURIComponent(myName) + '?gameType=' + selectedLbType, { headers: guestToken ? { Authorization: 'Bearer ' + guestToken } : {} })
    .then(r => r.json())
    .then(s => {
      const set = (id, val) => {
        const el = document.getElementById(id);
        if (el) el.textContent = val;
      };
      set('my-win', s.wins || 0);
      set('my-lose', s.losses || 0);
      set('my-draw', s.draws || 0);
      set('my-rate', s.total ? s.winRate + '%' : '—');
      // 多人休闲局也计入胜/负/平；ELO 只对计分局有意义，避免把默认 1200 当成竞技分展示
      if (s.rankedTotal) set('my-elo', 'ELO ' + s.elo + (s.casualTotal ? ' · 休闲 ' + s.casualTotal : ''));
      else set('my-elo', s.casualTotal ? '休闲局 ' + s.casualTotal : 'ELO —');
    })
    .catch(() => {});
}

/** 渲染签到卡片：未签到显示可点按钮，已签到显示今日已领与连续天数 */
function renderCheckInStatus(s) {
  const card = document.getElementById('checkin-card');
  const title = document.getElementById('checkin-title');
  const sub = document.getElementById('checkin-sub');
  const btn = document.getElementById('checkin-btn');
  if (!card || !title || !sub || !btn) return;
  const signed = !!s.signedToday;
  const streak = s.streak || 0;
  card.classList.toggle('signed', signed);
  if (signed) {
    title.textContent = '今日已签到 · 连续 ' + streak + ' 天';
    const next = s.nextMilestone || 0;
    sub.textContent = next ? '再连续 ' + (next - streak) + ' 天可解锁 +' + milestoneXp(next) + ' 经验' : '已达最高连续奖励';
    btn.textContent = '已签到';
    btn.disabled = true;
  } else {
    title.textContent = '每日签到 · +15 经验';
    let extra = '';
    if (s.comebackAvailable) extra = ' · 欢迎回归，额外 +50';
    else if (s.nextMilestone) extra = ' · 连续 ' + streak + ' 天，明日里程碑 +' + milestoneXp(s.nextMilestone);
    sub.textContent = '连续签到可解锁额外奖励' + extra;
    btn.textContent = '签到';
    btn.disabled = false;
  }
}

/** 里程碑经验与后端 CheckInService.MILESTONES 保持一致 */
function milestoneXp(day) {
  return ({ 3: 30, 7: 80, 14: 150, 30: 400 })[day] || 0;
}

function loadCheckInStatus() {
  const card = document.getElementById('checkin-card');
  if (!card || !guestToken) return;
  fetch('/api/checkin/status', { headers: { Authorization: 'Bearer ' + guestToken } })
    .then(r => r.ok ? r.json() : Promise.reject(new Error('unauthorized')))
    .then(s => renderCheckInStatus(s))
    .catch(() => {});
}

function doCheckIn() {
  const btn = document.getElementById('checkin-btn');
  if (!guestToken || (btn && btn.disabled)) return;
  if (btn) { btn.disabled = true; btn.textContent = '签到中…'; }
  fetch('/api/checkin', { method: 'POST', headers: { Authorization: 'Bearer ' + guestToken } })
    .then(r => r.ok ? r.json() : Promise.reject(new Error('unauthorized')))
    .then(s => {
      renderCheckInStatus(Object.assign({}, s, { signedToday: true }));
      const reward = s.rewardXp || 0;
      if (reward > 0 && typeof setStatusMsg === 'function') {
        setStatusMsg('签到成功，+' + reward + ' 经验' + (s.comeback ? '（含回归礼包）' : ''), 'win');
      }
      // 经验已计入等级，刷新成长面板让玩家立刻看到升级进度
      loadPlayerProgress();
    })
    .catch(() => {
      if (btn) { btn.disabled = false; btn.textContent = '签到'; }
      if (typeof setStatusMsg === 'function') setStatusMsg('签到失败，请稍后重试', 'error');
    });
}

// 终局结算反馈用的快照：只记录展示所需的最小字段，避免把整份进度留在内存里
let progressSnapshot = null;
let lastQuestStatus = null;
let lastProgress = null;
let achievementFilter = 'all';

function progressDigest(p) {
  return {
    xp: p.xp || 0,
    level: p.level || 1,
    title: p.title || '',
    unlocked: (p.achievements || []).filter(a => a.unlocked).map(a => ({ id: a.id, name: a.name }))
  };
}

function questDigest(s) {
  return { claimableXp: s.claimableXp || 0, todayGames: s.todayGames || 0 };
}

function loadDailyProgress() {
  loadCheckInStatus();
  const box = document.getElementById('daily-progress');
  if (!box || !guestToken) return Promise.resolve();
  return fetch('/api/quests', { headers: { Authorization: 'Bearer ' + guestToken } })
    .then(r => r.ok ? r.json() : Promise.reject(new Error('unauthorized')))
    .then(s => { renderDailyQuests(s); return s; })
    .catch(() => { box.textContent = '任务加载失败，稍后重试'; });
}

/** 渲染每日任务：进行中显示进度，完成后出现领取按钮，领取后变「已领取」 */
function renderDailyQuests(s) {
  lastQuestStatus = s;
  const box = document.getElementById('daily-progress');
  const streakEl = document.getElementById('daily-streak');
  if (!box) return;
  if (streakEl) streakEl.textContent = '连续 ' + (s.streakDays || 0) + ' 天';
  box.innerHTML = (s.quests || []).map(q => {
    const reward = q.rewardXp || 0;
    let action;
    if (q.claimed) action = '<span class="quest-claimed">已领取</span>';
    else if (q.claimable) action = '<button class="quest-claim" onclick="claimQuest(\'' + escHtml(q.id) + '\')">领取</button>';
    else action = '<strong>' + Math.min(q.progress || 0, q.goal || 1) + '/' + (q.goal || 1) + '</strong>';
    return '<div class="daily-item ' + (q.claimed || q.claimable ? 'done' : '') + '" title="' + escHtml(q.desc || '') + '">'
      + '<span class="daily-icon">' + escHtml(q.icon || '🎯') + '</span>'
      + '<span class="daily-label">' + escHtml(q.name || '') + ' <em class="quest-xp">+' + reward + ' XP</em></span>'
      + action + '</div>';
  }).join('');
}

function claimQuest(id) {
  if (!guestToken) return;
  document.querySelectorAll('.quest-claim').forEach(btn => { btn.disabled = true; });
  fetch('/api/quests/' + encodeURIComponent(id) + '/claim', {
    method: 'POST', headers: { Authorization: 'Bearer ' + guestToken }
  })
    .then(async r => {
      const body = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(body.message || '领取失败');
      return body;
    })
    .then(res => {
      if (res.status) renderDailyQuests(res.status);
      if (typeof setStatusMsg === 'function') setStatusMsg('任务奖励已领取，+' + (res.rewardXp || 0) + ' 经验', 'win');
      loadPlayerProgress();
    })
    .catch(e => {
      if (typeof setStatusMsg === 'function') setStatusMsg(e.message || '领取失败，请稍后重试', 'error');
      loadDailyProgress();
    });
}

// ============ 好友：好友码互加 · 在线状态 · 一键加入好友房间 ============

let friendsLoadedOnce = false;
let lastFriendsData = null;

/** 拉取好友全景（好友码/好友列表/申请）；失败静默，不打扰大厅 */
function loadFriends() {
  const listEl = document.getElementById('friends-list');
  if (!listEl || !guestToken) return;
  loadParty();
  fetch('/api/friends', { headers: { Authorization: 'Bearer ' + guestToken } })
    .then(r => r.ok ? r.json() : Promise.reject(new Error('unauthorized')))
    .then(data => renderFriends(data))
    .catch(() => { if (!friendsLoadedOnce) listEl.textContent = '好友服务暂不可用'; });
}

// ============ 队伍（在线好友组队：队长选玩法，全队由服务端一次安排进同一房间） ============

let partyData = null;

/** 拉取我的队伍与邀请（与好友列表同频刷新，面板一起更新） */
function loadParty() {
  if (!guestToken) return;
  fetch('/api/party', { headers: { Authorization: 'Bearer ' + guestToken } })
    .then(r => r.ok ? r.json() : Promise.reject(new Error('unauthorized')))
    .then(data => {
      partyData = data;
      renderParty();
      // 同队标记要出现在好友行上，队伍变化后顺手重绘一次
      if (lastFriendsData) renderFriends(lastFriendsData);
    })
    .catch(() => {});
}

/** 队伍面板：成员状态 + 队长的玩法选择与开局入口，邀请即时处理。 */
function renderParty() {
  const box = document.getElementById('party-panel');
  if (!box) return;
  const data = partyData || {};
  const party = data.party;
  const invites = data.invites || [];
  if (!party && !invites.length) { box.hidden = true; box.innerHTML = ''; return; }
  box.hidden = false;

  const parts = [];
  if (party) {
    const members = (party.members || []).map(m =>
      '<span class="party-member ' + (m.online ? 'online' : 'offline') + '">'
      + (m.leader ? '👑 ' : '') + escHtml(m.name || '玩家')
      + (m.online ? '' : '（离线）') + '</span>').join('');
    parts.push('<div class="party-head"><span class="party-code">队伍 ' + escHtml(String(party.id).replace(/^PTY-/, ''))
      + '</span><span class="party-count">' + (party.members || []).length + '/' + (data.maxMembers || 8)
      + '</span><button class="leave-btn friend-mini" onclick="leaveParty()">退出队伍</button></div>'
      + '<div class="party-members">' + members + '</div>');
    if (party.isLeader) {
      const options = Object.keys(GameModules).map(type => {
        const module = GameModules[type];
        const enough = party.members.length >= (module.minPlayers || 2)
          && party.members.length <= (module.maxPlayers || 2);
        return '<option value="' + type + '"' + (type === selectedGameType ? ' selected' : '')
          + (enough ? '' : ' disabled') + '>' + escHtml(module.name)
          + '（' + (module.minPlayers === module.maxPlayers ? module.maxPlayers
            : (module.minPlayers || 2) + '-' + (module.maxPlayers || 2)) + ' 人）</option>';
      }).join('');
      const ready = (party.members || []).every(m => m.online) && (party.members || []).length >= 2;
      parts.push('<label class="party-pick">全队玩：<select id="party-game" onchange="partyGameChanged()">' + options + '</select></label>'
        + '<button class="btn-match party-start"' + (ready ? '' : ' disabled')
        + ' onclick="startPartyGame()">▶ 带全队开局（' + party.members.length + ' 人）</button>'
        + '<div class="party-hint" id="party-hint">'
        + (ready ? '开局后全队进入同一房间；人数与玩法不符时无法开局。'
                 : '需要至少 2 名在线队员；有人离线时暂时无法开局。')
        + '</div>');
    } else {
      parts.push('<div class="party-hint">队长选择玩法并开局后，你会自动进入同一间房。</div>');
    }
  }
  if (invites.length) {
    parts.push(invites.map(i =>
      '<div class="party-invite"><span>' + escHtml(i.fromName || '好友') + ' 邀请你组队</span>'
      + '<span class="friend-request-actions">'
      + '<button class="btn-outline friend-mini" onclick="respondPartyInvite(\'' + i.id + '\',\'accept\')">加入</button>'
      + '<button class="leave-btn friend-mini" onclick="respondPartyInvite(\'' + i.id + '\',\'decline\')">拒绝</button>'
      + '</span></div>').join(''));
  }
  const picker = document.getElementById('party-game');
  if (picker) picker.value = selectedGameType;
  box.innerHTML = parts.join('');
}

/** 队长切换全队玩法：同步大厅玩法选择，人数选择器与会话目标保持一致。 */
function partyGameChanged() {
  const picker = document.getElementById('party-game');
  if (!picker || !picker.value) return;
  selectedGameType = picker.value;
  buildGameSelector();
  updatePlayerCountSelector();
  refreshRooms();
  renderParty();
}

/** 邀请好友组队（好友行 👥；仅在线好友可邀请，服务端同样校验） */
function inviteToParty(friendId) {
  if (!guestToken) return;
  fetch('/api/party/invite', {
    method: 'POST',
    headers: Object.assign({ 'Content-Type': 'application/json' }, { Authorization: 'Bearer ' + guestToken }),
    body: JSON.stringify({ target: friendId })
  }).then(async r => {
    const body = await r.json().catch(() => null);
    if (!r.ok) throw new Error((body && body.message) || '邀请失败');
    setStatusMsg('组队邀请已发送', 'waiting');
    loadParty();
  }).catch(e => setStatusMsg(e.message || '邀请失败', 'error'));
}

function respondPartyInvite(inviteId, action) {
  if (!guestToken) return;
  fetch('/api/party/invites/' + encodeURIComponent(inviteId) + '/' + action, {
    method: 'POST', headers: { Authorization: 'Bearer ' + guestToken }
  }).then(async r => {
    const body = await r.json().catch(() => null);
    if (!r.ok) throw new Error((body && body.message) || '操作失败');
    setStatusMsg(body.message || '已处理', 'win');
    loadParty();
  }).catch(e => setStatusMsg(e.message || '操作失败', 'error'));
}

function leaveParty() {
  if (!guestToken) return;
  fetch('/api/party/members', { method: 'DELETE', headers: { Authorization: 'Bearer ' + guestToken } })
    .then(async r => {
      const body = await r.json().catch(() => null);
      if (!r.ok) throw new Error((body && body.message) || '退出失败');
      setStatusMsg(body.message || '已退出队伍', 'waiting');
      loadParty();
    }).catch(e => setStatusMsg(e.message || '退出失败', 'error'));
}

/**
 * 队长带全队开局：只提交玩法，房号/座位/实际人数由服务端一次安排。
 * 任一队员离线、掉线或仍在房间中都不会占座，错误原样提示给队长。
 */
function startPartyGame() {
  const picker = document.getElementById('party-game');
  const type = (picker && picker.value) || selectedGameType;
  const button = document.querySelector('.party-start');
  if (button) { button.disabled = true; button.textContent = '正在开局…'; }
  fetch('/api/party/start', {
    method: 'POST',
    headers: Object.assign({ 'Content-Type': 'application/json' }, { Authorization: 'Bearer ' + guestToken }),
    body: JSON.stringify({ gameType: type })
  }).then(async r => {
    const body = await r.json().catch(() => null);
    if (!r.ok) throw new Error((body && body.message) || '开局失败');
    setStatusMsg('全队开局成功，正在进入房间…', 'waiting');
    // 服务端已把所有人放进同一房间并各自下发 GAME_START；队长这里只等推送。
  }).catch(e => {
    setStatusMsg(e.message || '开局失败', 'error');
    renderParty();
  });
}

function renderFriends(data) {
  friendsLoadedOnce = true;
  lastFriendsData = data;
  const listEl = document.getElementById('friends-list');
  const countEl = document.getElementById('friend-count');
  const codeEl = document.getElementById('friend-code');
  const reqEl = document.getElementById('friend-requests');
  if (!listEl || !reqEl) return;

  if (codeEl && data.me && data.me.code) codeEl.textContent = data.me.code;
  if (countEl) countEl.textContent = (data.friends ? data.friends.length : 0) + '/50';

  // 待处理申请：收到的可同意/拒绝，发出的可撤回
  const incoming = data.incoming || [];
  const outgoing = data.outgoing || [];
  const rows = incoming.map(r =>
    '<div class="friend-request-row"><span class="friend-dot offline"></span><span class="friend-name">' + escHtml(r.name || '玩家') + ' 请求加你为好友</span>'
    + '<span class="friend-request-actions"><button class="btn-outline friend-mini" onclick="respondFriendRequest(\'' + r.id + '\',\'accept\')">同意</button>'
    + '<button class="leave-btn friend-mini" onclick="respondFriendRequest(\'' + r.id + '\',\'decline\')">拒绝</button></span></div>');
  for (const r of outgoing) {
    rows.push('<div class="friend-request-row"><span class="friend-dot offline"></span><span class="friend-name">已向 ' + escHtml(r.name || '玩家') + ' 发出申请</span>'
      + '<span class="friend-request-actions"><button class="leave-btn friend-mini" onclick="cancelFriendRequest(\'' + r.id + '\')">撤回</button></span></div>');
  }
  reqEl.hidden = rows.length === 0;
  reqEl.innerHTML = rows.join('');

  const friends = data.friends || [];
  if (!friends.length) {
    listEl.innerHTML = '<div class="friends-empty">还没有好友。把你的好友码发给朋友互加，或直接输入对方的好友码 / 用户名。</div>';
    return;
  }
  listEl.innerHTML = friends.map(f => {
    const roomLabel = f.roomId
      ? (f.joinable
          ? '<button class="btn-outline friend-mini" onclick="joinFriendRoom(\'' + f.roomId + '\',\'' + (f.gameType || '') + '\')">▶ 加入 TA 的房间</button>'
          : '<span class="friend-room-label">房间 ' + escHtml(String(f.roomId).replace(/^ROOM-/, '')) + '（不可加入）</span>')
      : '';
    const sameParty = partyData && partyData.party && (partyData.party.members || []).some(m => m.id === f.id);
    const partyChip = sameParty ? '<span class="friend-party">同队</span>' : '';
    const unread = dmUnread[f.id];
    const badge = unread ? '<span class="dm-badge" title="未读私聊">' + unread + '</span>' : '';
    // 邀请组队只对在线好友可用：离线时点击只会得到错误，直接把按钮收起来更清楚
    const inviteBtn = (f.online && !sameParty)
      ? '<button class="btn-outline friend-mini" title="邀请组队" onclick="inviteToParty(\'' + f.id + '\')">👥</button>'
      : '';
    return '<div class="friend-row">'
      + '<span class="friend-dot ' + (f.online ? 'online' : 'offline') + '"></span>'
      + '<span class="friend-name" title="' + escHtml(f.id) + '">' + escHtml(f.name || '玩家') + '</span>'
      + partyChip + badge
      + '<span class="friend-room">' + roomLabel + '</span>'
      + '<button class="btn-outline friend-mini" title="私聊" onclick="openFriendChat(\'' + f.id + '\')">💬</button>'
      + inviteBtn
      + '<button class="leave-btn friend-mini" onclick="removeFriendById(\'' + f.id + '\')">✕</button>'
      + '</div>';
  }).join('');
}

/** 好友码 / 用户名添加好友 */
function addFriend() {
  const input = document.getElementById('friend-input');
  if (!input || !guestToken) return;
  const target = (input.value || '').trim();
  if (!target) { setStatusMsg('请输入对方的好友码或用户名', 'error'); return; }
  fetch('/api/friends/requests', {
    method: 'POST',
    headers: Object.assign({ 'Content-Type': 'application/json' }, { Authorization: 'Bearer ' + guestToken }),
    body: JSON.stringify({ target })
  })
    .then(async r => {
      const body = await r.json().catch(() => null);
      if (!r.ok) throw new Error(friendErrorText(body));
      input.value = '';
      setStatusMsg(body && body.message ? body.message : '申请已发送', 'waiting');
      loadFriends();
    })
    .catch(e => setStatusMsg(e.message || '添加失败，请稍后重试', 'error'));
}

/** 同意 / 拒绝收到的申请 */
function respondFriendRequest(id, action) {
  if (!guestToken) return;
  fetch('/api/friends/requests/' + encodeURIComponent(id) + '/' + action, {
    method: 'POST', headers: { Authorization: 'Bearer ' + guestToken }
  })
    .then(async r => {
      if (!r.ok) throw new Error(friendErrorText(await r.json().catch(() => null)));
      if (action === 'accept') setStatusMsg('已添加好友', 'win');
      loadFriends();
    })
    .catch(e => setStatusMsg(e.message || '操作失败', 'error'));
}

/** 撤回我发出的申请 */
function cancelFriendRequest(id) {
  if (!guestToken) return;
  fetch('/api/friends/requests/' + encodeURIComponent(id), {
    method: 'DELETE', headers: { Authorization: 'Bearer ' + guestToken }
  })
    .then(async r => {
      if (!r.ok) throw new Error(friendErrorText(await r.json().catch(() => null)));
      loadFriends();
    })
    .catch(e => setStatusMsg(e.message || '操作失败', 'error'));
}

/** 删除好友（双向移除） */
function removeFriendById(id) {
  if (!guestToken || !confirm('确定删除该好友？双方都会移除这条关系。')) return;
  fetch('/api/friends/' + encodeURIComponent(id), {
    method: 'DELETE', headers: { Authorization: 'Bearer ' + guestToken }
  })
    .then(async r => {
      if (!r.ok) throw new Error(friendErrorText(await r.json().catch(() => null)));
      loadFriends();
    })
    .catch(e => setStatusMsg(e.message || '操作失败', 'error'));
}

/** 一键加入好友所在房间（带玩法类型，服务端校验房间与玩法匹配） */
function joinFriendRoom(roomId, gameType) {
  joinSpecificRoom(roomId, gameType || selectedGameType);
}

function copyFriendCode() {
  const codeEl = document.getElementById('friend-code');
  const code = codeEl ? codeEl.textContent.trim() : '';
  if (!code || code === '—') return;
  const done = () => setStatusMsg('好友码已复制，发给朋友即可互加', 'waiting');
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(code).then(done, () => { fallbackCopy(code); done(); });
  } else {
    fallbackCopy(code);
    done();
  }
}

/** 好友接口业务错误 → 用户可读文案 */
function friendErrorText(body) {
  if (!body) return '操作失败，请稍后重试';
  const messages = {
    NOT_FOUND: '找不到该好友码或用户名',
    CONFLICT: body.message || '请求与当前状态冲突',
    LIMIT: body.message || '操作过于频繁或已达上限',
    INVALID: body.message || '输入无效',
    UNAUTHORIZED: '身份已失效，请重新进入大厅'
  };
  return messages[body.code] || body.message || '操作失败，请稍后重试';
}

/** 大厅可见时每 15 秒刷新好友在线状态；切到后台不请求 */
function startFriendsPolling() {
  setInterval(() => {
    const lobby = document.getElementById('lobby-screen');
    if (!lobby || lobby.style.display === 'none' || document.hidden) return;
    loadFriends();
  }, 15000);
}

/** 好友事件实时提醒：收到 WS 推送立即刷新面板并弹提示（轮询只作兜底） */
function handleFriendEvent(msg) {
  let payload = null;
  try { payload = JSON.parse(msg.data); } catch (_) { return; }
  if (!payload || !payload.kind) return;
  if (payload.kind.startsWith('party_')) {
    // 队伍事件只驱动面板与提示：开局由服务端直接给全队下发 GAME_START，客户端不自行进房。
    loadParty();
    if (payload.kind === 'party_invite') {
      const inviter = payload.name || '好友';
      setStatusMsg('👥 ' + inviter + ' 邀请你组队', 'waiting');
    } else if (payload.kind === 'party_joined') {
      setStatusMsg('👥 ' + (payload.name || '好友') + ' 加入了队伍', 'waiting');
    } else if (payload.kind === 'party_left') {
      setStatusMsg('👥 ' + (payload.name || '好友') + ' 离开了队伍', 'waiting');
    } else if (payload.kind === 'party_left_self') {
      setStatusMsg('已退出队伍', 'waiting');
    } else if (payload.kind === 'party_disbanded') {
      setStatusMsg('队伍已解散', 'waiting');
    }
    return;
  }
  // 面板立即刷新；人在对局中也不打断（面板在大厅，回大厅自然看到新状态）
  loadFriends();
  const who = payload.name || '好友';
  const tips = {
    online: '👥 ' + who + ' 上线了',
    offline: who + ' 离线了',
    request: '👥 ' + who + ' 请求加你为好友，去大厅好友面板处理',
    accepted: '👥 ' + who + ' 同意了你的好友申请'
  };
  const text = tips[payload.kind];
  if (text) setStatusMsg(text, 'waiting');
}

// ============ 好友私聊（DIRECT_MSG，仅好友之间；消息不落盘，刷新页面清空） ============

let dmOpenId = null;
const dmHistory = {};   // identityId -> [{me, text}]
const dmUnread = {};    // identityId -> 未读条数（弹窗没开时累计）

/** 从最近一次好友数据里反查展示名（申请列表里的人也能聊） */
function friendNameOf(id) {
  const data = lastFriendsData;
  if (!data) return '好友';
  const all = [].concat(data.friends || [], data.incoming || [], data.outgoing || []);
  const hit = all.find(x => x.id === id);
  return (hit && hit.name) || '好友';
}

function openFriendChat(id) {
  dmOpenId = id;
  dmUnread[id] = 0;
  if (!dmHistory[id]) dmHistory[id] = [];
  document.getElementById('dm-title').textContent = '与 ' + escHtml(friendNameOf(id)) + ' 私聊';
  document.getElementById('dm-modal').classList.add('show');
  renderDm();
  setTimeout(() => { const input = document.getElementById('dm-input'); if (input) input.focus(); }, 50);
}

function closeDm() {
  dmOpenId = null;
  document.getElementById('dm-modal').classList.remove('show');
}

function renderDm() {
  const box = document.getElementById('dm-messages');
  if (!box || !dmOpenId) return;
  const history = dmHistory[dmOpenId] || [];
  box.innerHTML = history.map(m =>
    '<div class="dm-line ' + (m.me ? 'me' : 'them') + '"><span>' + escHtml(m.text) + '</span></div>'
  ).join('') || '<div class="dm-empty">还没有消息，打个招呼吧～</div>';
  box.scrollTop = box.scrollHeight;
}

function sendDm() {
  const input = document.getElementById('dm-input');
  if (!input || !dmOpenId) return;
  const text = (input.value || '').trim();
  if (!text) return;
  if (!dmHistory[dmOpenId]) dmHistory[dmOpenId] = [];
  dmHistory[dmOpenId].push({ me: true, text });
  renderDm();
  input.value = '';
  send({ type: 'DIRECT_MSG', data: dmOpenId, message: text });
}/** 收到好友私聊：弹窗开着就地渲染，否则累计未读徽标 + 提示一条 */
function handleDirectMsg(msg) {
  const from = msg.playerId;
  const text = msg.message || '';
  if (!from || !text) return;
  if (!dmHistory[from]) dmHistory[from] = [];
  dmHistory[from].push({ me: false, text });
  if (dmOpenId === from) {
    renderDm();
  } else {
    dmUnread[from] = (dmUnread[from] || 0) + 1;
    loadFriends();
    setStatusMsg('💬 ' + (msg.playerName || '好友') + '：' + text.slice(0, 40), 'waiting');
  }
}// ============ 运营治理：举报与管理处置 ============

/** 打开举报弹窗：列出同房间的其他真人玩家 */
function openReport() {
  const targetSel = document.getElementById('report-target');
  if (!targetSel) return;
  const others = Object.values(players).filter(p => p.id !== myId && !p.ai);
  if (!others.length) {
    setStatusMsg('房间里没有可举报的其他玩家', 'waiting');
    return;
  }
  targetSel.innerHTML = others.map(p =>
    '<option value="' + escHtml(p.id) + '">' + escHtml(p.name || '玩家') + '</option>').join('');
  document.getElementById('report-detail').value = '';
  document.getElementById('report-hint').textContent = '恶意举报会被记录，请如实填写。';
  document.getElementById('report-modal').classList.add('show');
}

function closeReport() {
  document.getElementById('report-modal').classList.remove('show');
}

function submitReport() {
  const targetSel = document.getElementById('report-target');
  const reasonSel = document.getElementById('report-reason');
  const detail = document.getElementById('report-detail');
  if (!targetSel || !targetSel.value) return;
  const hint = document.getElementById('report-hint');
  send({
    type: 'REPORT',
    data: targetSel.value,
    action: Number(reasonSel ? reasonSel.value : 4),
    message: detail ? detail.value.trim().slice(0, 300) : ''
  });
  if (hint) hint.textContent = '已提交，正在等待服务端确认…';
}

/** 服务端举报结果的两种形态：房间内确认（GAME_CHAT）或错误提示 */
function handleReportFeedback(msg) {
  const hint = document.getElementById('report-hint');
  const text = msg.message || '';
  if (hint && /举报/.test(text)) hint.textContent = text;
  if (/举报已提交/.test(text)) {
    setTimeout(closeReport, 1200);
  }
}

// ============ 管理后台（?admin=1 + X-Admin-Token） ============

let adminToken = null;

if (new URLSearchParams(location.search).get('admin') === '1') {
  // 令牌不入 URL（会被日志/分享泄露），首次进入用 prompt 输入并只放内存
  adminToken = window.prompt('请输入管理令牌（ADMIN_TOKEN）', '') || null;
  if (adminToken) {
    const entry = document.getElementById('admin-entry');
    if (entry) entry.style.display = 'inline-block';
  }
}

function openAdmin() {
  if (!adminToken) { setStatusMsg('未提供管理令牌', 'error'); return; }
  document.getElementById('admin-modal').classList.add('show');
  loadAdmin();
}

function closeAdmin() {
  document.getElementById('admin-modal').classList.remove('show');
}

function adminFetch(path, options) {
  const opts = Object.assign({}, options || {});
  opts.headers = Object.assign({ 'X-Admin-Token': adminToken || '' }, opts.headers || {});
  return fetch(path, opts).then(async r => {
    let body = null;
    try { body = await r.json(); } catch (_) {}
    return { ok: r.ok, status: r.status, body };
  });
}

function loadAdmin() {
  const statusEl = document.getElementById('admin-status');
  const listEl = document.getElementById('admin-reports');
  if (!listEl) return;
  adminFetch('/api/admin/moderation?limit=100').then(({ ok, status, body }) => {
    if (!ok) {
      statusEl.textContent = (body && body.message) || ('管理接口不可用（' + status + '）');
      listEl.innerHTML = '';
      return;
    }
    const pending = (body.reports || []).filter(r => !r.handled).length;
    statusEl.textContent = '待处理举报 ' + pending + ' 条 · 共 ' + (body.reports || []).length + ' 条记录';
    listEl.innerHTML = (body.reports || []).map(r => {
      const reasonText = ({ abuse: '辱骂', spam: '刷屏', cheating: '作弊', afk: '挂机', other: '其他' })[r.reason] || r.reason;
      const when = new Date(r.createdAt).toLocaleString();
      const actions = r.handled
        ? '<span class="admin-done">已处理：' + escHtml(r.handledNote || '-') + '</span>'
        : '<div class="admin-actions">'
          + '<button class="btn-outline friend-mini" onclick="adminSanction(\'' + r.id + '\',\'mute\')">禁言 24h</button>'
          + '<button class="btn-outline friend-mini" onclick="adminSanction(\'' + r.id + '\',\'ban\')">封禁 7d</button>'
          + '<button class="leave-btn friend-mini" onclick="adminDismiss(\'' + r.id + '\')">忽略</button>'
          + '</div>';
      return '<div class="admin-row ' + (r.handled ? 'handled' : '') + '">'
        + '<div class="admin-row-head"><b>' + escHtml(r.reporterName || '举报人') + '</b> 举报 <b>' + escHtml(r.targetName || '被举报人') + '</b>'
        + '<span class="admin-tag">' + escHtml(reasonText) + '</span><span class="admin-time">' + escHtml(when) + '</span></div>'
        + (r.detail ? '<div class="admin-detail">' + escHtml(r.detail) + '</div>' : '')
        + '<div class="admin-meta">' + escHtml(r.gameType || '') + ' · ' + escHtml(r.roomId || '') + ' · ' + escHtml(r.id) + '</div>'
        + actions + '</div>';
    }).join('') || '<div class="friends-empty">暂无举报记录。</div>';

    const sanctionEl = document.getElementById('admin-sanctions');
    sanctionEl.innerHTML = (body.sanctions || []).map(s => {
      const active = s.active ? '<span class="admin-tag active">生效中</span>' : '<span class="admin-tag">已过期</span>';
      const action = s.action === 'ban' ? '封禁' : '禁言';
      return '<div class="admin-row compact"><div class="admin-row-head"><b>' + escHtml(s.id) + '</b> ' + action + active
        + '<button class="leave-btn friend-mini" onclick="adminLift(\'' + escHtml(s.id) + '\')">撤销</button></div>'
        + (s.reason ? '<div class="admin-detail">' + escHtml(s.reason) + '</div>' : '') + '</div>';
    }).join('') || '<div class="friends-empty">暂无处置记录。</div>';
  });
}

function adminSanction(reportId, action) {
  adminFetch('/api/admin/moderation/reports/' + encodeURIComponent(reportId) + '/sanction', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action, duration: action === 'ban' ? '7d' : '24h', reason: '举报核实' })
  }).then(({ ok, body }) => {
    if (!ok) { setStatusMsg((body && body.message) || '处置失败', 'error'); return; }
    loadAdmin();
  });
}

function adminDismiss(reportId) {
  adminFetch('/api/admin/moderation/reports/' + encodeURIComponent(reportId) + '/dismiss', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ note: '证据不足' })
  }).then(({ ok, body }) => {
    if (!ok) { setStatusMsg((body && body.message) || '操作失败', 'error'); return; }
    loadAdmin();
  });
}

function adminLift(identityId) {
  adminFetch('/api/admin/moderation/sanctions/' + encodeURIComponent(identityId), { method: 'DELETE' })
    .then(({ ok, body }) => {
      if (!ok) { setStatusMsg((body && body.message) || '撤销失败', 'error'); return; }
      loadAdmin();
    });
}

// ============ 运营数据看板（管理员） ============

let opsLoaded = false;
let announceAdminLoaded = false;
let eventsAdminLoaded = false;

function switchAdminTab(tab) {
  const tabs = { reports: 'admin-tab-reports', ops: 'admin-tab-ops', announce: 'admin-tab-announce', events: 'admin-tab-events' };
  const panes = { reports: 'admin-pane-reports', ops: 'admin-pane-ops', announce: 'admin-pane-announce', events: 'admin-pane-events' };
  Object.keys(tabs).forEach(key => {
    document.getElementById(tabs[key]).classList.toggle('active', key === tab);
    document.getElementById(panes[key]).style.display = key === tab ? 'flex' : 'none';
  });
  if (tab === 'ops' && !opsLoaded) loadOps();
  if (tab === 'announce' && !announceAdminLoaded) loadAdminAnnouncements();
  if (tab === 'events' && !eventsAdminLoaded) loadAdminEvents();
}

function loadOps() {
  const statusEl = document.getElementById('ops-status');
  adminFetch('/api/admin/ops?days=14').then(({ ok, status, body }) => {
    if (!ok) {
      statusEl.textContent = (body && body.message) || ('运营数据不可用（' + status + '）');
      return;
    }
    opsLoaded = true;
    const metrics = body.metrics || {};
    const realtime = body.realtime || {};
    const expiry = metrics.retention || {};
    const realtimeText = '此刻在线 ' + (realtime.totalPlayers || 0) + ' 人 · 房间 ' + (realtime.totalRooms || 0)
      + '（对局中 ' + (realtime.playingRooms || 0) + '）';
    const d1 = expiry.d1 == null ? '—' : expiry.d1 + '%';
    const d7 = expiry.d7 == null ? '—' : expiry.d7 + '%';
    statusEl.textContent = realtimeText + ' · 统计窗口 ' + (metrics.from || '') + ' ~ ' + (metrics.to || '');

    document.getElementById('ops-cards').innerHTML = [
      ['今日活跃', metrics.todayActive || 0, '人'],
      ['窗口活跃', metrics.activeUsers || 0, '人'],
      ['真人对局', metrics.totalGames || 0, '局'],
      ['计分局', metrics.rankedGames || 0, '局'],
      ['次日留存', d1, ''],
      ['7 日留存', d7, '']
    ].map(([label, value, unit]) =>
      '<div class="ops-card"><div class="ops-card-value">' + escHtml(String(value)) + '<small>' + unit + '</small></div>'
      + '<div class="ops-card-label">' + escHtml(label) + '</div></div>').join('');

    renderOpsBars('ops-trend', (metrics.trend || []).map(r => ({
      label: String(r.date || '').slice(5), value: r.users || 0, note: (r.games || 0) + ' 局'
    })));
    renderOpsBars('ops-games', (metrics.gameTypes || []).map(r => ({
      label: r.gameType, value: r.games, note: (r.share || 0) + '%'
    })));
    renderOpsBars('ops-hours', (metrics.hourly || []).map(r => ({
      label: r.slot, value: r.games
    })));
    document.getElementById('ops-definitions').innerHTML = (metrics.definitions || [])
      .map(d => '<div class="ops-definition">· ' + escHtml(d) + '</div>').join('');
  });
}

/** 横向条形图：纯 DOM，不引图表库（看板只给管理员看，够用即可） */
function renderOpsBars(containerId, rows) {
  const el = document.getElementById(containerId);
  if (!el) return;
  if (!rows.length) { el.innerHTML = '<div class="friends-empty">暂无数据</div>'; return; }
  const max = Math.max.apply(null, rows.map(r => r.value).concat([1]));
  el.innerHTML = rows.map(r => {
    const pct = Math.round((r.value / max) * 100);
    return '<div class="ops-bar-row"><span class="ops-bar-label">' + escHtml(String(r.label)) + '</span>'
      + '<span class="ops-bar-track"><span class="ops-bar-fill" style="width:' + pct + '%"></span></span>'
      + '<span class="ops-bar-value">' + r.value + (r.note ? ' <small>' + escHtml(r.note) + '</small>' : '') + '</span></div>';
  }).join('');
}

// ============ 运营公告管理（管理员） ============

function announceExpiryText(item) {
  return item.expiresAt > 0 ? '至 ' + new Date(item.expiresAt).toLocaleString() : '永久';
}

function loadAdminAnnouncements() {
  const statusEl = document.getElementById('announce-status');
  adminFetch('/api/admin/announcements').then(({ ok, status, body }) => {
    if (!ok) {
      statusEl.textContent = (body && body.message) || ('公告接口不可用（' + status + '）');
      return;
    }
    announceAdminLoaded = true;
    const list = body.announcements || [];
    const live = list.filter(a => a.active && !a.expired).length;
    statusEl.textContent = '生效中 ' + live + ' 条 · 共 ' + list.length + ' 条记录';
    document.getElementById('admin-announcements').innerHTML = list.map(a => {
      const level = announceLevelMeta(a.level);
      const state = a.expired ? '<span class="admin-tag">已过期</span>'
        : (a.active ? '<span class="admin-tag active">生效中</span>' : '<span class="admin-tag">已下线</span>');
      const toggle = a.active
        ? '<button class="leave-btn friend-mini" onclick="adminToggleAnnouncement(\'' + escHtml(a.id) + '\',false)">下线</button>'
        : '<button class="btn-outline friend-mini" onclick="adminToggleAnnouncement(\'' + escHtml(a.id) + '\',true)">上线</button>';
      return '<div class="admin-row ' + (a.active && !a.expired ? '' : 'handled') + '"'
        + ' data-announce-admin-id="' + escHtml(a.id) + '">'
        + '<div class="admin-row-head"><span class="announce-level">' + level.label + '</span>'
        + '<b>' + escHtml(a.title) + '</b>' + state
        + '<span class="admin-time">' + new Date(a.createdAt).toLocaleString() + '</span></div>'
        + '<div class="admin-detail">' + escHtml(a.body) + '</div>'
        + '<div class="admin-meta">有效期 ' + escHtml(announceExpiryText(a)) + ' · ' + escHtml(a.id) + '</div>'
        + '<div class="admin-actions">' + toggle
        + '<button class="leave-btn friend-mini" onclick="adminDeleteAnnouncement(\'' + escHtml(a.id) + '\')">删除</button>'
        + '</div></div>';
    }).join('') || '<div class="friends-empty">还没有发布过公告。</div>';
  });
}

function publishAnnouncement() {
  const title = document.getElementById('announce-title').value.trim();
  const content = document.getElementById('announce-body').value.trim();
  if (!title || !content) { setStatusMsg('公告标题和内容都不能为空', 'error'); return; }
  adminFetch('/api/admin/announcements', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      title: title,
      body: content,
      level: document.getElementById('announce-level').value,
      ttl: document.getElementById('announce-ttl').value
    })
  }).then(({ ok, body }) => {
    if (!ok) { setStatusMsg((body && body.message) || '发布失败', 'error'); return; }
    document.getElementById('announce-title').value = '';
    document.getElementById('announce-body').value = '';
    loadAdminAnnouncements();
    loadAnnouncements();
    setStatusMsg('公告已发布', 'waiting');
  });
}

function adminToggleAnnouncement(id, active) {
  adminFetch('/api/admin/announcements/' + encodeURIComponent(id), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ active: active })
  }).then(({ ok, body }) => {
    if (!ok) { setStatusMsg((body && body.message) || '操作失败', 'error'); return; }
    loadAdminAnnouncements();
    loadAnnouncements();
  });
}

function adminDeleteAnnouncement(id) {
  if (!window.confirm('删除后不可恢复，确认删除这条公告？')) return;
  adminFetch('/api/admin/announcements/' + encodeURIComponent(id), { method: 'DELETE' })
    .then(({ ok, body }) => {
      if (!ok) { setStatusMsg((body && body.message) || '删除失败', 'error'); return; }
      loadAdminAnnouncements();
      loadAnnouncements();
    });
}

// ============ 限时活动管理（管理员） ============

function loadAdminEvents() {
  const statusEl = document.getElementById('event-admin-status');
  adminFetch('/api/admin/events').then(({ ok, status, body }) => {
    if (!ok) {
      statusEl.textContent = (body && body.message) || ('活动接口不可用（' + status + '）');
      return;
    }
    eventsAdminLoaded = true;
    const list = body.events || [];
    const live = list.filter(e => e.live).length;
    statusEl.textContent = '生效中 ' + live + ' 个 · 共 ' + list.length + ' 个活动';

    const select = document.getElementById('event-game');
    if (select && body.gameTypes && !select.dataset.loaded) {
      const current = select.value || 'ALL';
      select.innerHTML = body.gameTypes.map(t =>
        '<option value="' + escHtml(t.value) + '">' + escHtml(t.label) + '</option>').join('');
      select.value = current;
      select.dataset.loaded = '1';
    }

    document.getElementById('admin-events').innerHTML = list.map(e => {
      const state = e.live ? '<span class="admin-tag active">生效中</span>'
        : (e.ended ? '<span class="admin-tag">已结束</span>' : '<span class="admin-tag">已暂停</span>');
      const toggle = e.ended ? ''
        : (e.active
          ? '<button class="leave-btn friend-mini" onclick="adminToggleEvent(\'' + escHtml(e.id) + '\',false)">暂停</button>'
          : '<button class="btn-outline friend-mini" onclick="adminToggleEvent(\'' + escHtml(e.id) + '\',true)">恢复</button>');
      return '<div class="admin-row ' + (e.live ? '' : 'handled') + '" data-event-admin-id="' + escHtml(e.id) + '">'
        + '<div class="admin-row-head"><b>' + escHtml(e.title) + '</b>' + state
        + '<span class="admin-time">至 ' + escHtml(new Date(e.endsAt).toLocaleString()) + '</span></div>'
        + '<div class="admin-meta">' + escHtml(e.gameLabel || e.gameType)
        + ' · 经验 +' + (e.xpPercent || 0) + '% · ' + escHtml(e.id) + '</div>'
        + '<div class="admin-actions">' + toggle
        + '<button class="leave-btn friend-mini" onclick="adminDeleteEvent(\'' + escHtml(e.id) + '\')">删除</button>'
        + '</div></div>';
    }).join('') || '<div class="friends-empty">还没有创建过活动。</div>';
  });
}

function publishEvent() {
  const title = document.getElementById('event-title').value.trim();
  if (!title) { setStatusMsg('活动标题不能为空', 'error'); return; }
  adminFetch('/api/admin/events', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      title: title,
      gameType: document.getElementById('event-game').value,
      xpPercent: Number(document.getElementById('event-percent').value),
      duration: document.getElementById('event-duration').value
    })
  }).then(({ ok, body }) => {
    if (!ok) { setStatusMsg((body && body.message) || '创建失败', 'error'); return; }
    document.getElementById('event-title').value = '';
    loadAdminEvents();
    loadEvents();
    setStatusMsg('活动已创建', 'waiting');
  });
}

function adminToggleEvent(id, active) {
  adminFetch('/api/admin/events/' + encodeURIComponent(id), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ active: active })
  }).then(({ ok, body }) => {
    if (!ok) { setStatusMsg((body && body.message) || '操作失败', 'error'); return; }
    loadAdminEvents();
    loadEvents();
  });
}

function adminDeleteEvent(id) {
  if (!window.confirm('删除后不可恢复，确认删除这个活动？')) return;
  adminFetch('/api/admin/events/' + encodeURIComponent(id), { method: 'DELETE' })
    .then(({ ok, body }) => {
      if (!ok) { setStatusMsg((body && body.message) || '删除失败', 'error'); return; }
      loadAdminEvents();
      loadEvents();
    });
}

function renderBlackjackPanel() {
  const status = document.getElementById('bj-status');
  const dealer = document.getElementById('bj-dealer');
  const dealerCards = document.getElementById('bj-dealer-cards');
  const playersEl = document.getElementById('bj-players');
  const logEl = document.getElementById('bj-log');
  const hitBtn = document.getElementById('bj-hit');
  const standBtn = document.getElementById('bj-stand');
  const hint = document.getElementById('bj-hint');
  if (!status || !playersEl || !rpgView || !rpgView.players) return;
  const v = rpgView;
  const me = v.players.find(p => p.side === myStone);
  const turnPlayer = v.players.find(p => p.side === v.turn);
  status.textContent = v.over ? '本局结束' : (turnPlayer ? '轮到 ' + turnPlayer.name + ' 行动' : '结算中…');
  dealer.textContent = v.over ? '庄家 ' + v.dealerTotal + ' 点' : '庄家明牌 ' + v.dealerVisibleTotal + ' 点';
  const cardHtml = c => c.hidden
    ? '<div class="bj-card hidden">?</div>'
    : '<div class="bj-card ' + (c.suit === 1 || c.suit === 2 ? 'red' : '') + '"><span>' + escHtml(c.label) + '</span><small>' + escHtml(c.suitLabel) + '</small></div>';
  dealerCards.innerHTML = (v.dealer || []).map(cardHtml).join('');
  playersEl.innerHTML = (v.players || []).map(p => {
    const cards = (p.cards || []).map(cardHtml).join('');
    let badge = '';
    if (p.blackjack) badge = ' · 黑杰克';
    else if (p.busted) badge = ' · 爆牌';
    else if (p.stood) badge = ' · 已停牌';
    const result = v.over && p.result != null ? (p.result > 0 ? ' · 胜' : p.result === 0 ? ' · 平' : ' · 负') : '';
    return '<div class="bj-player ' + (p.side === myStone ? 'is-you' : '') + ' ' + (p.busted ? 'is-busted' : '') + '">'
      + '<div class="bj-player-head"><b>' + escHtml(p.name) + '</b><span>' + p.total + ' 点' + badge + result + '</span></div>'
      + '<div class="bj-hand">' + cards + '</div></div>';
  }).join('');
  const myTurn = !v.over && v.turn === myStone && myStone > 0;
  if (hitBtn) hitBtn.disabled = !myTurn;
  if (standBtn) standBtn.disabled = !myTurn;
  if (hint) hint.textContent = v.over ? '' : myTurn ? '要牌可多次，点停牌结束本回合' : '等待其他玩家';
  logEl.innerHTML = (v.log || []).map(line => '<div>' + escHtml(line) + '</div>').join('');
  logEl.scrollTop = logEl.scrollHeight;
}

function loadPlayerProgress() {
  const grid = document.getElementById('achievement-grid');
  if (!grid || !guestToken) return Promise.resolve();
  return fetch('/api/me/progress', { headers: { Authorization: 'Bearer ' + guestToken } })
    .then(r => r.ok ? r.json() : Promise.reject(new Error('unauthorized')))
    .then(p => {
      progressSnapshot = progressDigest(p);
      lastProgress = p;
      const set = (id, text) => { const el = document.getElementById(id); if (el) el.textContent = text; };
      set('level-badge', 'Lv.' + p.level);
      set('level-title', p.title || '新手');
      set('level-xp', (p.levelXp || 0) + ' / ' + (p.levelXpGoal || 100) + ' XP');
      set('level-meta', '成就 ' + (p.achievementsUnlocked || 0) + ' / ' + (p.achievementsTotal || 0)
        + ' · 对局 ' + (p.totalGames || 0) + ' · 玩法 ' + (p.gameTypes || 0));
      const fill = document.getElementById('level-bar-fill');
      if (fill) fill.style.width = Math.max(0, Math.min(100, p.levelProgress || 0)) + '%';
      grid.innerHTML = (p.achievements || []).slice(0, 6).map(a =>
        '<div class="achievement ' + (a.unlocked ? 'unlocked' : '') + '" title="' + escHtml(a.desc || '') + '">'
        + '<span class="achievement-icon">' + (a.unlocked ? '★' : '☆') + '</span>'
        + '<span class="achievement-name">' + escHtml(a.name || '') + '</span>'
        + '<span class="achievement-progress">' + (a.progress || 0) + '/' + (a.goal || 1) + '</span></div>'
      ).join('');
      const wall = document.getElementById('achv-modal');
      if (wall && wall.classList.contains('show')) renderAchievementWall();
    })
    .catch(() => {});
}

function openAchievementWall() {
  const modal = document.getElementById('achv-modal');
  if (!modal) return;
  modal.classList.add('show');
  renderAchievementWall();
  if (guestToken) loadPlayerProgress();
}

function closeAchievementWall() {
  const modal = document.getElementById('achv-modal');
  if (modal) modal.classList.remove('show');
}

function setAchievementFilter(filter) {
  achievementFilter = filter;
  document.querySelectorAll('.achv-tab').forEach(btn => {
    btn.classList.toggle('active', btn.getAttribute('data-filter') === filter);
  });
  renderAchievementWall();
}

function renderAchievementWall() {
  const wall = document.getElementById('achv-wall');
  if (!wall) return;
  const p = lastProgress;
  if (!p) {
    wall.innerHTML = '<div class="achv-empty">正在加载成就…</div>';
    return;
  }
  const all = p.achievements || [];
  const unlockedCount = all.filter(a => a.unlocked).length;
  const sub = document.getElementById('achv-sub');
  if (sub) {
    sub.textContent = '已解锁 ' + unlockedCount + ' / ' + all.length
      + ' · ' + (all.length ? Math.round(unlockedCount * 100 / all.length) : 0) + '%'
      + ' · 每种玩法首胜都是一个收集目标';
  }
  const list = all.filter(a => achievementFilter === 'all'
    || (achievementFilter === 'unlocked' ? !!a.unlocked : !a.unlocked));
  if (!list.length) {
    wall.innerHTML = '<div class="achv-empty">'
      + (achievementFilter === 'unlocked' ? '还没有已解锁成就，先打一局吧' : '没有符合条件的成就')
      + '</div>';
    return;
  }
  wall.innerHTML = list.map(a => {
    const goal = a.goal || 1;
    const progress = a.progress || 0;
    const pct = Math.max(0, Math.min(100, Math.round(progress * 100 / goal)));
    return '<div class="achv-card ' + (a.unlocked ? 'unlocked' : '') + '">'
      + '<div class="achv-card-head"><span class="achv-star">' + (a.unlocked ? '★' : '☆') + '</span>'
      + '<span class="achv-card-name">' + escHtml(a.name || '') + '</span>'
      + '<span class="achv-card-progress">' + progress + '/' + goal + '</span></div>'
      + '<div class="achv-card-desc">' + escHtml(a.desc || '') + '</div>'
      + '<div class="achv-bar"><div class="achv-bar-fill" style="width:' + pct + '%"></div></div></div>';
  }).join('');
}

/** 套牌筛选只属于卡牌对战；切到其他页签时收起并复位，避免隐藏条件继续过滤 */
function syncRecordDeckFilter() {
  const box = document.getElementById('recent-deck-filter');
  if (!box) return;
  const isCard = selectedLbType === 'CARD';
  if (!isCard) selectedRecordDeck = '';
  box.style.display = isCard ? 'flex' : 'none';
  const select = document.getElementById('recent-deck-select');
  if (select && select.value !== selectedRecordDeck) select.value = selectedRecordDeck;
}

function onRecentDeckChange(deck) {
  selectedRecordDeck = deck || '';
  loadRecentGames();
}

/** 加载最近对局列表（不带棋谱，点开才请求详情） */
function loadRecentGames() {
  syncRecordDeckFilter();
  let url = '/api/games?limit=15&gameType=' + encodeURIComponent(selectedLbType);
  // 服务端先按套牌过滤再 limit；前端不做二次截断，否则会漏掉较旧但匹配的记录
  if (selectedLbType === 'CARD' && selectedRecordDeck) {
    url += '&deck=' + encodeURIComponent(selectedRecordDeck);
  }
  fetch(url)
    .then(r => r.json())
    .then(list => {
      const el = document.getElementById('recent-games-list');
      if (!el) return;
      if (!Array.isArray(list) || list.length === 0) {
        // 区分「当前游戏没记录」和「当前套牌没记录」，让筛选结果可解释
        const deckLabel = selectedRecordDeck
          ? ({ BALANCED: '均衡', AGGRO: '快攻', CONTROL: '控制', CUSTOM: '自定义' }[selectedRecordDeck] || selectedRecordDeck)
          : '';
        el.innerHTML = '<div class="record-empty">' + escHtml(gameTypeLabel(selectedLbType))
          + (deckLabel ? ' · ' + escHtml(deckLabel) + '套牌' : '') + '暂无对局记录</div>';
        return;
      }
      el.innerHTML = list.map(g => {
        const ps = g.participants || [];
        const names = ps.map(p => escHtml(p.name) + (p.ai ? '(AI)' : '')).join(' vs ');
        const winner = g.winnerName ? escHtml(g.winnerName) + ' 胜' : '平局';
        const tag = g.ranked ? '<span class="record-tag ranked">计分</span>'
                             : '<span class="record-tag">练习</span>';
        // 卡牌对局补充双方套牌：记录页看不出「打的是什么流派」时，复盘价值大打折扣
        const decks = ps.filter(p => p.deckLabel)
          .map(p => `${escHtml(p.name)} ${escHtml(p.deckLabel)}`).join(' · ');
        return `<div class="record-row" onclick="openRecordReplay('${g.id}', '${g.gameType}')">
          <div class="record-row-top">
            <span class="record-row-name">${names || '—'}</span>
            <span class="record-row-time">${formatStamp(g.endedAt)}</span>
          </div>
          <div class="record-row-bottom">
            <span>${winner} · ${g.moveCount} 手</span>
            ${tag}
          </div>
          ${decks ? `<div class="record-row-decks">套牌：${decks}</div>` : ''}
        </div>`;
      }).join('');
    })
    .catch(() => {
      const el = document.getElementById('recent-games-list');
      if (el) el.innerHTML = '<div class="record-empty">加载失败</div>';
    });
}

/** 打开某局的独立复盘（拉详情棋谱，不影响当前对局棋盘） */
function openRecordReplay(id, gameType) {
  fetch('/api/games/' + encodeURIComponent(id))
    .then(r => {
      if (!r.ok) throw new Error('not found');
      return r.json();
    })
    .then(rec => {
      rmMoves = rec.moveHistory || [];
      rmEffects = rec.moveEffects || [];
      rmGameType = rec.gameType || gameType || 'GOMOKU';
      rmShareId = /^[A-Za-z0-9-]+$/.test(String(id)) ? id : null;
      const shareBtn = document.getElementById('rm-share');
      if (shareBtn) shareBtn.style.display = rmShareId ? '' : 'none';

      if (rmMoves.length === 0) {
        alert('该局棋谱未存档（手数过多或已过期）');
        return;
      }

      // 标题与副标题
      const title = document.getElementById('rm-title');
      const sub = document.getElementById('rm-sub');
      if (title) title.textContent = gameTypeLabel(rmGameType) + ' 复盘';
      if (sub) {
        const ps = rec.participants || [];
        const lines = ps.map(p => {
          const delta = p.eloChange ? `（${p.eloChange > 0 ? '+' : ''}${p.eloChange}）` : '';
          const deck = p.deckLabel ? ` · ${escHtml(p.deckLabel)}套牌` : '';
          return `${escHtml(p.name)}${p.ai ? '(AI)' : ''} ${p.result}${delta}${deck}`;
        });
        // 自定义套牌的配比明细单独一行：预组的风格从名字就能看出来，
        // 自定义必须列出配比才知道「他到底带了多少张火球」。
        const custom = ps.filter(p => p.deckSummary)
          .map(p => `${escHtml(p.name)}：${escHtml(p.deckSummary)}`).join('<br>');
        sub.innerHTML = `${formatStamp(rec.endedAt)} · ${rec.moveCount} 手 · ${escHtml(rec.reason || '')}`
          + (lines.length ? '<br>' + lines.join('  ·  ') : '')
          + (custom ? `<div class="rm-deck-summary">${custom}</div>` : '');
      }

      // 画布尺寸按游戏模块设置，避免不同游戏棋盘尺寸串味。
      // 注意：交叉点类游戏（五子棋/围棋/黑白棋）提供 canvasSize()，
      // 而象棋/四子棋提供 canvasWidth()/canvasHeight()，两套接口都要兼容，
      // 否则 replays 会因为「canvasSize 不是函数」抛异常、整个弹窗打不开。
      const mc = window.GameModules[rmGameType];
      const cvs = document.getElementById('rm-canvas');
      const rpgLogEl = document.getElementById('rm-rpg-log');
      const isHtmlModule = !!(mc && mc.html);
      // 非棋盘玩法没有盘面：隐藏画布、改用文字战报
      if (cvs) cvs.style.display = isHtmlModule ? 'none' : '';
      if (rpgLogEl) rpgLogEl.style.display = isHtmlModule ? 'block' : 'none';
      if (mc && cvs && !isHtmlModule) {
        const w = mc.canvasWidth ? mc.canvasWidth() : (mc.canvasSize ? mc.canvasSize() : 0);
        const h = mc.canvasHeight ? mc.canvasHeight() : (mc.canvasSize ? mc.canvasSize() : 0);
        if (w > 0) cvs.width = w;
        if (h > 0) cvs.height = h;
      }

      // 文字战报没有盘面可看，从 0 手开始等于打开一个空面板。
      // 所以非棋盘玩法直接停在最后一手（看完整战报），用户可以往回拖。
      rmIndex = isHtmlModule ? rmMoves.length : 0;
      rmPlaying = false;
      if (rmTimer) { clearInterval(rmTimer); rmTimer = null; }
      const btn = document.getElementById('rm-play-btn');
      if (btn) btn.textContent = '▶';

      const slider = document.getElementById('rm-slider');
      if (slider) {
        slider.max = rmMoves.length;
        slider.value = rmIndex;
      }
      const stepText = document.getElementById('rm-step-text');
      if (stepText) stepText.textContent = rmIndex + '/' + rmMoves.length;

      document.getElementById('replay-modal').classList.add('show');
      rmDraw();
    })
    .catch(() => alert('对局记录加载失败'));
}

function closeRecordReplay() {
  rmPlaying = false;
  if (rmTimer) { clearInterval(rmTimer); rmTimer = null; }
  document.getElementById('replay-modal').classList.remove('show');
  rmShareId = null;
  const shareBtn = document.getElementById('rm-share');
  if (shareBtn) shareBtn.style.display = 'none';
}

/** 分享复盘链接：任何拿到链接的人都能打开这局回放（记录为公开只读） */
function copyReplayShare() {
  if (!rmShareId) return;
  const url = location.origin + location.pathname + '?replay=' + encodeURIComponent(rmShareId);
  const text = '来看这局的复盘：' + url;
  const done = function () { setStatusMsg('复盘链接已复制，发给朋友即可', 'waiting'); };
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(done, function () { fallbackCopy(text); done(); });
  } else {
    fallbackCopy(text);
    done();
  }
}

/** 把各玩法的历史编码翻译成可读行动（新玩法不再显示「行动 0」） */
function replayActionText(type, mv) {
  if (!mv) return '';
  if (type === 'HIGH_CARD') return mv[1] < 0 ? '退出本局' : '暗选 ' + mv[1] + '（第 ' + mv[2] + ' 轮）';
  if (type === 'DICE_RACE') {
    const names = ['稳健', '冒险', '追击'];
    const choice = names[mv[1]] || ('策略 ' + mv[1]);
    const score = mv[2] > 0 ? '，本轮 +' + mv[2] : '';
    return choice + score + '（总分 ' + (mv[3] || 0) + '）';
  }
  if (type === 'GEM_AUCTION') {
    const gems = mv[2] > 0 ? '，拿下 ' + mv[2] + ' 分宝石' : '';
    return '出价 ' + mv[1] + ' 金币' + gems + '（余 ' + (mv[3] || 0) + ' 金币）';
  }
  if (type === 'BLACKJACK') {
    if (mv[1] === 0) {
      const ranks = ['A', '2', '3', '4', '5', '6', '7', '8', '9', '10', 'J', 'Q', 'K'];
      const suits = ['♠', '♥', '♦', '♣'];
      return '要牌 ' + (ranks[(mv[2] || 1) - 1] || '?') + (suits[mv[3]] || '') + '（' + (mv[4] || 0) + ' 点）';
    }
    if (mv[1] === 1) return '停牌（' + (mv[4] || 0) + ' 点）';
    return '退出本局';
  }
  return '';
}

/** 按 rmIndex 重建盘面 */
function rmDraw() {
  const m = window.GameModules[rmGameType];
  const cvs = document.getElementById('rm-canvas');
  if (!m || !cvs) return;

  // 非棋盘玩法：用文字战报代替盘面，{side, action, value} 直接翻译成人话
  if (m.html) {
    const logEl = document.getElementById('rm-rpg-log');
    if (logEl) {
      const labels = m.actionLabels || [];
      const sides = m.sides || [];
      const lines = [];
      for (let i = 0; i < rmIndex; i++) {
        const mv = rmMoves[i];
        const sideName = sides[mv[0] - 1] ? sides[mv[0] - 1].label + '方' : ('第' + mv[0] + '方');
        const custom = replayActionText(rmGameType, mv);
        const actionName = custom || labels[mv[1]] || ('行动 ' + mv[1]);
        const extra = (!custom && mv[2] > 0) ? '（' + mv[2] + '）' : '';
        lines.push('<div>' + (i + 1) + '. ' + escHtml(sideName) + ' ' + escHtml(actionName) + escHtml(extra) + '</div>');
      }
      logEl.innerHTML = lines.join('') || '<div>还没有可回放的行动</div>';
      logEl.scrollTop = logEl.scrollHeight;
    }
    const slider0 = document.getElementById('rm-slider');
    if (slider0) slider0.value = rmIndex;
    const stepText0 = document.getElementById('rm-step-text');
    if (stepText0) stepText0.textContent = rmIndex + '/' + rmMoves.length;
    return;
  }

  const c2d = cvs.getContext('2d');

  let b = m.initialBoard();
  let last = null;
  for (let i = 0; i < rmIndex; i++) {
    const mv = rmMoves[i];
    if (mv[0] < 0) continue;          // 停一手
    if (!m.usePieceValues) {
      b[mv[0]][mv[1]] = mv[2];
    }
    const effects = rmEffects[i];
    if (effects && effects.length) {
      m.applyEffects(b, effects);
    }
    last = [mv[0], mv[1]];
  }

  m.draw(c2d, b, { lastMove: last, winLine: null, myStone: 0, currentTurn: 0, hint: false });

  const slider = document.getElementById('rm-slider');
  if (slider) slider.value = rmIndex;
  const stepText = document.getElementById('rm-step-text');
  if (stepText) stepText.textContent = rmIndex + '/' + rmMoves.length;
}

function rmStep(dir) {
  rmIndex = Math.max(0, Math.min(rmMoves.length, rmIndex + dir));
  rmDraw();
}

function rmSeek(val) {
  rmIndex = parseInt(val, 10) || 0;
  rmDraw();
}

function rmTogglePlay() {
  const btn = document.getElementById('rm-play-btn');
  if (rmPlaying) {
    rmPlaying = false;
    if (rmTimer) { clearInterval(rmTimer); rmTimer = null; }
    if (btn) btn.textContent = '▶';
    return;
  }
  if (rmIndex >= rmMoves.length) {
    rmIndex = 0;
    rmDraw();
  }
  rmPlaying = true;
  if (btn) btn.textContent = '⏸';
  rmTimer = setInterval(() => {
    if (rmIndex >= rmMoves.length) {
      rmTogglePlay();
      return;
    }
    rmIndex++;
    rmDraw();
    playStoneSound();
  }, RM_SPEED);
}

// ============================================================
// 排行榜（分游戏页签）
// ============================================================
function buildLbTabs() {
  const tabs = document.getElementById('lb-tabs');
  if (!tabs) return;
  tabs.innerHTML = '';
  Object.keys(GameModules).forEach(type => {
    const btn = document.createElement('button');
    btn.className = 'lb-tab' + (type === selectedLbType ? ' selected' : '');
    btn.textContent = GameModules[type].name;
    btn.onclick = function() {
      selectedLbType = type;
      syncRecordDeckFilter();
      buildLbTabs();
      loadLeaderboard();
      // 战绩与对局列表跟着排行榜页签走，保持「当前看的是哪个游戏」一致
      loadMyStats();
      loadRecentGames();
    };
    tabs.appendChild(btn);
  });
}

let leaderboardMode = 'elo'; // elo = 分游戏总榜；weekly = 近 7 天活跃榜；season = 本月赛季段位

/** 三种榜单循环切换：ELO 总榜 → 本周活跃 → 赛季段位 */
function toggleLeaderboardMode() {
  leaderboardMode = leaderboardMode === 'elo' ? 'weekly' : (leaderboardMode === 'weekly' ? 'season' : 'elo');
  const btn = document.getElementById('lb-mode-toggle');
  if (btn) btn.textContent = leaderboardMode === 'elo' ? '本周活跃' : (leaderboardMode === 'weekly' ? '赛季段位' : 'ELO 总榜');
  const tabs = document.getElementById('lb-tabs');
  // 赛季榜是全玩法汇总，与「分游戏页签」无关，故淡化页签
  if (tabs) tabs.style.opacity = leaderboardMode === 'elo' ? '' : '0.45';
  const banner = document.getElementById('season-banner');
  if (banner) banner.hidden = leaderboardMode !== 'season';
  loadLeaderboard();
}

function loadLeaderboard() {
  if (leaderboardMode === 'weekly') {
    renderWeeklyLeaderboard();
    return;
  }
  if (leaderboardMode === 'season') {
    renderSeasonLeaderboard();
    return;
  }
  renderEloLeaderboard();
}

function renderWeeklyLeaderboard() {
  fetch('/api/leaderboard/weekly?limit=10')
    .then(r => r.json())
    .then(list => {
      const el = document.getElementById('leaderboard-list');
      if (!el) return;
      if (!Array.isArray(list) || list.length === 0) {
        el.textContent = '本周暂无真人活跃数据';
        return;
      }
      el.innerHTML = list.map((item, i) =>
        `<div class="lb-row">
          <span class="lb-rank">${i + 1}</span>
          <span class="lb-name">${escHtml(item.name || '游客')}</span>
          <span class="lb-elo">${item.wins}胜 / ${item.games}局</span>
        </div>`
      ).join('');
    })
    .catch(() => {});
}

function renderEloLeaderboard() {
  fetch('/api/leaderboard?gameType=' + selectedLbType)
    .then(r => r.text())
    .then(jsonStr => {
      try {
        const list = JSON.parse(jsonStr);
        const el = document.getElementById('leaderboard-list');
        if (!el) return;
        if (list.length === 0) {
          el.textContent = '暂无数据';
          return;
        }
        el.innerHTML = list.map((item, i) =>
          `<div class="lb-row">
            <span class="lb-rank">${i + 1}</span>
            <span class="lb-name">${escHtml(item.name)}</span>
            <span class="lb-elo">${item.elo}</span>
          </div>`
        ).join('');
      } catch(e) {}
    })
    .catch(() => {});
}

function renderSeasonLeaderboard() {
  const headers = guestToken ? { Authorization: 'Bearer ' + guestToken } : {};
  fetch('/api/season?limit=10', { headers })
    .then(r => r.json())
    .then(s => {
      const banner = document.getElementById('season-banner');
      const me = s.me || {};
      if (banner) {
        banner.innerHTML = '<div class="season-name">' + escHtml(s.label || '赛季') + '</div>'
          + '<div class="season-sub">剩余 ' + (s.daysLeft || 0) + ' 天 · 我：'
          + escHtml(me.tier || '青铜') + ' ' + (me.points || 0) + ' 分</div>'
          + '<div class="season-bar"><div class="season-bar-fill" style="width:' + (me.tierProgress || 0) + '%"></div></div>'
          + '<div class="season-titles" id="season-titles"></div>';
      }
      // 月度结算固化的称号：长期收藏品，激励玩家每个赛季都回来打
      if (guestToken) {
        fetch('/api/season/history?limit=6', { headers })
          .then(r => r.ok ? r.json() : Promise.reject(new Error('unauthorized')))
          .then(data => {
            const box = document.getElementById('season-titles');
            if (!box) return;
            const list = data.history || [];
            if (list.length === 0) { box.innerHTML = '<span class="season-title-empty">赢下赛季，解锁专属称号</span>'; return; }
            box.innerHTML = '<span class="season-title-label">赛季称号</span>' + list.map(h =>
              '<span class="season-title-chip">' + escHtml(h.tier || '青铜') + ' · ' + escHtml(h.seasonId || '') + '</span>'
            ).join('');
          })
          .catch(() => {});
      }
      const el = document.getElementById('leaderboard-list');
      if (!el) return;
      const list = s.leaderboard || [];
      if (list.length === 0) {
        el.textContent = '本赛季暂无真人战绩，来抢第一！';
        return;
      }
      el.innerHTML = list.map((item, i) =>
        `<div class="lb-row">
          <span class="lb-rank">${i + 1}</span>
          <span class="lb-name">${escHtml(item.name || '游客')} <span class="lb-tier">${escHtml(item.tier || '')}</span></span>
          <span class="lb-elo">${item.points} 分</span>
        </div>`
      ).join('');
    })
    .catch(() => {});
}

// ============================================================
// 初始化
// ============================================================
initGameModule('GOMOKU');
drawBoard();

// 回车加入
document.getElementById('player-name').addEventListener('keydown', e => {
  if (e.key === 'Enter') joinGame();
});

// 加载排行榜 / 战绩 / 最近对局
buildLbTabs();
loadLeaderboard();
loadMyStats();
loadRecentGames();
// 关闭标签页后重新打开：提示用户可以直接恢复上一局座位
try {
  if (localStorage.getItem(SESSION_BACKUP_KEY)) {
    const hintEl = document.getElementById('recovery-hint');
    if (hintEl) {
      hintEl.textContent = '检测到未结束的对局，进入大厅后自动恢复座位';
      hintEl.style.display = '';
    }
  }
} catch (_) {}

// 分享落地页：/?replay=<id> 直接打开对应复盘
try {
  const replayParam = new URLSearchParams(location.search).get('replay');
  if (replayParam && /^[A-Za-z0-9-]+$/.test(replayParam)) openRecordReplay(replayParam);
} catch (_) {}

setInterval(loadLeaderboard, 30000); // 30 秒刷新
setInterval(loadOnlineStats, 30000);
setInterval(function () {
  // 大厅看等待人数、等待房中看同玩法还有谁在等；对局中不必轮询
  if (gameState === 'LOBBY' || gameState === 'WAITING') loadMatchOverview();
}, 10000);
setInterval(function () {
  // 大厅可见时才刷新战绩，避免在棋局中无谓请求
  if (gameState === 'LOBBY') {
    loadMyStats();
    loadRecentGames();
  }
}, 30000);
setInterval(function () {
  // 公告变化不频繁，一分钟拉一次即可；局中不拉
  if (gameState === 'LOBBY') loadAnnouncements();
}, 60000);
setInterval(function () {
  // 活动可能被管理员随时暂停/结束，大厅保持一分钟内的同步
  if (gameState === 'LOBBY') loadEvents();
}, 60000);


// 邀请链接：/?room=ABC123 → 预填房号，登录成功后自动进房
(function () {
  try {
    const params = new URLSearchParams(location.search);
    const type = params.get('game');
    if (type && GameModules[type]) selectedGameType = type;
    const code = normalizeRoomCode(params.get('room') || '');
    if (!code) return;
    pendingJoinCode = code;
    const input = document.getElementById('join-code');
    if (input) input.value = code.replace(/^ROOM-/, '');
  } catch (e) {
    // 旧浏览器不支持 URLSearchParams：忽略，不影响手动输入
  }
})();


function renderUnoPanel() {
  const topEl = document.getElementById('uno-top'), playersEl = document.getElementById('uno-players'), handEl = document.getElementById('uno-hand'), logEl = document.getElementById('uno-log'), drawBtn = document.getElementById('uno-draw'), drawEndBtn = document.getElementById('uno-draw-end'), catchBtn = document.getElementById('uno-catch');
  if (!topEl || !playersEl || !handEl || !logEl) return;
  const v = rpgView;
  if (!v || !v.players) { topEl.textContent = '等待开始…'; playersEl.innerHTML = ''; handEl.innerHTML = ''; logEl.innerHTML = ''; return; }
  const mine = v.players.find(p => p.side === myStone);
  const myTurn = gameState === 'PLAYING' && !v.over && currentTurn === myStone && myId !== 'SPECTATOR';
  const unoPending = v.unoPendingSide === myStone;
  const drawnPending = v.drawnPendingSide === myStone;
  let statusText;
  if (drawnPending) statusText = '摸到了可出的牌：请选择立即打出或结束摸牌';
  else if (unoPending) statusText = '你只剩 1 张牌，请喊 UNO';
  else if (v.unoCatchable && v.unoPendingSide !== myStone) statusText = '有人忘了喊 UNO，可以抓牌';
  else if (v.over) statusText = '本局结束';
  else statusText = (myTurn ? '你的回合' : '轮到 ' + ((v.players.find(p => p.side === v.turn) || {}).name || ('第 ' + v.turn + ' 方')))
    + ' · 顶牌：' + (v.top?.label || '—')
    + (v.pendingDraw ? ' · 需摸 ' + v.pendingDraw + ' 张' : '')
    + (v.discardLeft ? ' · 弃牌 ' + v.discardLeft : '')
    + (v.direction < 0 ? ' · 逆时针' : ' · 顺时针');
  topEl.textContent = statusText;
  playersEl.innerHTML = v.players.map(p => '<span class="score-chip">' + escHtml(p.name) + ' · 手牌 ' + p.handCount + (p.score ? ' · ' + p.score + '分' : '') + (p.side === v.turn && !v.over ? ' · 行动中' : '') + '</span>').join('');
  const hand = (mine && mine.hand) || [];
  handEl.innerHTML = hand.map(c => '<button class="number-card" data-uno-index="' + c.index + '" ' + ((!myTurn || !c.playable) ? 'disabled' : '') + '><span>' + escHtml(c.label) + '</span></button>').join('');
  handEl.querySelectorAll('[data-uno-index]').forEach(el => el.addEventListener('click', () => { const idx = Number(el.dataset.unoIndex); const card = hand.find(c => c.index === idx); if (card && card.color < 0) { const chosen = Number(window.prompt(card.value === 14 ? '万能 +4 选择颜色：0 红 / 1 黄 / 2 绿 / 3 蓝' : '万能牌选择颜色：0 红 / 1 黄 / 2 绿 / 3 蓝', String(v.activeColor || 0))); if (chosen >= 0 && chosen < 4) send({ type: 'MOVE', action: idx, targetSide: 1, targetIndex: chosen, moveSeq }); } else if (card && card.value === 7) { const target = Number(window.prompt('7 交换手牌：输入目标座位号（1-' + v.players.length + '）', '2')); if (target >= 1 && target <= v.players.length && target !== myStone) send({ type: 'MOVE', action: idx, targetSide: 2, targetIndex: target, moveSeq }); } else sendAction(idx); }));
  if (drawBtn) drawBtn.disabled = !myTurn || unoPending || drawnPending || !!(mine && mine.hand && mine.hand.some(c => c.playable));
  if (drawEndBtn) drawEndBtn.disabled = !drawnPending;
  const unoButton = document.getElementById('uno-call'); if (unoButton) unoButton.disabled = !unoPending;
  if (catchBtn) catchBtn.disabled = !v.unoCatchable || unoPending || v.over || myId === 'SPECTATOR';
  logEl.innerHTML = (v.log || []).map(line => '<div>' + escHtml(line) + '</div>').join('');
}
function renderHighCardPanel() {
  const panel = document.getElementById('high-card-panel');
  if (!rpgView) { panel.innerHTML = '<div class="table-intro">♠<h2>比大小</h2><p>2–8 人 · 同时暗选 · 五轮定胜负</p><p>邀请朋友，或添加电脑填满座位。</p></div>'; return; }
  const view = rpgView;
  const me = view.players.find(p => p.side === myStone);
  panel.innerHTML = `<div class="table-heading"><span>THE CARD TABLE</span><h2>比大小 <small>第 ${view.round} / 5 轮</small></h2><p>${view.roundPoints === 2 ? '决胜轮：最大牌得 2 分 · 并列均得分' : '本轮最大牌得 1 分 · 并列均得分'}</p></div>
    <div class="table-seats">${view.players.map(p => `<div class="table-seat ${p.side === myStone ? 'is-you' : ''} ${p.active ? '' : 'is-out'}"><span>${escHtml(p.name)}</span><strong>${p.score}<small> 分</small></strong><span>${p.streak ? '连击 ' + p.streak : '暂无连击'}</span><span>${!p.active ? '已退出' : p.answered ? '已暗选' : '正在选牌'}</span>${p.lastChoice > 0 ? '<span>上轮 ' + p.lastChoice + (p.lastAce ? ' · 王牌' : '') + '</span>' : ''}</div>`).join('')}</div>
    <div class="hand-caption">${!me ? '观战中 · 隐藏选择在本轮结束后揭晓' : !me.active ? '你已退出本局，可以继续观战' : me.answered ? '已提交，等待其他玩家' : '选择一张牌，本轮结束后一起揭晓'}</div>
    <div class="high-card-hand">${(me?.hand || []).map(card => `<button class="number-card" data-card="${card}" ${gameState !== 'PLAYING' || me.answered || !me.active ? 'disabled' : ''} aria-label="出牌 ${card}"><span>♠</span>${card}</button>`).join('')}</div>
    ${me && me.aceAvailable && gameState === 'PLAYING' && !me.answered ? '<button class="btn-endturn" id="high-card-ace">使用王牌（本局一次，牌值翻倍）</button>' : ''}
    <div class="table-log">${(view.log || []).map(line => '<p>' + escHtml(line) + '</p>').join('')}</div>`;
  panel.querySelectorAll('[data-card]').forEach(button => button.onclick = () => send({ type: 'MOVE', action: Number(button.dataset.card) }));
  const aceButton = panel.querySelector('#high-card-ace');
  if (aceButton) aceButton.onclick = () => {
    const card = Number(window.prompt('选择要翻倍的手牌（1-10）：', '10'));
    if (card >= 1 && card <= 10 && me.hand.includes(card)) send({ type: 'MOVE', action: 100, targetSide: 1, targetIndex: card, moveSeq });
  };
}

document.getElementById('player-name').value = readPreference('gomoku_name', '');
restoreAccount();
startFriendsPolling();
const soundButton = document.getElementById('sound-toggle');
soundButton.textContent = '音效 ' + (soundEnabled ? '开' : '关');
soundButton.setAttribute('aria-pressed', String(soundEnabled));
document.addEventListener('pointerdown', () => { if (audioCtx?.state === 'suspended') audioCtx.resume(); }, { passive: true });

let coachAvailable = false;
fetch('/api/coach').then(r => r.json()).then(data => {
  coachAvailable = data.available;
  document.getElementById('coach-status').textContent = data.available
    ? '点击后，将当前可见局势发送给管理员配置的模型服务。' : '管理员尚未启用大模型讲解。';
  document.getElementById('coach-button').disabled = !data.available;
}).catch(() => { document.getElementById('coach-status').textContent = '导师服务暂不可用'; });
async function requestCoach() {
  const button = document.getElementById('coach-button');
  const answer = document.getElementById('coach-answer');
  if (!mySessionId) { answer.textContent = '请先加入房间'; return; }
  button.disabled = true; answer.textContent = '正在分析当前可见局势…';
  try {
    const response = await fetch('/api/coach', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sessionId: mySessionId }) });
    const data = await response.json(); answer.textContent = data.message;
  } catch (_) { answer.textContent = '讲解暂不可用，请稍后重试'; }
  finally { button.disabled = !coachAvailable; }
}










