package com.gomoku.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 回合制 RPG 对战引擎 — 第一个非棋牌玩法
 *
 * 为什么选回合制而不是实时动作类：房间流程本身就是「双方轮流出一手」，
 * 回合制 RPG 的战斗恰好是同一种语义（你一刀我一刀），所以房间、计时、ELO、
 * 存档、断线重连、AI 调度全都能原样复用，不需要引入帧同步/客户端预测那一套。
 *
 * 玩法要点：
 * - 双方各有 HP / MP，MP 每回合自动回复少量，保证战斗一定会结束
 * - 六种行动：普攻、强力一击、火球术、治疗术、防御、喝药水
 * - 状态：防御（一次伤害减免）、灼烧（自己回合开始掉血）
 * - 出手前先结算灼烧，因此「被烧死」的一方会在自己的回合倒下
 *
 * 行动指令：{@link MoveCommand#action(int)}，下标见 {@link Action#getIndex()}。
 */
public class RpgBattleEngine implements GameEngine {

    /** 初始属性 */
    static final int MAX_HP = 100;
    static final int MAX_MP = 40;
    static final int BASE_ATK = 18;
    static final int BASE_DEF = 10;
    /** 每回合自动回复的 MP */
    static final int MP_REGEN = 3;
    /** 初始药水数量 */
    static final int START_POTIONS = 2;

    /**
     * 进入「决战阶段」的回合数（一回合 = 双方各一手）。
     *
     * 这个机制不是为了好看，而是修一个实测出来的死循环：
     * 治疗 35 点 + 每回合回蓝 3 点，使「互相加血」的净收益高于伤害，
     * 两个按规则出招的 AI 可以打到 500 手都不死。加决战阶段（伤害 ×1.5、治疗 ×0.5）
     * 让治疗再也追不上伤害，战斗必然收敛。
     */
    static final int SUDDEN_DEATH_ROUND = 25;

    /** 硬上限：即便双方都在拖，到这个手数也强制结算，保证对局不会无限长 */
    static final int MAX_MOVES = 240;

    /** 回放用的行动编码：moveHistory 里每手是 {side, action, value} */
    public static final int HIST_SIDE = 0;
    public static final int HIST_ACTION = 1;
    public static final int HIST_VALUE = 2;

    /**
     * 可选行动。index 即客户端发来的 action 序号，改动顺序会让旧回放错位，
     * 所以新行动只能往后追加。
     */
    public enum Action {
        ATTACK(0, "普通攻击", 0, "造成基础物理伤害"),
        HEAVY(1, "强力一击", 8, "伤害提升 80%，消耗 8 MP"),
        FIREBALL(2, "火球术", 12, "无视一半防御并附加 2 回合灼烧，消耗 12 MP"),
        HEAL(3, "治疗术", 14, "恢复 22 点生命，消耗 14 MP"),
        DEFEND(4, "防御", 0, "本回合伤害减免 60%，并回复 6 MP"),
        POTION(5, "喝药水", 0, "消耗 1 瓶药水，恢复 40 点生命");

        private final int index;
        private final String label;
        private final int mpCost;
        private final String desc;

        Action(int index, String label, int mpCost, String desc) {
            this.index = index;
            this.label = label;
            this.mpCost = mpCost;
            this.desc = desc;
        }

        public int getIndex() { return index; }
        public String getLabel() { return label; }
        public int getMpCost() { return mpCost; }
        public String getDesc() { return desc; }

        public static Action of(int index) {
            for (Action a : values()) {
                if (a.index == index) return a;
            }
            return null;
        }
    }

    /** 一方的战斗状态 */
    static final class Fighter {
        final int side;
        String name;
        int hp, mp, atk, def;
        int potions;
        /** 防御状态：本次受到的伤害减免，结算一次后清除 */
        boolean shield;
        /** 灼烧剩余回合数 */
        int burn;

        Fighter(int side, String name) {
            this.side = side;
            this.name = name;
            this.hp = MAX_HP;
            this.mp = MAX_MP;
            this.atk = BASE_ATK;
            this.def = BASE_DEF;
            this.potions = START_POTIONS;
        }

        boolean alive() { return hp > 0; }
    }

    private final Fighter[] fighters = new Fighter[3]; // 下标 1、2 有效
    private final List<int[]> history = new ArrayList<>();
    private final List<int[]> lastEffects = new ArrayList<>();
    /** 最近几条战斗日志，直接给前端滚动显示 */
    private final List<String> log = new ArrayList<>();

    private int currentTurn = 1;
    private int moveSeq = 0;
    private boolean over = false;
    private Integer winner;
    /** 决战阶段只播报一次，避免日志刷屏 */
    private boolean suddenDeathAnnounced = false;
    /** 本手是否处于决战阶段（按「即将落下的这一手」算，所以在出手前更新） */
    private boolean suddenDeathFlag = false;
    private final Random random;

    public RpgBattleEngine() {
        this(new Random());
    }

    /** 供测试注入固定种子，让伤害计算可复现 */
    RpgBattleEngine(Random random) {
        this.random = random;
        reset();
    }

    @Override
    public GameType type() {
        return GameType.RPG;
    }

    @Override
    public void reset() {
        fighters[1] = new Fighter(1, "红方");
        fighters[2] = new Fighter(2, "蓝方");
        history.clear();
        lastEffects.clear();
        log.clear();
        currentTurn = 1;
        moveSeq = 0;
        over = false;
        winner = null;
        suddenDeathAnnounced = false;
        suddenDeathFlag = false;
        log.add("战斗开始");
    }

    /** 玩家名由房间注入，让日志和结算文案里出现真实昵称 */
    @Override
    public void setPlayerName(int side, String name) {
        setFighterName(side, name);
    }

    /** 保留旧方法名（既有测试在用） */
    public void setFighterName(int side, String name) {
        Fighter f = fighter(side);
        if (f != null && name != null && !name.isEmpty()) {
            f.name = name;
        }
    }

    @Override
    public boolean applyMove(MoveCommand command, int side) {
        if (command == null || !command.hasAction()) return false;
        if (over) return false;
        Fighter actor = fighter(side);
        if (actor == null || !actor.alive()) return false;
        if (currentTurn != side) return false;

        Action action = Action.of(command.getAction());
        if (action == null) return false;
        if (!canUse(side, action)) return false;

        lastEffects.clear();
        moveSeq++;

        // 决战阶段按「即将落下的这一手」判定，所以要在出手前更新标记，
        // 否则本手的伤害会沿用上一手的状态，边界上差一手。
        suddenDeathFlag = history.size() + 1 > SUDDEN_DEATH_ROUND * 2;

        // 回合开始：先结算灼烧与 MP 回复。灼烧可能直接把人烧死，
        // 这时仍然算「这一手已走」，否则手数会对不上客户端的 moveSeq。
        tickTurnStart(actor);

        if (suddenDeath() && !suddenDeathAnnounced) {
            suddenDeathAnnounced = true;
            log.add("第 " + round() + " 回合进入决战阶段：伤害提升，治疗效果减半");
        }

        int value = 0;
        if (actor.alive()) {
            value = perform(actor, action);
        } else {
            log.add(actor.name + " 被灼烧击倒");
        }

        history.add(new int[]{side, action.getIndex(), value});
        settle();
        return true;
    }

    /** 回合开始结算：灼烧掉血、回蓝、清防御 */
    private void tickTurnStart(Fighter actor) {
        actor.shield = false;
        if (actor.burn > 0) {
            int dmg = 6;
            actor.burn--;
            actor.hp = Math.max(0, actor.hp - dmg);
            lastEffects.add(new int[]{actor.side, actor.hp});
            log.add(actor.name + " 受到灼烧 " + dmg + " 点伤害（剩余 " + actor.burn + " 回合）");
        }
        if (actor.mp < MAX_MP) {
            actor.mp = Math.min(MAX_MP, actor.mp + MP_REGEN);
        }
    }

    /** 执行行动，返回本手的主要数值（伤害或治疗量），供回放展示 */
    private int perform(Fighter actor, Action action) {
        Fighter foe = fighter(3 - actor.side);
        switch (action) {
            case ATTACK: {
                int dmg = damage(actor, foe, 1.0, 0.5);
                return hit(actor, foe, dmg, "普通攻击");
            }
            case HEAVY: {
                actor.mp -= action.getMpCost();
                int dmg = damage(actor, foe, 1.8, 0.5);
                return hit(actor, foe, dmg, "强力一击");
            }
            case FIREBALL: {
                actor.mp -= action.getMpCost();
                int dmg = damage(actor, foe, 1.3, 0.25);
                int dealt = hit(actor, foe, dmg, "火球术");
                if (foe.alive()) {
                    foe.burn = Math.max(foe.burn, 2);
                    log.add(foe.name + " 被点燃（2 回合）");
                }
                return dealt;
            }
            case HEAL: {
                actor.mp -= action.getMpCost();
                int before = actor.hp;
                actor.hp = Math.min(MAX_HP, actor.hp + healAmount(22));
                int healed = actor.hp - before;
                lastEffects.add(new int[]{actor.side, actor.hp});
                log.add(actor.name + " 治疗恢复 " + healed + " 点生命");
                return healed;
            }
            case DEFEND: {
                actor.shield = true;
                actor.mp = Math.min(MAX_MP, actor.mp + 6);
                log.add(actor.name + " 进入防御姿态");
                return 0;
            }
            case POTION: {
                actor.potions--;
                int before = actor.hp;
                actor.hp = Math.min(MAX_HP, actor.hp + healAmount(40));
                int healed = actor.hp - before;
                lastEffects.add(new int[]{actor.side, actor.hp});
                log.add(actor.name + " 喝下药水恢复 " + healed + " 点生命");
                return healed;
            }
            default:
                return 0;
        }
    }

    /** 结算一次伤害并写入日志，返回实际造成的伤害 */
    private int hit(Fighter actor, Fighter foe, int dmg, String actionLabel) {
        if (foe.shield) {
            dmg = Math.max(1, (int) Math.round(dmg * 0.4));
            log.add(foe.name + " 的防御姿态吸收了大部分伤害");
        }
        foe.hp = Math.max(0, foe.hp - dmg);
        lastEffects.add(new int[]{foe.side, foe.hp});
        log.add(actor.name + " 使用 " + actionLabel + "，对 " + foe.name + " 造成 " + dmg + " 点伤害");
        return dmg;
    }

    /**
     * 伤害公式：攻击力 × 倍率 − 防御 × 防御系数，再叠加 ±15% 浮动，最低 1 点。
     * 浮动用同一个 Random 实例，测试里注入固定种子即可复现。
     * 决战阶段额外 ×1.5，确保治疗追不上伤害。
     */
    private int damage(Fighter actor, Fighter foe, double mult, double defFactor) {
        double base = actor.atk * mult - foe.def * defFactor;
        double jitter = 0.85 + random.nextDouble() * 0.3;
        double scale = suddenDeath() ? 1.5 : 1.0;
        return Math.max(1, (int) Math.round(base * jitter * scale));
    }

    /** 治疗量在决战阶段减半，这是让战斗收敛的关键另一半 */
    private int healAmount(int base) {
        return suddenDeath() ? Math.max(1, base / 2) : base;
    }

    /** 是否已进入决战阶段 */
    public boolean suddenDeath() {
        return suddenDeathFlag;
    }

    /** 当前回合数（双方各一手算一回合），从 1 开始 */
    public int round() {
        return history.size() / 2 + 1;
    }

    /** 终局判定：一方倒下即结束；同一手不可能双亡，因为灼烧只结算出手方 */
    private void settle() {
        Fighter a = fighters[1];
        Fighter b = fighters[2];
        if (a.alive() && b.alive()) {
            // 硬上限：拖到这个手数就按剩余血量判定，避免无意义的超长对局
            if (history.size() >= MAX_MOVES) {
                over = true;
                if (a.hp == b.hp) winner = null;
                else winner = a.hp > b.hp ? 1 : 2;
                log.add("达到最大手数，按剩余生命判定：" + getScoreSummary());
                return;
            }
            currentTurn = 3 - currentTurn;
            return;
        }
        over = true;
        if (a.alive()) winner = 1;
        else if (b.alive()) winner = 2;
        else winner = null;
        log.add(winner == null ? "双方同归于尽，平局"
                : fighter(winner).name + " 获得胜利");
    }

    private Fighter fighter(int side) {
        return side >= 1 && side <= 2 ? fighters[side] : null;
    }

    /** 该方当前能否使用某个行动（MP / 药水是否够） */
    public boolean canUse(int side, Action action) {
        Fighter f = fighter(side);
        if (f == null || action == null) return false;
        if (f.mp < action.getMpCost()) return false;
        if (action == Action.POTION && f.potions <= 0) return false;
        return true;
    }

    public int getHp(int side) {
        Fighter f = fighter(side);
        return f == null ? 0 : f.hp;
    }

    public int getMp(int side) {
        Fighter f = fighter(side);
        return f == null ? 0 : f.mp;
    }

    public String getFighterName(int side) {
        Fighter f = fighter(side);
        return f == null ? "" : f.name;
    }

    @Override
    public int getCurrentTurn() {
        return currentTurn;
    }

    @Override
    public boolean isOver() {
        return over;
    }

    @Override
    public Integer getWinner() {
        return winner;
    }

    @Override
    public String getScoreSummary() {
        if (!over) return null;
        if (winner == null) {
            // 两种平局：同归于尽（双方都倒下）或达到最大手数时血量相同
            boolean bothAlive = fighters[1].alive() && fighters[2].alive();
            return bothAlive ? "双方生命值相同，平局" : "双方同归于尽";
        }
        Fighter w = fighter(winner);
        Fighter l = fighter(3 - winner);
        if (l.alive()) {
            return w.name + " 以剩余 " + w.hp + " 点生命获胜（达到最大手数）";
        }
        return w.name + " 以剩余 " + w.hp + " 点生命获胜（" + l.name + " 已倒下）";
    }

    @Override
    public List<int[]> getMoveHistory() {
        return new ArrayList<>(history);
    }

    @Override
    public List<int[]> getLastMoveEffects() {
        return new ArrayList<>(lastEffects);
    }

    @Override
    public int getMoveCount() {
        return history.size();
    }

    @Override
    public int getMoveSeq() {
        return moveSeq;
    }

    @Override
    public int getStepTimeSeconds() {
        return 30;
    }

    @Override
    public int getTotalTimeSeconds() {
        return 300;
    }

    /**
     * 客户端渲染所需的全部状态。
     *
     * 刻意不做「只给对手看的部分」这类隐藏信息：回合制对战没有手牌/暗置信息，
     * 双方看到的完全一致，前端因此不需要按视角做两套渲染。
     * （viewerSide 参数为统一接口而留，本玩法忽略它。）
     */
    @Override
    public Object getViewState(int viewerSide) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", "RPG");
        view.put("turn", currentTurn);
        view.put("over", over);
        view.put("winner", winner);
        view.put("round", round());
        view.put("suddenDeath", suddenDeath());

        List<Map<String, Object>> list = new ArrayList<>();
        for (int side = 1; side <= 2; side++) {
            Fighter f = fighter(side);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("side", side);
            m.put("name", f.name);
            m.put("hp", f.hp);
            m.put("maxHp", MAX_HP);
            m.put("mp", f.mp);
            m.put("maxMp", MAX_MP);
            m.put("potions", f.potions);
            m.put("shield", f.shield);
            m.put("burn", f.burn);
            m.put("alive", f.alive());
            list.add(m);
        }
        view.put("fighters", list);

        // 技能栏：带上可用性，前端直接照着色/禁用，不用重复算 MP 规则
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Action a : Action.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", a.getIndex());
            m.put("label", a.getLabel());
            m.put("mpCost", a.getMpCost());
            m.put("desc", a.getDesc());
            m.put("available", canUse(currentTurn, a));
            actions.add(m);
        }
        view.put("actions", actions);

        // 只给最近 12 条，日志无限增长会把同步包越撑越大
        int from = Math.max(0, log.size() - 12);
        view.put("log", new ArrayList<>(log.subList(from, log.size())));
        return view;
    }
}
