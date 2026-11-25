package com.gomoku.game;

import com.gomoku.dto.*;
import com.gomoku.model.GameMessage;
import com.gomoku.model.Player;
import com.google.gson.Gson;
import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 游戏房间 - 按玩法管理 2–8 名玩家对战（由 GameEngine 驱动）
 *
 * 断线策略：玩家连接断开时保留席位，给 {@link #DISCONNECT_GRACE_SECONDS} 秒宽限期重连，
 * 超时未重连判负；显式 LEAVE 立即移除。
 */
public class GameRoom {

    private static final Logger logger = LoggerFactory.getLogger(GameRoom.class);
    private static final Gson gson = new Gson();
    private static final int AI_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final ScheduledExecutorService aiExecutor = createAIExecutor();

    /** 断线重连宽限期（秒） */
    private static final int DISCONNECT_GRACE_SECONDS = 60;

    private static ScheduledExecutorService createAIExecutor() {
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(
                AI_POOL_SIZE,
                r -> {
                    Thread t = new Thread(r, "AI-Move-Thread");
                    t.setDaemon(true);
                    return t;
                }
        );
        pool.setRemoveOnCancelPolicy(true);
        return pool;
    }

    private static final int AI_DELAY_MS = 600;
    /** 多人再来一局的确认窗口；给每个座位留出看结算和反应的时间 */
    static final int REMATCH_WINDOW_SECONDS = 60;
    /** 一回合多行动时，AI 每步之间的小间隔（让前端能看到过程） */
    private static final int AI_STEP_DELAY_MS = 700;
    /** AI 单回合动作上限（安全阀） */
    private static final int MAX_AI_ACTIONS_PER_TURN = 20;

    public enum State {
        WAITING, PLAYING, FINISHED
    }

    private final String roomId;
    private final GameType gameType;
    private final GameEngine engine;
    private final CopyOnWriteArrayList<Player> players;
    private final CopyOnWriteArrayList<Player> spectators;
    private final ChannelGroup channelGroup;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** 每手效果历史（与 moveHistory 对齐，供回放） */
    private final List<List<int[]>> moveEffectsHistory = new ArrayList<>();
    /** 断线时间（playerId → 断线时刻） */
    private final Map<String, Long> playerDisconnectAt = new ConcurrentHashMap<>();
    /** 断线判负倒计时（playerId → future） */
    private final Map<String, ScheduledFuture<?>> forfeitFutures = new ConcurrentHashMap<>();

    private volatile State state;
    private volatile Player currentPlayer;
    private volatile Player winner;
    private String lastGameOverJson;
    /** 本局结束原因（机器可读），用于结算解释与复盘，不依赖中文文案猜测。 */
    private volatile FinishReason finishReason = FinishReason.NORMAL;
    private final int targetPlayers;
    private final java.util.Set<String> restartVotes = new java.util.HashSet<>();
    /** 再来一局请求的截止时间；0 表示当前没有等待中的请求 */
    private volatile long rematchExpiresAt;
    private ScheduledFuture<?> rematchTimeoutFuture;
    private final java.util.Set<String> forfeited = new java.util.HashSet<>();
    private final java.util.Set<String> departed = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> pendingAI = new java.util.HashSet<>();
    private int aiDifficulty = 4; // AI 搜索深度（简单2/中等4/困难6）
    private int roundCount;
    private volatile long lastActivityTime;
    /**
     * 私有房（房号邀请建的房）：不出现在大厅列表、也不参与自动匹配。
     *
     * 「创建房间」的语义是「我要和朋友打」，如果房间仍然挂在公共列表上，
     * 陌生人可以直接点进来占座，邀请就失去意义了。
     * 只能通过房号 / 邀请链接加入。
     */
    private final boolean privateRoom;

    // ============ 计时器状态 ============
    private volatile int[] totalTimeLeft; // 按 seat - 1 存储，各席位独立计时
    private int[] seatStepTimeLeft;
    private volatile int stepTimeLeft;                  // 当前步时剩余秒
    private ScheduledFuture<?> timerFuture;

    // ELO 服务引用（由 RoomManager 注入）
    private EloService eloService;
    // 对局记录服务（由 RoomManager 注入，为 null 时不做存档）
    private GameRecordService recordService;
    // 赛季段位（由 RoomManager 注入，为 null 时座位不显示段位徽章）
    private SeasonService seasonService;
    // 限时活动（由 RoomManager 注入，为 null 时无加成）
    private EventService eventService;
    /** 队伍标识查询（座位徽标）；未注入时为 null，房间照常工作 */
    private PartyService partyService;
    /** 房间管理器回调（开局/终局/再来一局）；未注入时不影响对局 */
    private RoomManager roomManager;
    /** 上一局实际参与者；只有原班人马再开才叫「再来一局」 */
    private volatile java.util.Set<String> finishedPlayerIds = java.util.Collections.emptySet();
    // 本局开局时间戳（用于对局记录的时长与排序）
    private volatile long gameStartedAt;
    /** 随机开局玩法的初始盘面；固定开局为 null。 */
    private volatile int[][] initialBoardSnapshot;

    public GameRoom(String roomId, GameType gameType) {
        this(roomId, gameType, false);
    }

    /**
     * @param privateRoom true = 房号邀请建的私有房（不出现在大厅列表、不参与匹配）
     */
    public GameRoom(String roomId, GameType gameType, boolean privateRoom) {
        this(roomId, gameType, privateRoom, 0);
    }

    public GameRoom(String roomId, GameType gameType, boolean privateRoom, int targetPlayers) {
        this.roomId = roomId;
        this.gameType = gameType;
        this.targetPlayers = gameType.resolvePlayerCount(targetPlayers);
        this.privateRoom = privateRoom;
        this.engine = createEngine(gameType);
        this.engine.configurePlayers(this.targetPlayers);
        this.engine.reset();
        this.totalTimeLeft = new int[this.targetPlayers];
        this.seatStepTimeLeft = new int[this.targetPlayers];
        this.channelGroup = new DefaultChannelGroup("room-" + roomId, GlobalEventExecutor.INSTANCE);
        this.players = new CopyOnWriteArrayList<>();
        this.spectators = new CopyOnWriteArrayList<>();
        this.state = State.WAITING;
        this.roundCount = 0;
        this.lastActivityTime = System.currentTimeMillis();
    }

    /** 是否为房号邀请建的私有房 */
    public boolean isPrivateRoom() { return privateRoom; }

    private static GameEngine createEngine(GameType type) {
        switch (type) {
            case HIGH_CARD: return new HighCardEngine();
            case BLACKJACK: return new BlackjackEngine();
            case UNO:       return new UnoBattleEngine();
            case BOMB_CAT:  return new BombCatEngine();
            case DOUDIZHU:  return new DoudizhuEngine();
            case REVERSI:  return new ReversiBoard();
            case GO9:      return new GoBoard();
            case XIANGQI:  return new XiangqiBoard();
            case CHECKERS: return new CheckersBoard();
            case FLIGHT_CHESS: return new FlightChessBoard();
            case JUNGLE:   return new JungleBoard();
            case DARK_CHESS: return new DarkChessBoard();
            case CONNECT4: return new ConnectFourBoard();
            case ULTIMATE_TTT: return new UltimateTicTacToeBoard();
            case RPG:      return new RpgBattleEngine();
            case CARD:     return new CardBattleEngine();
            case NIM:      return new NimBattleEngine();
            case QUIZ:     return new QuizBattleEngine();
            case DICE_RACE:return new DiceRaceEngine();
            case GEM_AUCTION:return new GemAuctionEngine();
            case GOMOKU:
            default:       return new GomokuBoard();
        }
    }

    private GameAI aiFor() {
        switch (gameType) {
            case HIGH_CARD: return HighCardAI.INSTANCE;
            case BLACKJACK: return BlackjackAI.INSTANCE;
            case UNO:       return UnoAI.INSTANCE;
            case BOMB_CAT:  return BombCatAI.INSTANCE;
            case DOUDIZHU:  return DoudizhuAI.INSTANCE;
            case REVERSI:  return ReversiAI.INSTANCE;
            case GO9:      return GoAI.INSTANCE;
            case XIANGQI:  return XiangqiAI.INSTANCE;
            case CHECKERS: return CheckersAI.INSTANCE;
            case FLIGHT_CHESS: return FlightChessAI.INSTANCE;
            case JUNGLE:   return JungleAI.INSTANCE;
            case DARK_CHESS: return DarkChessAI.INSTANCE;
            case CONNECT4: return ConnectFourAI.INSTANCE;
            case ULTIMATE_TTT: return UltimateTicTacToeAI.INSTANCE;
            case RPG:      return RpgAI.INSTANCE;
            case CARD:     return CardAI.INSTANCE;
            case NIM:      return NimAI.INSTANCE;
            case QUIZ:     return QuizAI.INSTANCE;
            case DICE_RACE:return DiceRaceAI.INSTANCE;
            case GEM_AUCTION:return GemAuctionAI.INSTANCE;
            case GOMOKU:
            default:       return GomokuAIAdapter.INSTANCE;
        }
    }

    public void setEloService(EloService eloService) {
        this.eloService = eloService;
    }

    public void setRecordService(GameRecordService recordService) {
        this.recordService = recordService;
    }

    public void setSeasonService(SeasonService seasonService) {
        this.seasonService = seasonService;
    }

    public void setEventService(EventService eventService) {
        this.eventService = eventService;
    }

    public void setPartyService(PartyService partyService) {
        this.partyService = partyService;
    }

    public void setRoomManager(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    private void touchActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }

    public long getLastActivityTime() {
        return lastActivityTime;
    }

    // ============ 状态语义方法 ============

    public boolean canJoin() { return state == State.WAITING && players.size() < targetPlayers; }
    /** 只有公开、仍在等人、未满且没有掉线人类席位的房间才能进入快速匹配。 */
    public boolean isAvailableForMatching() {
        return !isPrivateRoom()
                && state == State.WAITING
                && !isFull()
                && players.stream().noneMatch(p -> !p.isAi() && !p.isConnected());
    }
    public boolean canMove() { return state == State.PLAYING; }
    public boolean canRestart() { return state == State.FINISHED; }
    public boolean canSurrender() { return state == State.PLAYING; }

    // ============ 玩家管理 ============

    public boolean addPlayer(Player player) {
        lock.writeLock().lock();
        try {
            // 同一身份已在席位上（多标签页 / 重复 JOIN）：原地替换连接，不重复占位
            Player seated = findPlayerById(player.getId());
            if (departed.contains(player.getId())) return false;
            if (seated == player) return true;
            if (seated != null && seated != player) {
                replacePlayer(seated, player);
                touchActivity();
                if (state == State.PLAYING) {
                    GameMessage syncMsg = new GameMessage(GameMessage.Type.GAME_SYNC);
                    syncMsg.setRoomId(roomId);
                    syncMsg.setPlayerId(player.getId());
                    syncMsg.setStone(player.getStone());
                    syncMsg.setGameType(gameType.name());
                    syncMsg.setMessage("已在房间中");
                    syncMsg.setData(gson.toJson(buildSyncData(player.getStone())));
                    player.sendMessage(gson.toJson(syncMsg));
                } else {
                    GameMessage msg = new GameMessage(GameMessage.Type.WAITING);
                    msg.setRoomId(roomId);
                    msg.setPlayerId(player.getId());
                    msg.setGameType(gameType.name());
                    msg.setMessage("已加入房间");
                    player.sendMessage(gson.toJson(msg));
                }
                broadcastRoomInfo();
                return true;
            }

            if (!canJoin()) return false;
            seatPlayer(player);

            if (players.size() == targetPlayers) {
                startGame();
            } else {
                GameMessage msg = new GameMessage(GameMessage.Type.WAITING);
                msg.setRoomId(roomId);
                msg.setPlayerId(player.getId());
                msg.setGameType(gameType.name());
                msg.setMessage("等待玩家加入（" + players.size() + "/" + targetPlayers + "）");
                player.sendMessage(gson.toJson(msg));
            }
            broadcastRoomInfo();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 新队伍房间一次入座，不向任何队员发送临时 WAITING 状态。 */
    void startWithParty(List<Player> roster) {
        lock.writeLock().lock();
        try {
            if (state != State.WAITING || !players.isEmpty() || roster.size() != targetPlayers) {
                throw new IllegalStateException("队伍只能在新建且人数匹配的房间开局");
            }
            for (Player member : roster) seatPlayer(member);
            startGame();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void seatPlayer(Player player) {
        spectators.remove(player);
        player.setStone(players.size() + 1);
        // 初始化 ELO（按本房间游戏类型）。AI 不参与 ELO，若在此处为它注册默认分，
        // 「电脑」会凭空出现在排行榜里，所以直接跳过。
        if (eloService != null && !player.isAi()) {
            player.setElo(eloService.getRating(player, gameType));
        }
        players.add(player);
        if (player.isConnected()) {
            channelGroup.add(player.getConnection());
        }
        touchActivity();
        logger.info("[AUDIT] player_join roomId={} gameType={} playerId={} playerName={}",
                roomId, gameType, player.getId(), player.getName());
    }

    public void addAI(int difficulty) {
        addAI(difficulty, null);
    }

    /**
     * @param aiDeck AI 使用的卡牌套牌（null = 默认均衡）
     */
    public void addAI(int difficulty, String aiDeck) {
        lock.writeLock().lock();
        try {
            if (!canJoin()) return;
            this.aiDifficulty = Math.max(2, Math.min(6, difficulty));
            String aiId = "AI-" + UUID.randomUUID().toString().substring(0, 4);
            Player aiPlayer = new Player(aiId, targetPlayers == 2 ? "电脑" : "电脑 " + (players.size() + 1), null);
            // 标记为 AI：ELO 结算与排行榜都要据此排除，否则「电脑」会混进真人榜
            aiPlayer.setAi(true);
            if (aiDeck != null && !aiDeck.isEmpty()) {
                aiPlayer.setDeck(aiDeck);
            }
            addPlayer(aiPlayer);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void addAI() {
        addAI(4); // 默认中等
    }

    public void addSpectator(Player player) {
        lock.writeLock().lock();
        try {
            if (players.contains(player)) return;
            player.setStone(0);
            spectators.addIfAbsent(player);
            if (player.isConnected()) {
                channelGroup.add(player.getConnection());
            }
            touchActivity();
            logger.info("[AUDIT] spectator_join roomId={} gameType={} playerId={}", roomId, gameType, player.getId());

            GameMessage msg = new GameMessage(GameMessage.Type.GAME_SYNC);
            msg.setRoomId(roomId);
            msg.setPlayerId(player.getId());
            msg.setSessionId(player.getSessionId());
            msg.setGameType(gameType.name());
            msg.setMessage("你已加入观战");
            msg.setData(gson.toJson(buildSyncData(0)));  // 观众视角
            player.sendMessage(gson.toJson(msg));
            broadcastRoomInfo();
        } finally { lock.writeLock().unlock(); }
    }

    // ============ 游戏流程 ============

    private void startGame() {
        state = State.PLAYING;
        lastGameOverJson = null;
        finishReason = FinishReason.NORMAL;
        cancelForfeits();
        roundCount++;
        moveEffectsHistory.clear();
        restartVotes.clear();
        cancelRematchTimeout();
        rematchExpiresAt = 0;
        forfeited.clear();
        pendingAI.clear();
        gameStartedAt = System.currentTimeMillis();
        touchActivity();
        boolean sameRoster = !finishedPlayerIds.isEmpty()
                && players.stream().allMatch(p -> finishedPlayerIds.contains(p.getId()));
        if (sameRoster) notifyManagerRematch();
        finishedPlayerIds = java.util.Collections.emptySet();
        notifyManagerStarted();

        // 清掉上一局的 ELO 变化，避免终局面板显示过期分数
        for (Player p : players) {
            p.setLastEloChange(0);
        }

        // 每轮轮换先手席位，兼容双人黑白交换。
        for (int i = 0; i < players.size(); i++) {
            players.get(i).setStone((i + roundCount - 1) % targetPlayers + 1);
        }
        currentPlayer = findPlayerByStone(1);

        // 卡牌套牌必须在 engine.reset() 之前设定 ——
        // reset 会按选择重新建库并抽起手牌，之前的顺序是「先 reset 再分方」，
        // 套牌选择永远来不及生效。
        if (engine instanceof CardBattleEngine) {
            CardBattleEngine card = (CardBattleEngine) engine;
            for (Player p : players) {
                CardBattleEngine.Deck chosen = CardBattleEngine.Deck.fromName(p.getDeck());
                if (chosen == CardBattleEngine.Deck.CUSTOM) {
                    Map<CardBattleEngine.Card, Integer> counts = parseDeckCards(p.getDeckCards());
                    String err = counts == null
                            ? "自定义套牌格式错误" : card.setCustomDeck(p.getStone(), counts);
                    if (err != null) {
                        // 自定义套牌非法 → 回落均衡，避免整个对局开不了。
                        // 这里必须记日志：静默回落会让玩家以为自己选的牌生效了。
                        logger.warn("[CARD] playerId={} 自定义套牌非法（{}），已回落均衡", p.getId(), err);
                        card.setDeck(p.getStone(), CardBattleEngine.Deck.BALANCED);
                    }
                } else {
                    card.setDeck(p.getStone(), chosen);
                }
            }
        }
        engine.reset();
        initialBoardSnapshot = engine instanceof BoardGameEngine
                ? ((BoardGameEngine) engine).getInitialBoardSnapshot() : null;
        // reset() 可能直接跳过开局不可行动的座位（例如 21 点首手即为天然
        // 21 点，引擎会把 currentTurn 推到下一位）。房间的行动者必须以
        // 引擎初始化后的真实回合为准，否则合法的首位指令会被误拒，房间卡死。
        currentPlayer = findPlayerByStone(engine.getCurrentTurn());

        // 非棋盘玩法没有棋子，但战斗日志与结算文案要用真实昵称，
        // 所以在分好方之后再注入一次（reset 会把名字重置成默认的「红方/蓝方」）。
        // 统一入口：所有玩法都调用一次，用不到的（棋类）由默认空实现忽略。
        // 这样加新玩法不用回这里改代码。
        for (Player p : players) {
            engine.setPlayerName(p.getStone(), p.getName());
        }
        // 飞行棋的当前骰子在 reset 时抛出；名字注入后再同步一次，
        // 保证首个回合的「无棋可走自动跳过」日志使用真实昵称。
        if (engine instanceof FlightChessBoard) {
            ((FlightChessBoard) engine).beginTurnRoll();
        }

        // 初始化计时器（时限由引擎定义）
        totalTimeLeft = new int[targetPlayers];
        Arrays.fill(totalTimeLeft, engine.getTotalTimeSeconds());
        resetActionClocks();

        for (Player p : players) {
            GameMessage msg = new GameMessage(GameMessage.Type.GAME_START);
            msg.setRoomId(roomId);
            msg.setPlayerId(p.getId());
            msg.setSessionId(p.getSessionId());
            msg.setStone(p.getStone());
            msg.setGameType(gameType.name());
            Player opponent = getOpponent(p);
            msg.setMessage(opponent != null ? opponent.getName() : "神秘对手");
            msg.setData(gson.toJson(buildRoomData(p.getStone())));
            p.sendMessage(gson.toJson(msg));
        }

        GameMessage syncMsg = new GameMessage(GameMessage.Type.GAME_SYNC);
        syncMsg.setRoomId(roomId);
        syncMsg.setGameType(gameType.name());
        syncMsg.setMessage("游戏开始");
        // 观战者视角 0：只含公开信息
        syncMsg.setData(gson.toJson(buildSyncData(0)));
        broadcastSpectators(gson.toJson(syncMsg));

        broadcastRoomInfo();
        logger.info("[AUDIT] game_start roomId={} gameType={} round={}", roomId, gameType, roundCount);

        // 启动计时器（AI 回合也启动但不超时判负）
        startTurnTimer();

        // 已断线的人类玩家立即进入重连宽限倒计时（AI 无连接，不参与判定）
        for (Player p : players) {
            if (!p.isAi() && !p.isConnected()) scheduleForfeit(p);
        }

        triggerAIMove();
    }

    public boolean handleMove(Player player, int row, int col, int expectedSeq) {
        return handleMove(player, -1, -1, row, col, expectedSeq);
    }

    /**
     * 落子（带可选起点：象棋等棋子移动类游戏）
     * @param fromRow/fromCol 起点，无起点概念的游戏传 -1
     */
    public boolean handleMove(Player player, int fromRow, int fromCol, int row, int col, int expectedSeq) {
        return handleCommand(player, MoveCommand.of(fromRow, fromCol, row, col), expectedSeq);
    }

    /**
     * 通用出招入口 — 与玩法无关。
     *
     * 棋类走坐标，RPG 走行动序号，都从这里进入；房间本身不解释指令内容，
     * 只做「是否轮到你 / 序号是否对得上 / 引擎是否接受」三件事。
     *
     * @param expectedSeq 客户端认为的下一手序号，-1 表示不校验
     */
    public boolean handleCommand(Player player, MoveCommand command, int expectedSeq) {
        return handleCommand(player, command, expectedSeq, null);
    }

    public boolean handleCommand(Player player, MoveCommand command, int expectedSeq, String expectedVersion) {
        lock.writeLock().lock();
        try {
            if (!canMove()) return false;
            if (!acceptsAction(player, expectedVersion)) return false;
            int previousRound = engine.getActionRound();
            int previousTurn = engine.getCurrentTurn();
            // 回合归属校验。
            // 同时行动中 currentPlayer 只是兼容字段，具体待提交状态由引擎校验。
            boolean unoCatch = engine instanceof UnoBattleEngine
                    && command.hasAction()
                    && command.getAction() == UnoBattleEngine.CATCH_UNO_ACTION;
            if (engine.simultaneousTurn()) {
                if (!players.contains(player)) return false;
            } else if (!unoCatch) {
                if (!player.getId().equals(currentPlayer.getId())) return false;
                if (player.getStone() != currentPlayer.getStone()) return false;
            }

            // 序号幂等校验：客户端自增 moveSeq，服务端只接受「当前手数+1」。
            // 网络重传（同序号）与客户端序号错位（超前/滞后）都在这拦下。
            //
            // 非棋盘玩法使用 expectedVersion 绑定当前局面或整轮，避免全局手数
            // 校验错误拒绝其他座位同时提交。旧客户端省略版本时沿用规则校验。
            if (engine instanceof BoardGameEngine
                    && expectedSeq >= 0 && expectedSeq != engine.getMoveSeq() + 1) {
                logger.warn("[AUDIT] move_seq_mismatch roomId={} playerId={} expected={} actual={}",
                        roomId, player.getId(), expectedSeq, engine.getMoveSeq() + 1);
                return false;
            }

            // 卡牌攻击走独立路径：它不消耗手牌、也不推进出牌阶段，
            // 引擎的 applyMove 不认这种指令（会当成非法行动拒绝）。
            if (command.hasAttack() && engine instanceof CardBattleEngine) {
                CardBattleEngine card = (CardBattleEngine) engine;
                if (!card.attack(player.getStone(), command.getAttackMinion(), command.getAttackTarget())) {
                    return false;
                }
            } else if (!engine.applyMove(command, player.getStone())) {
                return false;
            }
            touchActivity();
            moveEffectsHistory.add(new ArrayList<>(engine.getLastMoveEffects()));
            refreshActionClocks(previousRound, engine.autoEndsTurn() || previousTurn != engine.getCurrentTurn());

            broadcastMove(player, command.getRow(), command.getCol(), false);

            logger.info("[AUDIT] move roomId={} gameType={} playerId={} cmd={} side={} seq={}",
                    roomId, gameType, player.getId(), command, player.getStone(), engine.getMoveSeq());

            if (engine.isOver()) {
                endGameByEngine();
            } else {
                currentPlayer = findPlayerByStone(engine.getCurrentTurn());
                triggerAIMove();
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 斗地主专用出牌入口：手牌下标可任意长，无法塞进通用 MoveCommand。
     *
     * 通用路径已负责房间/座位/行动版本校验；这里只在持有写锁时把下标交给引擎，
     * 由引擎校验牌型与压牌。网络协议仍使用 action = -3 + targetSide = 3 的 MOVE，
     * 下标通过逗号分隔的 data? 不走消息扩展，避免影响旧客户端字段。
     */
    public boolean playDoudizhu(Player player, List<Integer> indices, String expectedVersion) {
        lock.writeLock().lock();
        try {
            if (!canMove() || !acceptsAction(player, expectedVersion)) return false;
            if (!(engine instanceof DoudizhuEngine)) return false;
            if (!player.getId().equals(currentPlayer.getId())) return false;
            if (player.getStone() != currentPlayer.getStone()) return false;
            DoudizhuEngine game = (DoudizhuEngine) engine;
            int previousTurn = game.getCurrentTurn();
            if (!game.playByIndices(player.getStone(), indices)) return false;
            touchActivity();
            moveEffectsHistory.add(new ArrayList<>(engine.getLastMoveEffects()));
            refreshActionClocks(game.getActionRound(), game.autoEndsTurn() || previousTurn != game.getCurrentTurn());
            broadcastMove(player, -1, -1, false);
            logger.info("[AUDIT] doudizhu_play roomId={} playerId={} cards={} seq={}",
                    roomId, player.getId(), indices, engine.getMoveSeq());
            if (engine.isOver()) {
                endGameByEngine();
            } else {
                currentPlayer = findPlayerByStone(engine.getCurrentTurn());
                triggerAIMove();
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 斗地主 AI 的最终出牌：AI 决定「出什么牌」时只能返回通用动作，
     * 手牌下标不能塞进 MoveCommand。这里在同一个 AI 线程里取出刚算好的下标，
     * 再走与真人相同的引擎校验和广播路径。
     */
    private boolean handleDoudizhuAiPlay(Player actor, int action) {
        List<Integer> chosen = DoudizhuAI.pendingIndices();
        if (chosen.isEmpty()) return false;
        return playDoudizhu(actor, chosen, null);
    }

    public boolean handleMove(Player player, int row, int col) {
        return handleMove(player, row, col, -1);
    }

    public boolean handleMove(Player player, int fromRow, int fromCol, int row, int col) {
        return handleMove(player, fromRow, fromCol, row, col, -1);
    }

    /**
     * 结束本方回合（一回合多行动的玩法专用，如卡牌）。
     *
     * 与 handlePass 的区别：pass 是「这一手我不下」，endTurn 是「我这一回合操作完了」。
     * 卡牌一回合可以出多张牌，所以必须有一个显式的收尾动作，否则只能等超时。
     */
    public boolean handleEndTurn(Player player) {
        return handleEndTurn(player, null);
    }

    public boolean handleEndTurn(Player player, String expectedVersion) {
        lock.writeLock().lock();
        try {
            if (!canMove()) return false;
            if (!acceptsAction(player, expectedVersion)) return false;
            if (!player.getId().equals(currentPlayer.getId())) return false;
            if (engine.autoEndsTurn()) return false; // 单行动玩法没有「结束回合」概念

            if (!engine.endTurn(player.getStone())) return false;
            resetActionClocks();
            touchActivity();


            logger.info("[AUDIT] end_turn roomId={} playerId={} seq={}",
                    roomId, player.getId(), engine.getMoveSeq());

            if (engine.isOver()) {
                endGameByEngine();
            } else {
                currentPlayer = findPlayerByStone(engine.getCurrentTurn());
                // 广播一手「空行动」把最新 view 推给双方（含换手后的状态）
                broadcastMove(player, -1, -1, true);
                if (currentPlayer != null && currentPlayer.isAi()) {
                    triggerAIMove();
                }
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 卡牌手动攻击：用己方第 fromCol 个随从攻击对方目标（toCol = -1 表示打英雄）。
     * 走坐标字段承载，避免再扩协议。
     */
    public boolean handleAttack(Player player, int minionIndex, int targetIndex) {
        return handleAttack(player, minionIndex, targetIndex, null);
    }

    public boolean handleAttack(Player player, int minionIndex, int targetIndex, String expectedVersion) {
        lock.writeLock().lock();
        try {
            if (!canMove()) return false;
            if (!acceptsAction(player, expectedVersion)) return false;
            if (!player.getId().equals(currentPlayer.getId())) return false;
            if (!(engine instanceof CardBattleEngine)) return false;

            CardBattleEngine card = (CardBattleEngine) engine;
            if (!card.attack(player.getStone(), minionIndex, targetIndex)) return false;
            touchActivity();

            logger.info("[AUDIT] attack roomId={} playerId={} minion={} target={}",
                    roomId, player.getId(), minionIndex, targetIndex);

            if (engine.isOver()) {
                endGameByEngine();
            } else {
                broadcastMove(player, minionIndex, targetIndex, false);
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 停一手（围棋用）
     * @return false 表示当前不能停一手
     */
    public boolean handlePass(Player player) {
        return handlePass(player, null);
    }

    public boolean handlePass(Player player, String expectedVersion) {
        lock.writeLock().lock();
        try {
            if (!canMove()) return false;
            if (!acceptsAction(player, expectedVersion)) return false;
            if (!player.getId().equals(currentPlayer.getId())) return false;
            int previousRound = engine.getActionRound();
            if (!engine.pass(player.getStone())) return false;
            refreshActionClocks(previousRound, true);
            touchActivity();
            moveEffectsHistory.add(new ArrayList<>(engine.getLastMoveEffects()));

            broadcastMove(player, GameEngine.PASS_MOVE_ROW, GameEngine.PASS_MOVE_ROW, true);

            logger.info("[AUDIT] pass roomId={} playerId={}", roomId, player.getId());

            if (engine.isOver()) {
                endGameByEngine();
            } else {
                currentPlayer = findPlayerByStone(engine.getCurrentTurn());
                // 飞行棋跳过并自动掷骰后，下一位仍可能无棋可走；
                // AI 全自动推进，真人则看到「跳过」按钮并显式提交。
                if (engine instanceof FlightChessBoard && currentPlayer != null && currentPlayer.isAi()) {
                    autoSkipIfStuck((FlightChessBoard) engine);
                }
                if (currentPlayer != null && currentPlayer.isAi()) {
                    triggerAIMove();
                }
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 斗地主叫地主/不叫：复用通用行动入口，engine 自行区分阶段。 */
    public boolean handleDoudizhuBid(Player player, int action, String expectedVersion) {
        return handleCommand(player, MoveCommand.action(action), -1, expectedVersion);
    }

    /**
     * 结算原因由服务端区分，客户端不能仅凭胜负猜测是五连、认输还是超时。
     * 保留原有 reason 文案供存档/日志使用，另下发稳定的机器可读枚举。
     */
    private enum FinishReason {
        NORMAL, SURRENDER, TIMEOUT, DISCONNECT, LEFT, DRAW
    }

    /**
     * AI 的一回合多行动流程：反复出牌，直到没有可打的牌或 AI 决定收尾。
     *
     * 每次动作之间必须让出线程一小会儿（AI_STEP_DELAY_MS），
     * 否则一整回合的 action 会在同一毫秒内灌给客户端，
     * 前端来不及渲染，玩家只看到结果看不到过程。
     */
    private void triggerAIMove() {
        if (state != State.PLAYING) return;
        for (Player actor : players) {
            if (actor.isAi() && !forfeited.contains(actor.getId()) && engine.isAwaitingAction(actor.getStone())) {
                scheduleAIAction(actor);
            }
        }
    }

    private void scheduleAIAction(Player actor) {
        if (!pendingAI.add(actor.getId())) return;
        final int slot = actor.getStone();
        int scheduledRound = roundCount;
        aiExecutor.schedule(() -> {
            lock.writeLock().lock();
            try {
                pendingAI.remove(actor.getId());
                if (roundCount != scheduledRound) return;
                if (state != State.PLAYING || !players.contains(actor)
                        || forfeited.contains(actor.getId()) || !engine.isAwaitingAction(actor.getStone())) return;
                GameAI ai = aiFor();
                if (!engine.autoEndsTurn() && !engine.simultaneousTurn()
                        && ai.shouldEndTurn(engine, actor.getStone(), aiDifficulty)) {
                    handleEndTurn(actor); return;
                }
                MoveCommand command = ai.decide(engine, actor.getStone(), aiDifficulty);
                if (command != null) {
                    if (engine instanceof DoudizhuEngine
                            && command.hasAction()
                            && command.getAction() == DoudizhuEngine.PLAY_MARKER) {
                        if (handleDoudizhuAiPlay(actor, command.getAction())) return;
                    } else if (handleCommand(actor, command, -1)) return;
                }
                if (gameType == GameType.GO9) handlePass(actor);
                else if (engine instanceof FlightChessBoard) autoSkipIfStuck((FlightChessBoard) engine);
                else if (!engine.autoEndsTurn() && !engine.simultaneousTurn()) handleEndTurn(actor);
                else if (engine instanceof DoudizhuEngine) {
                    // 斗地主的兜底：AI 没找到可压牌时必须过牌，不能把回合卡死。
                    DoudizhuEngine ddz = (DoudizhuEngine) engine;
                    if (ddz.isAwaitingAction(slot)) {
                        if (ddz.isBidding()) handleCommand(actor, MoveCommand.action(DoudizhuEngine.NO_BID_ACTION), -1);
                        else handleCommand(actor, MoveCommand.action(DoudizhuEngine.PASS_ACTION), -1);
                    }
                }
            } finally { lock.writeLock().unlock(); }
        }, AI_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 飞行棋某个座位掷出无棋可走的点数时，引擎已在掷骰阶段自动轮转到下一位；
     * 但若 AI 没有返回指令，房间仍停在旧座位会卡住。这里主动把「当前无合法走法」
     * 的局面推进一步：掷骰并轮转，直到出现可行动座位。
     */
    private void autoSkipIfStuck(FlightChessBoard flight) {
        if (!flight.legalMoves(flight.getCurrentTurn()).isEmpty()) return;
        Player skipped = currentPlayer;
        if (!flight.skipTurn(flight.getCurrentTurn())) return;
        // 跳过也是一手，必须与 moveHistory 同步记录空效果，否则复盘滑块会整体错位。
        moveEffectsHistory.add(new ArrayList<>(engine.getLastMoveEffects()));
        resetActionClocks();
        touchActivity();
        currentPlayer = findPlayerByStone(engine.getCurrentTurn());
        broadcastMove(skipped, -1, -1, true);
        if (currentPlayer != null && currentPlayer.isAi()) triggerAIMove();
    }

    // ============ 计时器 ============

    private void startTurnTimer() {
        cancelTurnTimer();
        timerFuture = aiExecutor.scheduleAtFixedRate(this::tickTimers, 1, 1, TimeUnit.SECONDS);
    }

    private void resetActionClocks() {
        stepTimeLeft = engine.getStepTimeSeconds();
        Arrays.fill(seatStepTimeLeft, stepTimeLeft);
    }

    private void refreshActionClocks(int previousRound, boolean turnFinished) {
        if (engine.simultaneousTurn() ? previousRound != engine.getActionRound() : turnFinished) resetActionClocks();
        refreshLegacyStepTime();
    }

    private void refreshLegacyStepTime() {
        int next = engine.getCurrentTurn() - 1;
        stepTimeLeft = next >= 0 && next < seatStepTimeLeft.length ? seatStepTimeLeft[next] : 0;
    }

    /** 一个房间只保持一个秒钟节拍，玩家提交不会重新排定下一次扣时。 */
    private void tickTimers() {
        lock.writeLock().lock();
        try {
            if (state != State.PLAYING) { cancelTurnTimer(); return; }
            List<Player> expired = new ArrayList<>();
            for (Player player : players) {
                if (player.isAi() || forfeited.contains(player.getId()) || !engine.isAwaitingAction(player.getStone())) continue;
                int index = player.getStone() - 1;
                seatStepTimeLeft[index] = Math.max(0, seatStepTimeLeft[index] - 1);
                totalTimeLeft[index] = Math.max(0, totalTimeLeft[index] - 1);
                if (seatStepTimeLeft[index] == 0 || totalTimeLeft[index] == 0) expired.add(player);
            }
            refreshLegacyStepTime();
            broadcastTimerSync();
            long remaining = players.stream().filter(p -> !forfeited.contains(p.getId())).count();
            if (engine.simultaneousTurn() && !expired.isEmpty() && expired.size() == remaining) {
                finishReason = FinishReason.TIMEOUT;
                endGame(null, "所有玩家同时超时，本局平局", null);
                return;
            }
            for (Player player : expired) {
                if (state != State.PLAYING) break;
                String reason = totalTimeLeft[player.getStone() - 1] == 0
                        ? player.getName() + " 总时间耗尽" : player.getName() + " 步时超时";
                handleForfeit(player, reason);
            }
        } finally { lock.writeLock().unlock(); }
    }

    private void cancelTurnTimer() {
        if (timerFuture != null) {
            timerFuture.cancel(false);
            timerFuture = null;
        }
    }

    private void broadcastTimerSync() {
        int blackTime = totalTimeLeft[0];
        GameMessage msg = new GameMessage(GameMessage.Type.TIMER_SYNC);
        msg.setRoomId(roomId);
        msg.setTimeLeft(stepTimeLeft);
        msg.setTotalTimeLeft(blackTime); // 兼容：用 totalTimeLeft 传黑方时间
        // data 携带各座位的总时、步时和待提交状态。
        msg.setData(gson.toJson(buildTimerData()));
        broadcastMessage(gson.toJson(msg));
    }

    private Map<String, Object> buildTimerData() {
        Map<String, Object> timerData = new LinkedHashMap<>();
        timerData.put("stepTime", stepTimeLeft);
        timerData.put("blackTotal", totalTimeLeft[0]);
        timerData.put("whiteTotal", totalTimeLeft[1]);
        timerData.put("seatTotals", totalTimeLeft.clone());
        timerData.put("seatSteps", seatStepTimeLeft.clone());
        List<Integer> awaitingSides = new ArrayList<>();
        if (state == State.PLAYING) for (Player player : players) {
            if (!forfeited.contains(player.getId()) && engine.isAwaitingAction(player.getStone())) awaitingSides.add(player.getStone());
        }
        timerData.put("awaitingSides", awaitingSides);
        timerData.put("currentTurn", engine.getCurrentTurn());
        return timerData;
    }

    // ============ 广播 ============

    private void broadcastMove(Player player, int row, int col, boolean isPass) {
        GameMessage base = new GameMessage(GameMessage.Type.GAME_MOVE);
        base.setRoomId(roomId);
        base.setPlayerId(player.getId());
        base.setPlayerName(player.getName());
        base.setRow(row);
        base.setCol(col);
        base.setStone(player.getStone());
        base.setMoveSeq(engine.getMoveSeq());

        Map<String, Object> common = new LinkedHashMap<>();
        common.put("turn", engine.getCurrentTurn());
        common.put("pass", isPass);
        common.put("effects", engine.getLastMoveEffects());
        common.put("timers", buildTimerData());
        common.put("actionVersion", actionVersion());

        // 视图按视角逐人裁剪：卡牌类有隐藏手牌，不能整包广播。
        // 信息全公开的玩法（棋类/RPG）getViewState 忽略视角，各人拿到的内容一致。
        // players + spectators 恰好覆盖渠道组里所有连接，逐人发送即完整广播。
        for (Player p : players) {
            if (departed.contains(p.getId())) continue;
            p.sendMessage(gson.toJson(enrichMoveView(base, common, p.getStone())));
        }
        // 观战者只看公开信息（viewerSide = 0）
        broadcastSpectators(gson.toJson(enrichMoveView(base, common, 0)));
    }

    /** 给一手消息附加指定视角的状态快照 */
    private GameMessage enrichMoveView(GameMessage base, Map<String, Object> common, int viewerSide) {
        GameMessage msg = copyMoveHeader(base);
        Map<String, Object> data = new LinkedHashMap<>(common);
        Object view = engine.getViewState(viewerSide);
        if (view != null) {
            data.put("view", view);
        }
        msg.setData(gson.toJson(data));
        return msg;
    }

    /** 复制一手消息的公共头字段（每个视角各发一份） */
    private static GameMessage copyMoveHeader(GameMessage src) {
        GameMessage msg = new GameMessage(src.getType());
        msg.setRoomId(src.getRoomId());
        msg.setPlayerId(src.getPlayerId());
        msg.setPlayerName(src.getPlayerName());
        msg.setRow(src.getRow());
        msg.setCol(src.getCol());
        msg.setStone(src.getStone());
        msg.setMoveSeq(src.getMoveSeq());
        return msg;
    }

    /**
     * 广播玩家连接状态变化（断线/重连通知，不重置前端棋盘）
     */
    private void broadcastPlayerStatus(String text, Player about) {
        GameMessage msg = new GameMessage(GameMessage.Type.PLAYER_STATUS);
        msg.setRoomId(roomId);
        if (about != null) msg.setPlayerId(about.getId());
        msg.setMessage(text);
        broadcastMessage(gson.toJson(msg));
    }

    public void broadcastMessage(String json) {
        for (Player p : players) if (!departed.contains(p.getId())) p.sendMessage(json);
        for (Player p : spectators) p.sendMessage(json);
    }

    public void broadcastSpectators(String json) {
        int sent = 0;
        for (Player p : spectators) {
            if (p.isConnected()) {
                p.sendMessage(json);
                sent++;
            }
        }
        // 观战广播静默失败很难排查（曾经出现「观战者收不到落子、但能收到房间信息」），
        // 留一条 debug 日志便于定位。
        if (!spectators.isEmpty() && sent == 0) {
            logger.warn("[SPECTATE] room={} 有 {} 个观众但都未连接，广播被跳过",
                    roomId, spectators.size());
        } else if (sent > 0) {
            logger.debug("[SPECTATE] room={} 广播给 {} 个观众", roomId, sent);
        }
    }

    // ============ 结束游戏 ============

    /** 引擎终局（五连/子数/数子）驱动的结束 */
    private void endGameByEngine() {
        Integer winnerSide = engine.getWinner();
        // 斗地主农民胜时引擎用 0 表示「农民阵营」，没有单个胜者座位。
        // 结算消息仍需要一个代表座位，否则前端会把三名玩家都显示成失败/平局。
        if ((winnerSide == null || winnerSide == 0) && engine.isOver()) {
            java.util.List<Integer> sides = engine.getWinningSides();
            if (!sides.isEmpty()) winnerSide = sides.get(0);
        }
        Player winnerPlayer = winnerSide == null ? null : findPlayerByStone(winnerSide);
        String reason;
        if (winnerSide == null) {
            reason = engine.getScoreSummary() != null ? "终局：" + engine.getScoreSummary() : "平局！";
        } else if (gameType == GameType.GOMOKU) {
            reason = "五子连珠！";
        } else {
            reason = engine.getScoreSummary() != null ? engine.getScoreSummary() : "终局";
        }
        // 连线只有棋盘类才有（五子连珠/四连）；RPG 等玩法终止局时拿不到也不想拿
        int[][] winLine = engine instanceof BoardGameEngine
                ? ((BoardGameEngine) engine).getWinLine() : null;
        // 认输/超时/断线也可能让引擎进入「只剩最后一人」的终局。
        // 此时必须保留调用方已经写入的结束原因，否则结算与记录会把
        // 对手退出误报为正常完赛；只有从未设置过原因时才落到 NORMAL/DRAW。
        if (finishReason == FinishReason.NORMAL) {
            finishReason = winnerSide == null ? FinishReason.DRAW : FinishReason.NORMAL;
        }
        endGame(winnerPlayer, reason, winLine);
    }

    private void endGame(Player winnerPlayer, String reason, int[][] winLine) {
        FinishReason resolvedReason = finishReason;
        if (winnerPlayer == null && resolvedReason == FinishReason.NORMAL) {
            resolvedReason = FinishReason.DRAW;
        }
        cancelTurnTimer();
        cancelForfeits();
        state = State.FINISHED;
        this.winner = winnerPlayer;
        touchActivity();

        // 结算前记录双方 ELO（对局记录里要存 before/after 才能算出变化）
        Map<String, Integer> eloBefore = new LinkedHashMap<>();
        for (Player p : players) {
            eloBefore.put(p.getId(), p.getElo());
        }

        List<Integer> winningSides = engine.isOver() ? engine.getWinningSides()
                : winnerPlayer == null ? java.util.Collections.emptyList()
                : java.util.Collections.singletonList(winnerPlayer.getStone());
        java.util.List<Integer> drawingSides = engine.isOver() ? engine.getDrawingSides()
                : java.util.Collections.emptyList();
        for (Player p : players) {
            if (winningSides.contains(p.getStone())) p.addWin();
            else if (!winningSides.isEmpty() && !drawingSides.contains(p.getStone())) p.addLoss();
        }

        // 纯运气类玩法（比大小 / 21 点）不进入双人 ELO，避免随机性污染竞技分。
        boolean ranked = gameType != GameType.HIGH_CARD && gameType != GameType.BLACKJACK && targetPlayers == 2
                && players.stream().noneMatch(Player::isAi)
                && !players.get(0).getRatingKey().equals(players.get(1).getRatingKey());
        // ELO 更新（跳过 AI）。判定用 Player.isAi 而不是 aiPlayer 引用，
        // 该引用在 replacePlayer / addPlayer 重入等路径下可能已指向新对象。
        int eloChange = 0;
        if (ranked && eloService != null && winnerPlayer != null && !winnerPlayer.isAi()) {
            Player loser = getOpponent(winnerPlayer);
            if (loser != null && !loser.isAi()) {
                int[] result = eloService.updateRating(winnerPlayer, loser, gameType);
                eloChange = result[2];
                // 写入双方各自的变化量，前端按玩家渲染 +/- 分（此前只给总分，导致面板不显示）
                winnerPlayer.setLastEloChange(eloChange);
                loser.setLastEloChange(-eloChange);
            }
        } else if (ranked && eloService != null && winnerPlayer == null) {
            // 平局
            if (players.size() == 2 && !players.get(0).isAi() && !players.get(1).isAi()) {
                int before0 = players.get(0).getElo();
                int before1 = players.get(1).getElo();
                eloService.updateDraw(players.get(0), players.get(1), gameType);
                players.get(0).setLastEloChange(players.get(0).getElo() - before0);
                players.get(1).setLastEloChange(players.get(1).getElo() - before1);
            }
        }

        GameMessage msg = new GameMessage(GameMessage.Type.GAME_OVER);
        msg.setRoomId(roomId);
        msg.setWinner(winnerPlayer != null ? winnerPlayer.getId() : "draw");
        msg.setGameType(gameType.name());
        msg.setMessage(reason);

        // 构建附加数据：分数 + ELO + 获胜连线 + 对局记录（含每手效果，供回放）
        Map<String, Object> dataMap = new LinkedHashMap<>();
        ScoreDataDto scoreData = buildScoreData();
        dataMap.put("scores", scoreData.getScores());
        dataMap.put("eloChange", eloChange);
        dataMap.put("winningSides", winningSides);
        dataMap.put("drawingSides", drawingSides);
        // 结算消息可能被重连恢复或缓存到客户端；房间可见性必须随终局一起下发，
        // 不能依赖前端在进房时记住的临时标记。
        dataMap.put("privateRoom", privateRoom);
        dataMap.put("aiSeats", (int) players.stream().filter(Player::isAi).count());
        dataMap.put("finishReason", resolvedReason.name());
        dataMap.put("forfeitedPlayerIds", new ArrayList<>(forfeited));
        // 21 点可能出现「有庄家、但所有玩家都没赢」的决胜局：此时不能把所有人当平局。
        dataMap.put("decisiveNoWinner", gameType == GameType.BLACKJACK
                && winningSides.isEmpty() && drawingSides.isEmpty());
        if (engine.getScoreSummary() != null) {
            dataMap.put("scoreSummary", engine.getScoreSummary());
        }
        if (winLine != null) {
            dataMap.put("winLine", Arrays.asList(winLine));
        }
        // 对局记录（用于回放）
        dataMap.put("moveHistory", engine.getMoveHistory());
        dataMap.put("moveEffects", moveEffectsHistory);
        // 随机开局玩法必须把开局盘面带给客户端，否则结算页/独立复盘只能从空盘开始。
        if (initialBoardSnapshot != null) {
            dataMap.put("initialBoard", copyBoard(initialBoardSnapshot));
        }
        // 结算时生效的限时活动：客户端据此在结算页解释多出来的经验
        EventService.Event activeEvent = eventService == null
                ? null : eventService.activeFor(gameType, System.currentTimeMillis());
        if (activeEvent != null) {
            Map<String, Object> eventData = new LinkedHashMap<>();
            eventData.put("id", activeEvent.id);
            eventData.put("title", activeEvent.title);
            eventData.put("gameType", activeEvent.gameType);
            eventData.put("xpPercent", activeEvent.xpPercent);
            eventData.put("endsAt", activeEvent.endsAt);
            dataMap.put("event", eventData);
        }
        msg.setData(gson.toJson(dataMap));
        lastGameOverJson = gson.toJson(msg);

        // 先写入内存索引再广播：客户端收到终局后会立刻查询成长/任务进度，
        // 若先广播后存档，这次查询可能漏掉本局（磁盘仍是异步写，不阻塞广播）。
        archiveGame(winnerPlayer, reason, eloBefore, activeEvent);
        java.util.Set<String> finished = new java.util.HashSet<>();
        // 原班人马判定必须包含 AI 座位：否则人机房永远不满足 sameRoster，
        // 「再来一局」指标会系统性漏掉最常用的人机练习入口。
        // 身份去重与真人事件仍由 RoomManager 过滤 AI。
        for (Player p : players) finished.add(p.getId());
        finishedPlayerIds = java.util.Collections.unmodifiableSet(finished);
        notifyManagerFinished();

        broadcastMessage(lastGameOverJson);
        broadcastRoomInfo();

        // 存档后释放显式离场的历史席位，剩余玩家可补人再开局。
        for (Player p : new ArrayList<>(players)) {
            if (departed.contains(p.getId())) removePlayer(p);
        }
            // 所有真人已离场，即使仍有 AI 也直接回收房间，不再保持等待
            if (players.stream().noneMatch(p -> !p.isAi() && !departed.contains(p.getId()))) {
                for (Player spectator : new ArrayList<>(spectators)) {
                    GameMessage kickMsg = new GameMessage(GameMessage.Type.ERROR);
                    kickMsg.setMessage("房间内玩家已全部离开，房间解散");
                    spectator.sendMessage(gson.toJson(kickMsg));
                }
                players.clear();
                spectators.clear();
            }

        logger.info("[AUDIT] game_over roomId={} gameType={} winner={} reason=\"{}\" eloChange={}", roomId,
                gameType, winnerPlayer != null ? winnerPlayer.getId() : "draw", reason, eloChange);
    }

    /**
     * 把本局写入对局记录（异步）。AI 局也会存档以便复盘，但标记 ranked=false 且不计 ELO。
     */
    private void archiveGame(Player winnerPlayer, String reason, Map<String, Integer> eloBefore,
                             EventService.Event activeEvent) {
        if (recordService == null) return;
        try {
            GameRecord record = new GameRecord();
            record.setGameType(gameType.name());
            record.setRoomId(roomId);
            record.setStartedAt(gameStartedAt > 0 ? gameStartedAt : System.currentTimeMillis());
            record.setEndedAt(System.currentTimeMillis());
            record.setWinnerName(winnerPlayer != null ? winnerPlayer.getName() : null);
            record.setReason(reason);
            record.setMoveCount(engine.getMoveCount());
            // 活动倍率刻进记录：历史经验必须稳定，活动结束后不回算
            if (activeEvent != null) {
                record.setEventId(activeEvent.id);
                record.setEventTitle(activeEvent.title);
                record.setEventXpPercent(activeEvent.xpPercent);
            }

            // 计分局：双方都是真人。有 AI 或一方缺席都不算
            boolean hasAi = players.stream().anyMatch(Player::isAi);
            record.setRanked(gameType != GameType.HIGH_CARD && gameType != GameType.BLACKJACK
                    && !hasAi && players.size() == 2
                    && !players.get(0).getRatingKey().equals(players.get(1).getRatingKey()));
            List<GameRecord.Participant> participants = new ArrayList<>();
            for (Player p : players) {
                // 以 Player.isAi 为准：aiPlayer 引用在 replacePlayer 等路径下可能失效，
                // 而 isAi 标记一旦设置就跟着玩家对象走。
                boolean isAi = p.isAi();
                String result;
                List<Integer> wins = engine.isOver() ? engine.getWinningSides()
                        : winnerPlayer == null ? java.util.Collections.emptyList()
                        : java.util.Collections.singletonList(winnerPlayer.getStone());
                java.util.List<Integer> draws = engine.isOver() ? engine.getDrawingSides()
                        : java.util.Collections.emptyList();
                if (wins.isEmpty() || draws.contains(p.getStone())) result = "DRAW";
                else result = wins.contains(p.getStone()) ? "WIN" : "LOSE";

                int before = eloBefore.getOrDefault(p.getId(), p.getElo());
                int after = p.getElo();
                GameRecord.Participant participant = new GameRecord.Participant(
                        p.getId(), p.getName(), p.getStone(), result, isAi, before, after, after - before);
                participant.setIdentityId(p.getIdentityId());
                // 卡牌：记录「引擎实际生效」的套牌，而不是玩家提交值。
                // 自定义配比非法时服务端会静默回落均衡，照抄提交值会让记录撒谎。
                if (engine instanceof CardBattleEngine) {
                    CardBattleEngine card = (CardBattleEngine) engine;
                    CardBattleEngine.Deck used = card.getDeck(p.getStone());
                    participant.setDeck(used.name());
                    participant.setDeckLabel(used.getLabel());
                    participant.setDeckSummary(card.getDeckSummary(p.getStone()));
                }
                participants.add(participant);
            }
            record.setParticipants(participants);

            // 棋谱（存档服务会自行截断超长棋谱）
            record.setMoveHistory(new ArrayList<>(engine.getMoveHistory()));
            record.setInitialBoard(initialBoardSnapshot == null ? null : copyBoard(initialBoardSnapshot));
            List<List<int[]>> effects = new ArrayList<>(moveEffectsHistory.size());
            for (List<int[]> e : moveEffectsHistory) {
                effects.add(new ArrayList<>(e));
            }
            record.setMoveEffects(effects);

            recordService.saveAsync(record);
            logger.info("[AUDIT] game_archived roomId={} recordId={} ranked={} moves={}",
                    roomId, record.getId(), record.isRanked(), record.getMoveCount());
        } catch (Exception e) {
            // 存档失败绝不影响对局结束流程
            logger.warn("对局记录构建失败 roomId={}", roomId, e);
        }
    }

    /** 上报产品事件到房间管理器；统计失败绝不影响开局/终局流程。 */
    private void notifyManagerStarted() {
        if (roomManager == null) return;
        try {
            roomManager.onGameStarted(this);
        } catch (RuntimeException e) {
            logger.warn("开局产品事件上报失败 roomId={}", roomId, e);
        }
    }

    private static int[][] copyBoard(int[][] board) {
        if (board == null) return null;
        int[][] copy = new int[board.length][];
        for (int r = 0; r < board.length; r++) {
            copy[r] = board[r] == null ? null : board[r].clone();
        }
        return copy;
    }

    private void notifyManagerFinished() {
        if (roomManager == null) return;
        try {
            roomManager.onGameFinished(this);
        } catch (RuntimeException e) {
            logger.warn("终局产品事件上报失败 roomId={}", roomId, e);
        }
    }

    private void notifyManagerRematch() {
        if (roomManager == null) return;
        try {
            roomManager.onRematch(this);
        } catch (RuntimeException e) {
            logger.warn("再来一局产品事件上报失败 roomId={}", roomId, e);
        }
    }

    public void surrender(Player player) {
        lock.writeLock().lock();
        try {
            if (!canSurrender()) return;
            Player found = findPlayerById(player.getId());
            if (found == null) return;
            finishReason = FinishReason.SURRENDER;
            handleForfeit(found, player.getName() + " 认输");
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void requestRestart(Player player) {
        lock.writeLock().lock();
        try {
            if (!canRestart()) return;
            Player found = findPlayerById(player.getId());
            if (found == null) return;

            if (restartVotes.contains(found.getId())) {
                // 网络重传或重复点击必须幂等，且不能放大成新一轮全房广播。
                return;
            }
            restartVotes.add(found.getId());
            boolean allReady = players.size() == targetPlayers
                    && players.stream().allMatch(p -> p.isAi() || restartVotes.contains(p.getId()));
            if (allReady) { startGame(); return; }
            if (rematchExpiresAt == 0) {
                rematchExpiresAt = System.currentTimeMillis() + REMATCH_WINDOW_SECONDS * 1000L;
                scheduleRematchTimeout();
            }
            broadcastRematchState(found.getName() + " 已准备再来一局"
                    + "（" + restartVotes.size() + "/" + humanPlayerCount() + " 人确认）");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 撤回再来一局准备。最后一个准备者撤回时立即结束本轮等待，
     * 不必让其他玩家盯着一个已经没有意义的倒计时。
     */
    public void cancelRestart(Player player) {
        lock.writeLock().lock();
        try {
            if (state != State.FINISHED || player == null) return;
            Player found = findPlayerById(player.getId());
            if (found == null || !restartVotes.remove(found.getId())) return;
            if (restartVotes.isEmpty()) {
                clearRematchWait();
                broadcastRematchState(found.getName() + " 撤回了再来一局");
                return;
            }
            broadcastRematchState(found.getName() + " 撤回了再来一局");
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void scheduleRematchTimeout() {
        cancelRematchTimeout();
        long delay = Math.max(1L, rematchExpiresAt - System.currentTimeMillis());
        rematchTimeoutFuture = aiExecutor.schedule(() -> {
            lock.writeLock().lock();
            try {
                if (state != State.FINISHED || rematchExpiresAt == 0) return;
                if (System.currentTimeMillis() < rematchExpiresAt) return;
                clearRematchWait();
                broadcastRematchState("再来一局等待超时，已回到结算页");
            } finally {
                lock.writeLock().unlock();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void cancelRematchTimeout() {
        ScheduledFuture<?> future = rematchTimeoutFuture;
        rematchTimeoutFuture = null;
        if (future != null) future.cancel(false);
    }

    private void clearRematchWait() {
        restartVotes.clear();
        rematchExpiresAt = 0;
        cancelRematchTimeout();
    }

    /** 电脑自动跟随，不参与准备确认。 */
    private int humanPlayerCount() {
        return (int) players.stream().filter(p -> !p.isAi()).count();
    }

    private RematchStateDto buildRematchState() {
        RematchStateDto dto = new RematchStateDto();
        List<String> ready = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        for (Player p : players) {
            if (p.isAi() || departed.contains(p.getId())) continue;
            if (restartVotes.contains(p.getId())) ready.add(p.getId());
            else waiting.add(p.getId());
        }
        dto.setReadyPlayerIds(ready);
        dto.setWaitingPlayerIds(waiting);
        dto.setHumanPlayers(ready.size() + waiting.size());
        boolean pending = state == State.FINISHED && rematchExpiresAt > 0
                && System.currentTimeMillis() < rematchExpiresAt;
        dto.setPending(pending);
        dto.setExpiresAt(pending ? rematchExpiresAt : null);
        return dto;
    }

    private void broadcastRematchState(String message) {
        broadcastRoomInfo();
        if (message == null || message.isEmpty()) return;
        GameMessage msg = new GameMessage(GameMessage.Type.RESTART_REQUEST);
        msg.setRoomId(roomId);
        msg.setMessage(message);
        msg.setData(gson.toJson(buildRematchState()));
        broadcastMessage(gson.toJson(msg));
    }

    public void handleChat(Player player, String message) {
        if (!player.canSendChat()) return;
        GameMessage msg = new GameMessage(GameMessage.Type.GAME_CHAT);
        msg.setRoomId(roomId);
        msg.setPlayerId(player.getId());
        msg.setPlayerName(player.getName());
        msg.setMessage(message);
        broadcastMessage(gson.toJson(msg));
    }

    // ============ 断线 / 离开 ============

    /**
     * 连接断开（掉线）：保留席位，进入宽限期等待重连
     */
    public void handleDisconnect(Player player) {
        lock.writeLock().lock();
        try {
            if (player.getConnection() != null) {
                channelGroup.remove(player.getConnection());
            }

            if (spectators.contains(player)) {
                // 观众也保留会话，刷新后可恢复；TTL 到期再由管理器移除。
                broadcastRoomInfo();
                return;
            }
            if (!players.contains(player)) return;

            playerDisconnectAt.put(player.getId(), System.currentTimeMillis());
            broadcastPlayerStatus(player.getName() + " 连接断开，" + DISCONNECT_GRACE_SECONDS + " 秒内未重连将判负", player);
            logger.info("[AUDIT] player_disconnect roomId={} playerId={} grace={}s",
                    roomId, player.getId(), DISCONNECT_GRACE_SECONDS);

            if (state == State.PLAYING) {
                scheduleForfeit(player);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 断线判负倒计时（宽限期内重连会取消）
     */
    private void scheduleForfeit(Player player) {
        ScheduledFuture<?> existing = forfeitFutures.get(player.getId());
        if (existing != null && !existing.isDone()) return;

        ScheduledFuture<?> future = aiExecutor.schedule(() -> {
            lock.writeLock().lock();
            try {
                forfeitFutures.remove(player.getId());
                if (state != State.PLAYING || !players.contains(player)) return;
                if (player.isConnected()) return; // 已重连
                handleForfeit(player, player.getName() + " 断线超时");
            } finally {
                lock.writeLock().unlock();
            }
        }, DISCONNECT_GRACE_SECONDS, TimeUnit.SECONDS);
        forfeitFutures.put(player.getId(), future);
    }

    /**
     * 显式离开房间（LEAVE 消息 / 清理任务）：立即移除席位
     */
    /**
     * 显式离开房间（LEAVE / 清理任务）。
     *
     * 终局房里的离场必须只移除该玩家，不能再把椅子状态改成 WAITING：
     * 留下的人仍应停留在结算页；若房间转成公开等待房，离开者下一条 JOIN
     * 会立刻匹配回原房，并把仍在看结算的对手强行拉进下一局。
     */
    public void removePlayer(Player player) {
        lock.writeLock().lock();
        try {
            if (player.getConnection() != null) {
                channelGroup.remove(player.getConnection());
            }

            if (spectators.contains(player)) {
                spectators.remove(player);
                broadcastRoomInfo();
                return;
            }

            cancelForfeit(player.getId());
            playerDisconnectAt.remove(player.getId());

            if (!players.contains(player)) return;
            departed.add(player.getId());
            boolean otherHumanOnResult = state == State.FINISHED && players.stream()
                    .anyMatch(p -> !p.isAi() && !p.getId().equals(player.getId()) && !departed.contains(p.getId()));
            if (otherHumanOnResult) {
                players.remove(player);
                departed.remove(player.getId());
                if (players.stream().noneMatch(p -> !p.isAi())) {
                    players.clear();
                }
                for (int i = 0; i < players.size(); i++) players.get(i).setStone(i + 1);
                touchActivity();
                broadcastRoomInfo();
                logger.info("[AUDIT] player_leave_finished roomId={} playerId={}", roomId, player.getId());
                return;
            }
            if (state == State.PLAYING) {
                handleForfeit(player, player.getName() + " 离开房间");
                // 全部真人已显式离场时停止剩余电脑局，避免后台继续排 AI 与计时任务。
                if (state == State.PLAYING && players.stream().noneMatch(p -> !p.isAi() && !departed.contains(p.getId()))) {
                    for (Player remaining : new ArrayList<>(players)) {
                        if (state != State.PLAYING) break;
                        if (remaining.isAi()) handleForfeit(remaining, "真人已全部离开");
                    }
                }
                if (!players.contains(player)) return; // 终局清理可能已移除该历史席位。
                // 已淘汰的席位保留到终局，重连与存档不会丢失参与者。
                if (state == State.PLAYING) { broadcastRoomInfo(); return; }
            }
            players.remove(player);
            departed.remove(player.getId());
            if (players.stream().noneMatch(p -> !p.isAi())) {
                players.clear();
            }
            for (int i = 0; i < players.size(); i++) players.get(i).setStone(i + 1);
            state = State.WAITING;
            engine.reset();
            lastGameOverJson = null;
            finishReason = FinishReason.NORMAL;
            currentPlayer = null;
            pendingAI.clear();
            clearRematchWait();
            cancelForfeits();
            moveEffectsHistory.clear();
            cancelTurnTimer();
                touchActivity();

            if (!players.isEmpty()) {
                GameMessage waitMsg = new GameMessage(GameMessage.Type.WAITING);
                waitMsg.setRoomId(roomId);
                waitMsg.setGameType(gameType.name());
                waitMsg.setMessage("玩家已离开，等待新玩家加入...");
                broadcastMessage(gson.toJson(waitMsg));
            }
            broadcastRoomInfo();
            logger.info("[AUDIT] player_leave roomId={} playerId={}", roomId, player.getId());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void cancelForfeit(String playerId) {
        ScheduledFuture<?> future = forfeitFutures.remove(playerId);
        if (future != null) future.cancel(false);
    }

    private void cancelForfeits() {
        for (ScheduledFuture<?> future : forfeitFutures.values()) future.cancel(false);
        forfeitFutures.clear();
    }

    private void handleForfeit(Player player, String reason) {
        if (!forfeited.add(player.getId())) return;
        if (reason.contains("认输")) finishReason = FinishReason.SURRENDER;
        else if (reason.contains("超时") || reason.contains("时间耗尽")) finishReason = FinishReason.TIMEOUT;
        else if (reason.contains("断线")) finishReason = FinishReason.DISCONNECT;
        else if (reason.contains("离开")) finishReason = FinishReason.LEFT;
        int previousRound = engine.getActionRound();
        if (engine.forfeit(player.getStone())) {
            if (engine.isOver()) endGameByEngine();
            else {
                currentPlayer = findPlayerByStone(engine.getCurrentTurn());
                refreshActionClocks(previousRound, true);
                // forfeit 也可能给引擎追加一手（如多人淘汰记录），与手数保持逐手对齐。
                if (moveEffectsHistory.size() < engine.getMoveCount()) {
                    while (moveEffectsHistory.size() < engine.getMoveCount()) {
                        moveEffectsHistory.add(new ArrayList<>(engine.getLastMoveEffects()));
                    }
                }
                // 飞行棋的认输只淘汰一个座位，必须让大厅和聊天区明确看到
                // 谁离场；普通双人局仍由终局/离开提示承担同一信息。
                if (engine instanceof FlightChessBoard) {
                    broadcastPlayerStatus(player.getName() + " 已退出本局", player);
                }
                broadcastMove(player, -1, -1, true);
                triggerAIMove();
            }
        } else {
            // 多人引擎必须定义自己的淘汰规则，不能把第一位对手误判为唯一胜者。
            finishReason = reason.contains("认输") ? FinishReason.SURRENDER
                    : reason.contains("超时") || reason.contains("时间耗尽") ? FinishReason.TIMEOUT
                    : reason.contains("断线") ? FinishReason.DISCONNECT
                    : reason.contains("离开") ? FinishReason.LEFT
                    : FinishReason.NORMAL;
            endGame(targetPlayers == 2 ? getOpponent(player) : null, reason, null);
        }
    }

    // ============ 辅助方法 ============

    public Player getOpponent(Player player) {
        for (Player p : players) {
            if (!p.getId().equals(player.getId())) return p;
        }
        return null;
    }

    private Player getBlackPlayer() {
        for (Player p : players) {
            if (p.getStone() == GomokuBoard.BLACK) return p;
        }
        return players.isEmpty() ? null : players.get(0);
    }

    /**
     * 解析自定义套牌 JSON（{"FOOTMAN":6,"ARCHER":4,...}）。
     *
     * 用 Gson 解析成 Map<String,Integer> 再映射到 Card 枚举；
     * 任何无法识别的卡名都视为格式错误（返回 null），由调用方回落均衡。
     */
    private static Map<CardBattleEngine.Card, Integer> parseDeckCards(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            Map<String, Double> raw = gson.fromJson(json, Map.class);
            if (raw == null || raw.isEmpty()) return null;
            Map<CardBattleEngine.Card, Integer> result = new LinkedHashMap<>();
            for (Map.Entry<String, Double> e : raw.entrySet()) {
                // 卡名是枚举名（FOOTMAN/ARCHER…），不是 Deck 名
                CardBattleEngine.Card card;
                try {
                    card = CardBattleEngine.Card.valueOf(e.getKey().trim().toUpperCase());
                } catch (IllegalArgumentException | NullPointerException ignored) {
                    return null; // 未知卡名 → 整份配比作废
                }
                int n = e.getValue() == null ? 0 : (int) Math.round(e.getValue());
                result.put(card, n);
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private Player findPlayerById(String id) {
        for (Player p : players) {
            if (p.getId().equals(id)) return p;
        }
        return null;
    }

    private Player findPlayerByStone(int side) {
        for (Player p : players) {
            if (p.getStone() == side) return p;
        }
        return null;
    }

    // ============ DTO 构建 ============

    /** 同时行动只绑定整轮，其他座位提交不会让当前玩家的操作过期。 */
    private String actionVersion() {
        return roomId + ":" + roundCount + ":" + (engine.simultaneousTurn()
                ? "r" + engine.getActionRound() : "s" + engine.getMoveSeq());
    }

    /** 在写锁内与规则校验一起执行，避免验证后局面又被其他线程推进。 */
    private boolean acceptsAction(Player player, String expectedVersion) {
        return players.contains(player) && !departed.contains(player.getId()) && !forfeited.contains(player.getId())
                && (expectedVersion == null || expectedVersion.equals(actionVersion()));
    }

    private RoomDataDto buildRoomData() {
        return buildRoomData(0);
    }

    /**
     * @param viewerSide 观察者视角（1/2=玩家，0=观众/未知）。
     *                   卡牌类按视角裁手牌，棋盘/RPG 忽略此参数。
     */
    private RoomDataDto buildRoomData(int viewerSide) {
        RoomDataDto data = new RoomDataDto();
        data.setGameType(gameType.name());
        data.setMinPlayers(gameType.getMinPlayers());
        data.setMaxPlayers(gameType.getMaxPlayers());
        data.setTargetPlayers(targetPlayers);
        data.setActionVersion(actionVersion());
        List<PlayerInfoDto> playerDtos = new ArrayList<>();
        for (Player p : players) {
            PlayerInfoDto dto = new PlayerInfoDto(p.getId(), p.getName(), p.getStone(), p.getWins(), p.getElo());
            dto.setAi(p.isAi());
            // 段位徽章挂在座位上：多人房里一眼看出谁是老玩家，也把「赛季」变成可见身份
            if (seasonService != null && !p.isAi()) {
                dto.setTier(seasonService.tierOf(p.getIdentityId()));
            }
            // 队伍徽标：同一队伍的队员在座位上显示同一个短码，一眼看出谁是一起来的
            if (partyService != null && !p.isAi() && p.getIdentityId() != null) {
                dto.setPartyId(partyService.partyIdOf(p.getIdentityId()));
            }
            playerDtos.add(dto);
        }
        data.setPlayers(playerDtos);
        data.setSpectatorCount(spectators.size());
        // 观战者昵称也是公开信息：房间里应该能看到"谁在看"
        List<String> spectatorNames = new ArrayList<>();
        for (Player sp : spectators) {
            if (sp.getName() != null) spectatorNames.add(sp.getName());
        }
        data.setSpectators(spectatorNames);
        data.setCurrentTurn(engine.getCurrentTurn());
        // 非棋盘玩法把状态挂在房间数据里，GAME_START 就能画出完整面板
        data.setView(engine.getViewState(viewerSide));
        data.setRematch(buildRematchState());
        applyVisibility(data);
        return data;
    }

    private void applyVisibility(RoomDataDto data) {
        data.setPrivateRoom(privateRoom);
        data.setAiSeats((int) players.stream().filter(Player::isAi).count());
        data.setHumanSeats((int) players.stream().filter(p -> !p.isAi()).count());
    }

    private GameSyncDto buildSyncData() {
        return buildSyncData(0);
    }

    private GameSyncDto buildSyncData(int viewerSide) {
        GameSyncDto data = new GameSyncDto();
        data.setGameType(gameType.name());
        data.setState(state.name());
        data.setRoomData(buildRoomData(viewerSide));
        // 只有棋盘类才填 board；非棋盘玩法（RPG/卡牌等）改填 view，
        // 前端按 gameType 选渲染模块，不会去读不存在的盘面。
        if (engine instanceof BoardGameEngine) {
            // 暗棋等隐藏信息玩法会覆写 visible 版本；绝不能把内部真实盘面直接下发。
            data.setBoard(((BoardGameEngine) engine).getVisibleBoardSnapshot());
        }
        data.setView(engine.getViewState(viewerSide));
        data.setMoveCount(engine.getMoveCount());
        data.setMoveSeq(engine.getMoveSeq());
        data.setTimers(buildTimerData());
        if (state == State.FINISHED && lastGameOverJson != null) {
            data.setGameOver(gson.fromJson(lastGameOverJson, GameMessage.class));
        }
        return data;
    }

    private ScoreDataDto buildScoreData() {
        ScoreDataDto data = new ScoreDataDto();
        List<ScoreItemDto> items = new ArrayList<>();
        for (Player p : players) {
            ScoreItemDto item = new ScoreItemDto(p.getId(), p.getName(), p.getWins(), p.getLosses(), p.getElo());
            // 带上本局 ELO 变化，前端据此在每位玩家名下显示 +/- 分
            item.setEloChange(p.getLastEloChange());
            items.add(item);
        }
        data.setScores(items);
        return data;
    }

    public void broadcastRoomInfo() {
        // 房间信息也带 view（玩家列表/血量/手牌数），必须按视角分别发送
        for (Player p : players) {
            if (departed.contains(p.getId())) continue;
            GameMessage msg = new GameMessage(GameMessage.Type.ROOM_INFO);
            msg.setRoomId(roomId);
            msg.setGameType(gameType.name());
            msg.setData(gson.toJson(buildRoomData(p.getStone())));
            p.sendMessage(gson.toJson(msg));
        }
        GameMessage specMsg = new GameMessage(GameMessage.Type.ROOM_INFO);
        specMsg.setRoomId(roomId);
        specMsg.setGameType(gameType.name());
        specMsg.setData(gson.toJson(buildRoomData(0)));
        String specJson = gson.toJson(specMsg);
        broadcastSpectators(specJson);
    }

    void refreshPartyBadges() {
        lock.readLock().lock();
        try { broadcastRoomInfo(); }
        finally { lock.readLock().unlock(); }
    }

    public GameSyncDto buildSyncDataForReconnect(Player player) {
        lock.readLock().lock();
        try {
            int viewerSide = player != null && players.contains(player) && !departed.contains(player.getId())
                    ? player.getStone() : 0;
            return buildSyncData(viewerSide);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 重连恢复席位
     */
    public void replacePlayer(Player oldPlayer, Player newPlayer) {
        lock.writeLock().lock();
        try {
            int idx = players.indexOf(oldPlayer);
            if (idx >= 0) players.set(idx, newPlayer);
            int spectatorIndex = spectators.indexOf(oldPlayer);
            if (spectatorIndex >= 0) spectators.set(spectatorIndex, newPlayer);
            if (currentPlayer == oldPlayer) currentPlayer = newPlayer;
            if (winner == oldPlayer) winner = newPlayer;

            cancelForfeit(oldPlayer.getId());
            playerDisconnectAt.remove(oldPlayer.getId());

            if (oldPlayer.getConnection() != null) channelGroup.remove(oldPlayer.getConnection());
            if (newPlayer.isConnected()) channelGroup.add(newPlayer.getConnection());

            broadcastPlayerStatus(newPlayer.getName() + " 已重连", newPlayer);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 玩家断线超过会话 TTL 后由 RoomManager 调用，强制清理
     */
    public boolean hasPlayer(Player player) {
        return players.contains(player) || spectators.contains(player);
    }

    public void notifyShutdown() {
        cancelTurnTimer();
        cancelForfeits();
        cancelRematchTimeout();
        GameMessage msg = new GameMessage(GameMessage.Type.ERROR);
        msg.setMessage("服务器正在关闭，请稍后重连");
        broadcastMessage(gson.toJson(msg));
    }

    public static void shutdownAIExecutor() {
        aiExecutor.shutdown();
        try {
            if (!aiExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                aiExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            aiExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // Getters
    public String getRoomId() { return roomId; }
    public GameType getGameType() { return gameType; }
    public GameEngine getEngine() { return engine; }
    public State getState() { return state; }
    public List<Player> getPlayers() { return players; }
    public List<Player> getSpectators() { return spectators; }
    public int getPlayerCount() { return players.size(); }
    public int getSpectatorCount() { return spectators.size(); }
    public int getTargetPlayers() { return targetPlayers; }
    /** 当前重开截止时间；无等待时为 0。 */
    public long getRematchDeadline() {
        lock.readLock().lock();
        try { return rematchExpiresAt; }
        finally { lock.readLock().unlock(); }
    }
    /** 当前已准备重开的真人玩家数（电脑不计入）。 */
    public int getRestartVoteCount() {
        lock.readLock().lock();
        try { return restartVotes.size(); }
        finally { lock.readLock().unlock(); }
    }
    public boolean isFull() { return players.size() >= targetPlayers; }
    public int getAiDifficulty() { return aiDifficulty; }
}

