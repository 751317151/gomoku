package com.gomoku.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 卡牌对战引擎 — 第二个非棋类玩法，也是第一次出现「隐藏信息」
 *
 * 为什么选它来验证引擎抽象：RPG 双方信息完全对称，看不出 getViewState 的问题；
 * 卡牌的手牌只能自己看。如果抽象层不能按视角下发状态，这个玩法就做不对 ——
 * 所以它正好是检验通用引擎层是否真的够用的试金石。
 *
 * 玩法设计（刻意保持简单，重点是机制而非卡池深度）：
 * - 双方英雄 30 点生命，每回合获得递增法力水晶（上限 10）
 * - 手牌上限 7 张，每回合抽 1 张；牌库抽空则该玩家每回合受到 1 点疲劳伤害
 * - 卡牌三类：随从（站场，可攻击）、法术（即时效果）、治疗
 * - 出牌后若随从可攻击，本回合可以攻击一次（简化：出牌阶段后进入攻击）
 * - 回合结束自动进入对手回合
 *
 * 出牌指令：{@link MoveCommand#action(int)}，action = 手牌下标。
 */
public class CardBattleEngine implements GameEngine {

    /** 英雄初始生命 */
    static final int MAX_HP = 30;
    /** 手牌上限 */
    static final int MAX_HAND = 7;
    /** 法力水晶上限 */
    static final int MAX_MANA = 10;
    /**
     * 开局法力。
     *
     * 注意这里**不是**靠调大它来保证「起手总有牌可打」——
     * 那个保证由 {@link #drawOpeningHand} 结构性提供（起手全高费时换一张便宜的进来）。
     * 否则每加一张高费卡都要重调这个值，历史上已经调过 1→2→3→4。
     */
    static final int OPENING_MANA = 3;
    /** 每方场上随从上限，防止无限铺场 */
    static final int MAX_BOARD = 7;
    /** 硬上限，保证对局收敛 */
    static final int MAX_MOVES = 300;

    /** 回放编码：{side, action, value} */
    public static final int HIST_SIDE = 0;
    public static final int HIST_ACTION = 1;
    public static final int HIST_VALUE = 2;

    /** 自定义套牌必须正好这么多张 */
    public static final int DECK_SIZE = 30;
    /** 同名卡最多带几张（防止全带同一张） */
    public static final int MAX_COPIES = 6;

    /** 嘲讽：对手必须先攻击它 */
    static final int TAUNT = 1;
    /** 冲锋：召唤当回合即可攻击（否则有召唤失调） */
    static final int CHARGE = 2;
    /** 战吼：召唤时立即触发一次效果 */
    static final int BATTLECRY = 4;
    /** 亡语：死亡时触发一次效果 */
    static final int DEATHRATTLE = 8;
    /** 圣盾：抵挡第一次伤害（抵挡后消失） */
    static final int DIVINE_SHIELD = 16;
    /** 衍生物：只能被召唤出来，不进入牌库 */
    static final int TOKEN = 32;
    /** 吸血：造成伤害时，自己恢复等量生命 */
    static final int LIFESTEAL = 64;
    /** 连击：若本回合已出过别的牌，则获得加成（+2 攻击） */
    static final int COMBO = 128;

    /**
     * 卡牌定义。index 会出现在回放里，只能往后追加。
     */
    public enum Card {
        // 随从：atk/hp > 0
        // 关键词：TAUNT=嘲讽（对手必须先打它）、CHARGE=冲锋（召唤当回合即可攻击）
        //
        // 加关键词的直接原因：上一轮做了「手动选攻击目标」，但没有嘲讽时
        // 打脸永远是最优解，选目标这个交互等于无效 —— 玩法需要一个
        // 强制攻击随从的机制才有决策空间。
        FOOTMAN(0, "步兵", 1, 1, 2, "1/2 随从", 0),
        ARCHER(1, "弓箭手", 2, 2, 1, "2/1 冲锋", CHARGE),
        KNIGHT(2, "骑士", 3, 3, 3, "3/3 随从", 0),
        GUARDIAN(3, "守卫", 4, 2, 5, "2/5 嘲讽", TAUNT),
        DRAGON(4, "幼龙", 6, 6, 5, "6/5 强力随从", 0),
        // 法术：atk/hp = 0
        FIREBALL(5, "火球术", 3, 0, 0, "造成 4 点伤害", 0),
        HEAL(6, "治疗术", 2, 0, 0, "恢复 5 点生命", 0),
        CLEAVE(7, "横扫", 5, 0, 0, "对所有敌方随从造成 3 点伤害", 0),
        BUFF(8, "鼓舞", 2, 0, 0, "使一个己方随从获得 +2/+2", 0),
        // 第二轮新增：两个关键词各一张，效果都是「对敌方英雄造成伤害」，
        // 不需要选目标，交互最简单（选目标型战吼留给下一轮）
        BERSERKER(9, "狂战士", 4, 4, 3, "4/3 战吼：对敌方英雄造成 2 点伤害", BATTLECRY),
        BONE_DRAGON(10, "骨龙", 5, 4, 4, "4/4 亡语：对敌方英雄造成 3 点伤害", DEATHRATTLE),
        // 第三轮新增：圣盾与亡语召唤
        // 圣盾需要一个统一的"伤害入口"，否则火球能穿盾、普攻不能，规则会自相矛盾
        SHIELD_GUARD(11, "圣盾卫兵", 4, 3, 2, "3/2 圣盾·嘲讽", DIVINE_SHIELD | TAUNT),
        // 亡语召唤需要"衍生物"概念：召唤出来的随从不能进牌库
        SKELETON(12, "骷髅", 3, 2, 2, "2/2 亡语：召唤 1/1 幼狼", DEATHRATTLE),
        WOLF_CUB(13, "幼狼", 0, 1, 1, "1/1 衍生物", TOKEN),
        // 第四轮扩充：两个新关键词各两张 + 一张纯数值卡
        VAMPIRE(14, "吸血鬼", 4, 3, 4, "3/4 吸血", LIFESTEAL),
        VAMPIRE_LORD(15, "血族领主", 7, 6, 6, "6/6 吸血", LIFESTEAL),
        SWORDSMAN(16, "剑客", 3, 2, 4, "2/4 连击（本回合已出牌则 +2 攻）", COMBO),
        ASSASSIN(17, "刺客", 5, 3, 3, "3/3 连击 + 冲锋", COMBO | CHARGE),
        SHIELD_BEARER(18, "持盾者", 2, 1, 4, "1/4 嘲讽", TAUNT),
        // 第五轮：高费区补强。
        // 之前 6 费以上只有幼龙一张，对局拖到后期没有"终结手段"，
        // 双方只能靠小随从互相磨 —— 这几张就是给后期准备的。
        WAR_TITAN(19, "战争泰坦", 8, 9, 9, "9/9 圣盾·嘲讽", DIVINE_SHIELD | TAUNT),
        NECROMANCER(20, "死灵法师", 6, 5, 5, "5/5 亡语：召唤 3/3 骷髅战士", DEATHRATTLE),
        BLOOD_QUEEN(21, "血皇后", 7, 5, 6, "5/6 吸血·连击", LIFESTEAL | COMBO),
        SKELETON_WARRIOR(22, "骷髅战士", 0, 3, 3, "3/3 衍生物", TOKEN);

        private final int index;
        private final String label;
        private final int cost;
        private final int atk;
        private final int hp;
        private final String desc;
        private final int keywords;

        Card(int index, String label, int cost, int atk, int hp, String desc, int keywords) {
            this.index = index;
            this.label = label;
            this.cost = cost;
            this.atk = atk;
            this.hp = hp;
            this.desc = desc;
            this.keywords = keywords;
        }

        public int getIndex() { return index; }
        public String getLabel() { return label; }
        public int getCost() { return cost; }
        public int getAtk() { return atk; }
        public int getHp() { return hp; }
        public String getDesc() { return desc; }
        public boolean isMinion() { return hp > 0; }
        public boolean hasTaunt() { return (keywords & TAUNT) != 0; }
        public boolean hasCharge() { return (keywords & CHARGE) != 0; }
        public boolean hasBattlecry() { return (keywords & BATTLECRY) != 0; }
        public boolean hasDeathrattle() { return (keywords & DEATHRATTLE) != 0; }
        public boolean hasDivineShield() { return (keywords & DIVINE_SHIELD) != 0; }
        public boolean hasLifesteal() { return (keywords & LIFESTEAL) != 0; }
        public boolean hasCombo() { return (keywords & COMBO) != 0; }
        /** 衍生物：只能由效果召唤，不进入牌库 */
        public boolean isToken() { return (keywords & TOKEN) != 0; }

        /** 出这张牌是否需要玩家指定目标（前端据此进入"点目标"模式） */
        public boolean needsTarget() {
            return this == FIREBALL || this == BUFF;
        }

        public static Card of(int index) {
            for (Card c : values()) {
                if (c.index == index) return c;
            }
            return null;
        }
    }

    /**
     * 套牌预组。
     *
     * 为什么要有预组：之前双方永远抽同一套 30 张牌，每局手感都一样，
     * 「选牌」这个卡牌游戏最核心的决策完全缺失。预组用最小成本补上这一层 ——
     * 玩家不需要组牌，但能在开局前选一个风格，对局节奏立刻不同。
     *
     * 三套风格差异落在曲线和卡种上：
     * - 均衡：攻守兼备，什么都有
     * - 快攻：低费随从 + 直伤，抢前期节奏
     * - 控制：清场 + 治疗 + 大随从，拖后期
     *
     * 每套必须正好 30 张（buildDeck 有构建期自检）。
     */
    public enum Deck {
        BALANCED("均衡", "攻守兼备，适合上手"),
        AGGRO("快攻", "低费铺场 + 直伤，抢节奏"),
        CONTROL("控制", "清场、治疗与大随从，拖后期"),
        /** 玩家自己配的 30 张（配比存在 CardBattleEngine#customDecks） */
        CUSTOM("自定义", "自己挑选的 30 张");

        private final String label;
        private final String desc;

        Deck(String label, String desc) {
            this.label = label;
            this.desc = desc;
        }

        public String getLabel() { return label; }
        public String getDesc() { return desc; }

        /** 从客户端传来的名字解析，未知/空 → 均衡（向后兼容旧客户端） */
        public static Deck fromName(String name) {
            if (name == null || name.isEmpty()) return BALANCED;
            try {
                return valueOf(name.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return BALANCED;
            }
        }
    }

    /** 场上随从实例（同一张牌可以有多个实例，各自独立生命） */
    static final class Minion {
        Card card;
        int atk;
        int hp;
        /** 本回合是否已攻击 */
        boolean attacked;
        /**
         * 是否是本回合刚召唤的。
         * 非冲锋随从有「召唤失调」：召唤当回合不能攻击。
         * 之前所有随从都能立即攻击，卡面写着「冲锋」却毫无意义。
         */
        boolean summonedThisTurn;
        /**
         * 圣盾：抵挡第一次伤害后消失。
         * 注意它不是"减伤"，而是整次伤害归零 —— 所以必须在唯一的伤害入口里判断。
         */
        boolean divineShield;
        /**
         * 连击是否已激活：召唤时若本回合已经出过别的牌，则加成生效。
         * 这是"快攻/节奏"型关键词，奖励先铺场再上连击随从的顺序。
         */
        boolean comboActive;

        Minion(Card card) {
            this.card = card;
            this.atk = card.getAtk();
            this.hp = card.getHp();
            this.summonedThisTurn = true; // 召唤时置位，回合开始统一清除
            this.divineShield = card.hasDivineShield();
        }

        /** 当前能否攻击（未被攻击过 + 无召唤失调） */
        boolean canAttack() {
            if (attacked || !alive()) return false;
            return !summonedThisTurn || card.hasCharge();
        }

        boolean alive() { return hp > 0; }
    }

    /** 一方状态 */
    static final class PlayerState {
        final int side;
        String name;
        int hp;
        int mana;
        int maxMana;
        int fatigue;
        final List<Card> hand = new ArrayList<>();
        final List<Minion> board = new ArrayList<>();
        /**
         * 各自独立的牌库。
         * 之前双方共用一个抽牌序列，导致「对手抽牌会消耗我的牌」——
         * 这是规则层面的错误，不只是实现简化（实测每人 40 张的预期完全对不上）。
         */
        final List<Card> deck = new ArrayList<>();

        PlayerState(int side, String name) {
            this.side = side;
            this.name = name;
            this.hp = MAX_HP;
            this.mana = 0;
            this.maxMana = 0;
            this.fatigue = 0;
        }

        boolean alive() { return hp > 0; }
    }

    private final PlayerState[] states = new PlayerState[3];
    private final List<int[]> history = new ArrayList<>();
    private final List<int[]> lastEffects = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final Random random;
    private int currentTurn = 1;
    private int moveSeq = 0;
    private boolean over = false;
    private Integer winner;
    /**
     * 本回合开始时的 history 长度。
     *
     * 连击判定不能用「history 最后一条是否同 side」—— 新回合开始时，
     * 最后一条仍是自己上一回合打出的牌，会误判为"本回合已出牌"（实测连击跨回合泄漏）。
     * 用「本回合内是否新增过记录」才是正确判据。
     */
    private int turnStartHistorySize = 0;
    /**
     * 双方选择的套牌（下标 1、2 有效）。
     *
     * 存在引擎上而不是 PlayerState 上：reset() 会重建 PlayerState，
     * 而「这一局双方打什么套牌」是跨 reset 的配置，应该在 reset 之前就定下来。
     */
    private final Deck[] chosenDecks = new Deck[]{null, Deck.BALANCED, Deck.BALANCED};
    /**
     * 双方的自定义配比（下标 1、2 有效）。仅当 chosenDecks[side] == CUSTOM 时使用。
     * 由房间在开局前注入，已经过 {@link #validateCustomDeck} 校验。
     */
    private final Map<Card, Integer>[] customDecks = new Map[]{null, null, null};
    /**
     * 本局实际建库用的配比（下标 1、2 有效）。
     *
     * 与 {@link #customDecks} 的区别：customDecks 是玩家**提交**的配比，
     * 而这里是 {@link #buildDeck} 真正使用的配比。自定义配比非法时后者会回落均衡，
     * 对局记录必须以这里为准，否则会把「回落均衡」写成「自定义」。
     */
    private final Map<Card, Integer>[] builtDeckCounts = new Map[]{null, null, null};

    public CardBattleEngine() {
        this(new Random());
    }

    /** 供测试注入固定种子 */
    CardBattleEngine(Random random) {
        this.random = random;
        reset();
    }

    /**
     * 测试专用：构造一个手牌只有指定一张、法力充足的引擎。
     *
     * 用途是把 AI 的决策路径钉死 —— 正常随机起手会让 AI 选到别的牌，
     * 导致「AI 会不会选对目标」这个断言变成碰运气。
     */
    static CardBattleEngine forAiTestWithSingleCard(Random random, Card card, int mana) {
        CardBattleEngine e = new CardBattleEngine(random);
        e.states[1].hand.clear();
        e.states[1].hand.add(card);
        e.states[1].mana = mana;
        return e;
    }

    @Override
    public GameType type() {
        return GameType.CARD;
    }

    @Override
    public void reset() {
        states[1] = new PlayerState(1, "红方");
        states[2] = new PlayerState(2, "蓝方");
        history.clear();
        lastEffects.clear();
        log.clear();
        currentTurn = 1;
        moveSeq = 0;
        over = false;
        winner = null;
        turnStartHistorySize = 0;

        // 各自独立牌库（先建库再抽起手牌）
        buildDeck(states[1]);
        buildDeck(states[2]);

        // 先手方 3 张、后手方 4 张（补偿后手）
        drawOpeningHand(states[1], 3);
        drawOpeningHand(states[2], 4);
        startMatch();
        log.add("战斗开始");
    }

    /**
     * 给某一方构建牌库：两只牌库各自独立洗牌。
     *
     * 每人 40 张（30 张起始牌 + 10 张后手补偿不需要，双方对称），
     * 保证对局中期才会进入疲劳阶段，而不是十来回合就开始掉血。
     */
    /**
     * 按某一方选定的套牌构建牌库。
     *
     * 用 Map 而不是数组 + 下标：加卡时不会因为 counts 长度对不上而越界，
     * 也不会出现「新卡忘了配张数就静默为 0」的问题。
     * 三套预组的张数都必须正好 30（下面有构建期自检）。
     */
    private void buildDeck(PlayerState s) {
        s.deck.clear();
        Deck preset = getDeck(s.side);
        Map<Card, Integer> counts;
        if (preset == Deck.CUSTOM && customDecks[s.side] != null) {
            counts = customDecks[s.side];
        } else {
            // CUSTOM 但没有配比（不该发生）→ 回落均衡，保证对局能开
            if (preset == Deck.CUSTOM) {
                // 同步纠正「选择」本身：否则 getDeck() 仍报 CUSTOM，
                // 对局记录会把实际打的均衡套牌写成自定义。
                preset = Deck.BALANCED;
                chosenDecks[s.side] = Deck.BALANCED;
            }
            counts = deckCounts(preset);
        }
        builtDeckCounts[s.side] = new LinkedHashMap<>(counts);

        for (Map.Entry<Card, Integer> e : counts.entrySet()) {
            if (e.getKey().isToken()) continue; // 双保险：衍生物绝不进牌库
            for (int k = 0; k < e.getValue(); k++) {
                s.deck.add(e.getKey());
            }
        }
        // 构建期自检：牌库必须正好 30 张。
        // 加卡时最容易犯的错就是张数配不平（本轮就写出过 32 张），
        // 放在这里 assert 比等单测发现更早、更直接。
        if (s.deck.size() != 30) {
            throw new IllegalStateException("套牌 " + preset + " 的张数必须为 30，实际 "
                    + s.deck.size() + "（检查 deckCounts 配比）");
        }
        Collections.shuffle(s.deck, random);
    }

    /** 三套预组的配比表。每套都必须正好 30 张。 */
    static Map<Card, Integer> deckCounts(Deck preset) {
        Map<Card, Integer> c = new LinkedHashMap<>();
        switch (preset) {
            case AGGRO:
                // 低费随从为主 + 直伤，曲线压到 1-3 费
                c.put(Card.FOOTMAN, 3);
                c.put(Card.ARCHER, 5);
                c.put(Card.KNIGHT, 3);
                c.put(Card.BERSERKER, 3);
                c.put(Card.FIREBALL, 4);
                c.put(Card.BUFF, 2);
                c.put(Card.GUARDIAN, 1);
                c.put(Card.SKELETON, 2);
                c.put(Card.SWORDSMAN, 3);
                c.put(Card.SHIELD_BEARER, 2);
                c.put(Card.ASSASSIN, 1);
                c.put(Card.BLOOD_QUEEN, 1); // 中期威胁：连击吸血
                break;
            case CONTROL:
                // 清场/治疗/大随从，曲线整体偏高（合计 30 张）
                c.put(Card.WAR_TITAN, 1);    // 终极防线：9/9 圣盾嘲讽
                c.put(Card.NECROMANCER, 1);  // 亡语续航
                c.put(Card.DRAGON, 2);
                c.put(Card.VAMPIRE_LORD, 2);
                c.put(Card.VAMPIRE, 2);
                c.put(Card.BONE_DRAGON, 2);
                c.put(Card.GUARDIAN, 3);
                c.put(Card.SHIELD_GUARD, 2);
                c.put(Card.HEAL, 4);
                c.put(Card.CLEAVE, 3);
                c.put(Card.FIREBALL, 3);
                c.put(Card.FOOTMAN, 3);
                c.put(Card.KNIGHT, 2);
                break;
            case BALANCED:
            default:
                c.put(Card.WAR_TITAN, 1);   // 一张终结手段
                c.put(Card.NECROMANCER, 1);
                c.put(Card.BLOOD_QUEEN, 1);
                c.put(Card.FOOTMAN, 2);
                c.put(Card.ARCHER, 2);
                c.put(Card.KNIGHT, 2);
                c.put(Card.GUARDIAN, 2);
                c.put(Card.DRAGON, 1);
                c.put(Card.FIREBALL, 2);
                c.put(Card.HEAL, 2);
                c.put(Card.CLEAVE, 1);
                c.put(Card.BUFF, 2);
                c.put(Card.BERSERKER, 2);
                c.put(Card.BONE_DRAGON, 1);
                c.put(Card.SHIELD_GUARD, 2);
                c.put(Card.SKELETON, 1);
                c.put(Card.VAMPIRE, 2);
                c.put(Card.SWORDSMAN, 2);
                c.put(Card.SHIELD_BEARER, 1);
                break;
        }
        return c;
    }

    /** 玩家名由房间注入 */
    /**
     * 设定某一方使用的套牌。必须在 {@link #reset()} 之前调用 ——
     * reset 会按这个选择重新建库并抽起手牌。
     */
    public void setDeck(int side, Deck deck) {
        if (side >= 1 && side <= 2 && deck != null) {
            chosenDecks[side] = deck;
        }
    }

    /**
     * 校验一份自定义配比是否合法。
     *
     * 规则（都很直白，但必须服务端校验 —— 客户端可以做 UI 约束，但不能信任）：
     * - 正好 {@link #DECK_SIZE} 张
     * - 不能包含衍生物（只能被召唤）
     * - 单卡不超过 {@link #MAX_COPIES} 张
     * - 张数不能为负
     *
     * @return null 表示合法；否则返回面向玩家的错误说明
     */
    public static String validateCustomDeck(Map<Card, Integer> counts) {
        if (counts == null || counts.isEmpty()) return "套牌不能为空";
        int total = 0;
        for (Map.Entry<Card, Integer> e : counts.entrySet()) {
            Card c = e.getKey();
            int n = e.getValue() == null ? 0 : e.getValue();
            if (c == null) return "包含未知卡牌";
            if (c.isToken()) return "衍生物「" + c.getLabel() + "」不能加入套牌";
            if (n < 0) return "「" + c.getLabel() + "」张数不能为负";
            if (n > MAX_COPIES) return "「" + c.getLabel() + "」最多 " + MAX_COPIES + " 张";
            total += n;
        }
        if (total != DECK_SIZE) return "套牌必须正好 " + DECK_SIZE + " 张，当前 " + total + " 张";
        return null;
    }

    /**
     * 设定某一方的自定义套牌。
     *
     * @return null 表示成功；否则为错误说明（调用方应回给客户端）
     */
    public String setCustomDeck(int side, Map<Card, Integer> counts) {
        String err = validateCustomDeck(counts);
        if (err != null) return err;
        if (side < 1 || side > 2) return "无效的一方";
        customDecks[side] = new LinkedHashMap<>(counts);
        chosenDecks[side] = Deck.CUSTOM;
        return null;
    }

    /** 读取某一方当前使用的套牌 */
    public Deck getDeck(int side) {
        return side >= 1 && side <= 2 && chosenDecks[side] != null
                ? chosenDecks[side] : Deck.BALANCED;
    }

    /**
     * 本局实际建库的配比摘要（仅自定义套牌需要，预组返回 null）。
     *
     * 返回形如「步兵×3 · 弓箭手×5 · 骑士×3…」的紧凑文本，用于对局记录展示
     * 「这局双方到底在打什么」。按费用升序排列，便于一眼看出曲线。
     */
    public String getDeckSummary(int side) {
        if (side < 1 || side > 2 || getDeck(side) != Deck.CUSTOM) return null;
        Map<Card, Integer> counts = builtDeckCounts[side];
        if (counts == null || counts.isEmpty()) return null;
        return counts.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .sorted(Comparator.comparingInt(e -> e.getKey().getCost()))
                .map(e -> e.getKey().getLabel() + "×" + e.getValue())
                .collect(java.util.stream.Collectors.joining(" · "));
    }

    @Override
    public void setPlayerName(int side, String name) {
        PlayerState s = state(side);
        if (s != null && name != null && !name.isEmpty()) {
            s.name = name;
        }
    }

    @Override
    public boolean applyMove(MoveCommand command, int side) {
        if (command == null || !command.hasAction()) return false;
        if (over) return false;
        PlayerState actor = state(side);
        if (actor == null || !actor.alive()) return false;
        if (currentTurn != side) return false;

        int handIndex = command.getAction();
        if (handIndex < 0 || handIndex >= actor.hand.size()) return false;

        Card card = actor.hand.get(handIndex);
        if (card.getCost() > actor.mana) return false;

        // 需要选目标的法术：必须先校验目标合法，再扣费/移牌。
        // 顺序很重要 —— 先扣再校验的话，非法目标会白白吃掉一张牌和法力。
        if (card.needsTarget() && !isValidTarget(actor, card, command)) return false;

        lastEffects.clear();
        moveSeq++;
        actor.mana -= card.getCost();
        actor.hand.remove(handIndex);

        int value = play(actor, card, command);
        history.add(new int[]{side, card.getIndex(), value});

        // 出牌后只在「一方倒下」或「达到硬上限」时才终局；
        // 回合不在这里结束 —— 卡牌是一回合多行动，玩家自己点「结束回合」。
        checkFinished();
        return true;
    }

    /** 只做终局判定，不换手 */
    private void checkFinished() {
        if (checkedOver()) return;
        if (history.size() >= MAX_MOVES) {
            over = true;
            PlayerState a = states[1];
            PlayerState b = states[2];
            winner = a.hp == b.hp ? null : (a.hp > b.hp ? 1 : 2);
            log.add("达到最大手数，按剩余生命判定：" + getScoreSummary());
        }
    }

    @Override
    public boolean autoEndsTurn() {
        return false;
    }

    /**
     * 结束本方回合：先让己方随从打出这一回合的攻击，再抽牌换手。
     * 这是玩家主动点「结束回合」时走的路径（也可以在法力耗尽后由前端提示）。
     */
    @Override
    public boolean endTurn(int side) {
        if (over || currentTurn != side) return false;
        PlayerState actor = state(side);
        if (actor == null) return false;

        lastEffects.clear();
        moveSeq++;

        autoAttack(actor);
        if (checkedOver()) return true;

        currentTurn = 3 - side;
        beginTurn(state(currentTurn));
        checkFinished();
        return true;
    }

    /**
     * 手动攻击：用己方第 minionIndex 个随从攻击对方英雄或指定随从。
     * action 编码沿用「下标」，但攻击用负偏移区分：
     *   action = ATTACK_BASE - targetIndex   （targetIndex = -1 表示打英雄）
     * 这样无需扩展协议就能表达「谁打谁」。
     */
    public boolean attack(int side, int minionIndex, int targetIndex) {
        if (over || currentTurn != side) return false;
        PlayerState actor = state(side);
        PlayerState foe = state(3 - side);
        if (actor == null || foe == null) return false;
        if (minionIndex < 0 || minionIndex >= actor.board.size()) return false;

        Minion attacker = actor.board.get(minionIndex);
        // 召唤失调 + 已攻击 都由 canAttack() 统一判断
        if (!attacker.canAttack()) return false;

        // 嘲讽：对方场上有嘲讽随从时，只能打嘲讽随从
        List<Integer> taunts = new ArrayList<>();
        for (int i = 0; i < foe.board.size(); i++) {
            if (foe.board.get(i).card.hasTaunt()) taunts.add(i);
        }
        if (!taunts.isEmpty() && targetIndex < 0) {
            return false; // 有嘲讽时不能打英雄
        }
        if (!taunts.isEmpty() && !taunts.contains(targetIndex)) {
            return false; // 有嘲讽时不能打非嘲讽随从
        }

        lastEffects.clear();
        moveSeq++;
        attacker.attacked = true;

        if (targetIndex < 0) {
            // 打英雄
            int dealt = Math.min(attacker.atk, foe.hp);
            foe.hp = Math.max(0, foe.hp - attacker.atk);
            log.add(attacker.card.getLabel() + " 攻击 " + foe.name + "，造成 " + dealt + " 点伤害");
            lastEffects.add(new int[]{foe.side, foe.hp});
            applyLifesteal(actor, attacker, dealt); // 打脸也能吸血
        } else {
            if (targetIndex >= foe.board.size()) return false;
            Minion target = foe.board.get(targetIndex);
            // 双向伤害都走统一入口：圣盾可能吃掉其中一次
            int dealtToTarget = damageMinion(target, attacker.atk);
            int dealtToAttacker = damageMinion(attacker, target.atk);
            // 吸血：造成多少伤害就回多少血（只有主动攻击方受益）
            applyLifesteal(actor, attacker, dealtToTarget);
            log.add(attacker.card.getLabel() + " 攻击 " + target.card.getLabel()
                    + "（各自受到 " + dealtToTarget + "/" + dealtToAttacker + " 伤害）");
            if (!target.alive()) {
                foe.board.remove(target);
                onMinionDeath(foe, target);
            }
            if (!attacker.alive()) {
                actor.board.remove(attacker);
                onMinionDeath(actor, attacker);
            }
        }

        history.add(new int[]{side, HISTORY_ATTACK, targetIndex});
        checkFinished();
        return true;
    }

    /** 历史里攻击行动的卡片编码（与真实卡牌下标区分开） */
    public static final int HISTORY_ATTACK = 100;

    /**
     * 该法术是否需要玩家选择目标。
     *
     * 需求差异来自玩法设计：火球需要选「打谁」（敌方英雄或随从），
     * 鼓舞需要选「强化哪个己方随从」。像横扫/治疗这种无差别的则不需要。
     */
    private static boolean cardNeedsTarget(Card card) {
        return card == Card.FIREBALL || card == Card.BUFF;
    }

    /** 该法术当前是否有合法目标可指定（没有就让前端置灰，避免点了没反应） */
    private boolean canTargetAnything(PlayerState actor, Card card) {
        PlayerState foe = state(3 - actor.side);
        if (card == Card.FIREBALL) {
            // 敌方英雄永远可打
            return foe != null;
        }
        if (card == Card.BUFF) {
            // 必须有己方随从
            return !actor.board.isEmpty();
        }
        return true;
    }

    /** 校验玩家给出的目标是否合法（在扣费之前调用） */
    private boolean isValidTarget(PlayerState actor, Card card, MoveCommand command) {
        PlayerState foe = state(3 - actor.side);
        int targetIndex = command != null && command.hasTarget() ? command.getTargetIndex() : Integer.MIN_VALUE;

        if (card == Card.FIREBALL) {
            // 目标必须属于对方：-1 = 敌方英雄，否则必须是对方场上存在的随从
            if (command == null || !command.hasTarget()) return false;
            if (command.getTargetSide() != foe.side) return false;
            return targetIndex == -1 || (targetIndex >= 0 && targetIndex < foe.board.size());
        }
        if (card == Card.BUFF) {
            // 鼓舞必须有己方随从可选择
            if (actor.board.isEmpty()) return false;
            if (command == null || !command.hasTarget()) return false;
            if (command.getTargetSide() != actor.side) return false;
            return targetIndex >= 0 && targetIndex < actor.board.size();
        }
        return true; // 其他牌不需要目标
    }

    /**
     * 本回合（当前行动方）是否已经出过牌。
     *
     * 卡牌是「一回合多行动」，所以同一回合内 history 里会有多条同 side 记录。
     * 通过「最后一条记录与当前 side 相同」判断是否已经动过。
     */
    private boolean playedThisTurn(int side) {
        // 本回合内已经有出牌记录 → 连击成立。
        // 注意判据是「本回合新增了记录」，不是「最后一条是谁的」。
        return history.size() > turnStartHistorySize && currentTurn == side;
    }

    /** 战吼结算（当前只有一张战吼牌：对敌方英雄造成伤害） */
    private void triggerBattlecry(PlayerState actor, Card card) {
        PlayerState foe = state(3 - actor.side);
        if (card == Card.BERSERKER) {
            int dmg = 2;
            foe.hp = Math.max(0, foe.hp - dmg);
            log.add(actor.name + " 的狂战士战吼对 " + foe.name + " 造成 " + dmg + " 点伤害");
            lastEffects.add(new int[]{foe.side, foe.hp});
        }
    }

    /**
     * 吸血结算：造成伤害的随从若带吸血，其主人恢复等量生命。
     *
     * 只按「实际造成的伤害」回血（被圣盾吸收时 dealt=0，就一点血都不回）——
     * 这个细节很重要，否则会出现「打圣盾也能吸血」的荒谬情况。
     */
    private void applyLifesteal(PlayerState owner, Minion attacker, int dealt) {
        if (dealt <= 0 || attacker == null || !attacker.card.hasLifesteal()) return;
        int before = owner.hp;
        owner.hp = Math.min(MAX_HP, owner.hp + dealt);
        int healed = owner.hp - before;
        if (healed > 0) {
            log.add(owner.name + " 的" + attacker.card.getLabel()
                    + "吸血恢复 " + healed + " 点生命");
            lastEffects.add(new int[]{owner.side, owner.hp});
        }
    }

    /**
     * 对随从造成伤害的**唯一入口**。
     *
     * 为什么必须集中：圣盾是「整次伤害归零」，不是减伤。
     * 如果散落在各个技能里判断，很容易漏掉某条路径 ——
     * 实测前车之鉴是亡语：用 removeIf 清尸体时拿不到被删对象，亡语就静默失效。
     * 同理，火球如果绕过这里，就会出现「火球穿盾、普攻不穿」的自相矛盾。
     *
     * @return 实际造成的伤害（圣盾抵挡时为 0）
     */
    private int damageMinion(Minion target, int amount) {
        if (target == null || amount <= 0) return 0;
        if (target.divineShield) {
            target.divineShield = false;
            log.add(target.card.getLabel() + " 的圣盾抵挡了 " + amount + " 点伤害");
            return 0;
        }
        target.hp -= amount;
        return amount;
    }

    /**
     * 统一处理一个随从的死亡（含亡语）。
     *
     * 必须集中在这里：移除随从的地方有 5 处（攻击、横扫、自动攻击、清理尸体…），
     * 任何一处直接 board.remove 都会让亡语静默失效。这是个很容易踩的坑 ——
     * 上一版就是先写了 removeIf 清理，再加亡语时漏了那条路径。
     *
     * @param owner 死亡随从所属的一方
     * @param dead  已确认 hp<=0 的随从（调用方负责从 board 移除）
     */
    private void onMinionDeath(PlayerState owner, Minion dead) {
        if (dead == null || dead.card == null) return;
        log.add(dead.card.getLabel() + " 被击杀");
        if (!dead.card.hasDeathrattle()) return;

        PlayerState foe = state(3 - owner.side);
        if (dead.card == Card.BONE_DRAGON) {
            int dmg = 3;
            foe.hp = Math.max(0, foe.hp - dmg);
            log.add(owner.name + " 的骨龙亡语对 " + foe.name + " 造成 " + dmg + " 点伤害");
            lastEffects.add(new int[]{foe.side, foe.hp});
        } else if (dead.card == Card.NECROMANCER) {
            if (owner.board.size() + 1 >= MAX_BOARD) {
                log.add(owner.name + " 的死灵法师亡语无处召唤（场上已满）");
                return;
            }
            Minion sk = new Minion(Card.SKELETON_WARRIOR);
            owner.board.add(sk);
            log.add(owner.name + " 的死灵法师亡语召唤了 3/3 骷髅战士");
        } else if (dead.card == Card.SKELETON) {
            // 亡语召唤：需要占一个场位。场上满了就不召唤，
            // 但要记日志说明，否则玩家会以为亡语没生效。
            // 注意：本方法在随从「已从 board 移除」之后调用，
            // 所以此刻 board.size() 比死亡前少 1。判断"死亡前是否已满"要加回 1。
            // 这个 off-by-one 实测会让满场时仍然召唤（7 → 死 1 → 6 → 误判未满 → 又召唤回 7）。
            if (owner.board.size() + 1 >= MAX_BOARD) {
                log.add(owner.name + " 的骷髅亡语无处召唤（场上已满）");
                return;
            }
            Minion cub = new Minion(Card.WOLF_CUB);
            owner.board.add(cub);
            log.add(owner.name + " 的骷髅亡语召唤了 1/1 幼狼");
        }
    }

    /** 打出一张牌，返回本手的主要数值（伤害/治疗/召攻数值），供回放展示 */
    private int play(PlayerState actor, Card card, MoveCommand command) {
        PlayerState foe = state(3 - actor.side);

        if (card.isMinion()) {
            if (actor.board.size() >= MAX_BOARD) {
                // 场上满了：牌回手，不消耗（引擎层已扣，这里补回）
                // 调用方已扣法力，为简单起见这里直接退化成无效果但仍消耗
                log.add(actor.name + " 场上已满，" + card.getLabel() + " 无法上场");
                return 0;
            }
            Minion m = new Minion(card);
            // 连击：本回合在这张之前已经出过牌 → 激活。
            // history 里记录的是本局所有手牌，用「本回合已出牌数 > 0」判断。
            if (card.hasCombo() && playedThisTurn(actor.side)) {
                m.comboActive = true;
                m.atk += 2; // 连击加成：+2 攻击
            }
            actor.board.add(m);
            log.add(actor.name + " 召唤了 " + card.getLabel() + "（" + m.atk + "/" + m.hp + "）");
            lastEffects.add(new int[]{actor.side, card.getIndex()});
            // 战吼在召唤后立即结算（不需要选目标的那一类）
            if (card.hasBattlecry()) {
                triggerBattlecry(actor, card);
            }
            return m.atk;
        }

        switch (card) {
            case FIREBALL: {
                // 火球可以指定「敌方英雄」或「敌方某个随从」
                int dmg = 4;
                int targetIndex = command != null && command.hasTarget() ? command.getTargetIndex() : -1;
                if (targetIndex < 0 || targetIndex >= foe.board.size()) {
                    // 未指定或指定英雄
                    foe.hp = Math.max(0, foe.hp - dmg);
                    log.add(actor.name + " 的火球术对 " + foe.name + " 造成 " + dmg + " 点伤害");
                    lastEffects.add(new int[]{foe.side, foe.hp});
                } else {
                    Minion target = foe.board.get(targetIndex);
                    int dealt = damageMinion(target, dmg);
                    log.add(actor.name + " 的火球术对 " + target.card.getLabel()
                            + " 造成 " + dealt + " 点伤害");
                    if (!target.alive()) {
                        foe.board.remove(target);
                        onMinionDeath(foe, target);
                    }
                }
                return dmg;
            }
            case HEAL: {
                int before = actor.hp;
                actor.hp = Math.min(MAX_HP, actor.hp + 5);
                int healed = actor.hp - before;
                log.add(actor.name + " 治疗恢复 " + healed + " 点生命");
                lastEffects.add(new int[]{actor.side, actor.hp});
                return healed;
            }
            case CLEAVE: {
                int count = 0;
                // 先把尸体收集起来再统一移除：遍历中改集合会抛 ConcurrentModificationException
                List<Minion> dead = new ArrayList<>();
                for (Minion m : foe.board) {
                    damageMinion(m, 3);
                    count++;
                    if (!m.alive()) dead.add(m);
                }
                for (Minion m : dead) {
                    foe.board.remove(m);
                    onMinionDeath(foe, m);
                }
                log.add(actor.name + " 的横扫对敌方 " + count + " 个随从造成 3 点伤害");
                return count;
            }
            case BUFF: {
                // 鼓舞指定「己方某个随从」
                int targetIndex = command != null && command.hasTarget() ? command.getTargetIndex() : 0;
                if (actor.board.isEmpty()) {
                    log.add(actor.name + " 的鼓舞没有目标");
                    return 0;
                }
                if (targetIndex < 0 || targetIndex >= actor.board.size()) targetIndex = 0;
                Minion target = actor.board.get(targetIndex);
                target.atk += 2;
                target.hp += 2;
                log.add(actor.name + " 的鼓舞使 " + target.card.getLabel() + " 获得 +2/+2");
                return 2;
            }
            default:
                return 0;
        }
    }

    /**
     * 攻击：用己方第 minionIndex 个随从攻击对手英雄或对手随从。
     * 这里没做成独立指令，而是随「回合结束」自动结算 —— 保持行动空间只有「出牌」一种，
     * 前端交互与 RPG 技能栏同构，复用同一套按钮派发逻辑。
     */
    private void autoAttack(PlayerState actor) {
        PlayerState foe = state(3 - actor.side);
        for (Minion m : actor.board) {
            if (!m.canAttack()) continue; // 召唤失调/已攻击的随从不出手
            m.attacked = true;

            // 嘲讽优先：对方有嘲讽随从时必须先打它
            int targetIdx = -1;
            for (int i = 0; i < foe.board.size(); i++) {
                if (foe.board.get(i).card.hasTaunt()) { targetIdx = i; break; }
            }
            if (targetIdx < 0 && !foe.board.isEmpty()) targetIdx = 0;

            if (targetIdx >= 0) {
                Minion target = foe.board.get(targetIdx);
                int dealtToTarget = damageMinion(target, m.atk);
                int dealtToAttacker = damageMinion(m, target.atk);
                applyLifesteal(actor, m, dealtToTarget);
                log.add(m.card.getLabel() + " 攻击了 " + target.card.getLabel() + "（各自受到 "
                        + dealtToTarget + "/" + dealtToAttacker + " 伤害）");
                if (!target.alive()) {
                    foe.board.remove(target);
                    onMinionDeath(foe, target);
                }
            } else {
                int dealt = Math.min(m.atk, foe.hp);
                foe.hp = Math.max(0, foe.hp - m.atk);
                log.add(m.card.getLabel() + " 对 " + foe.name + " 造成 " + dealt + " 点伤害");
                applyLifesteal(actor, m, dealt);
            }
        }
        // 攻击后清理尸体：必须逐个走 onMinionDeath，removeIf 会吞掉亡语
        List<Minion> deadAttackers = new ArrayList<>();
        for (Minion m : actor.board) if (!m.alive()) deadAttackers.add(m);
        for (Minion m : deadAttackers) {
            actor.board.remove(m);
            onMinionDeath(actor, m);
        }
        List<Minion> deadDefenders = new ArrayList<>();
        for (Minion m : foe.board) if (!m.alive()) deadDefenders.add(m);
        for (Minion m : deadDefenders) {
            foe.board.remove(m);
            onMinionDeath(foe, m);
        }
    }

    /** 回合开始：法力 +1（上限 10）、重置随从攻击标记、抽 1 张 */
    private void beginTurn(PlayerState s) {
        // 新回合开始：记录基线，供连击判定
        turnStartHistorySize = history.size();
        s.maxMana = Math.min(MAX_MANA, s.maxMana + 1);
        s.mana = s.maxMana;
        for (Minion m : s.board) {
            m.attacked = false;
            m.summonedThisTurn = false; // 解除召唤失调
        }
        drawCard(s);
    }

    /**
     * 开局初始化。
     *
     * 先手第一回合给 3 费，是修一个实测出来的死局：
     * 卡池费用分布是 1/2/2/2/3/3/4/5/6，若起手三张全是 3 费以上，
     * 第一回合就完全无牌可出，只能干等超时判负 —— 这不是玩家操作失误，
     * 而是开局资源给少了。实测 3 费可以让 200 个随机种子全部有牌可打。
     */
    private void startMatch() {
        // 首回合直接给 OPENING_MANA 点法力；同时把 maxMana 设为 OPENING_MANA - 1，
        // 这样对手第一次 beginTurn 后是 OPENING_MANA（否则会变成 OPENING_MANA + 1，
        // 双方起手资源就不对等了）。
        states[1].maxMana = OPENING_MANA - 1;
        states[1].mana = OPENING_MANA;
        states[2].maxMana = OPENING_MANA - 1;
        states[2].mana = OPENING_MANA;
        for (Minion m : states[1].board) m.attacked = false;
        for (Minion m : states[2].board) m.attacked = false;
    }

    /**
     * 抽起手牌，并**保证至少一张买得起**。
     *
     * 为什么不能只靠「调大开局法力」：开局资源与牌库配比是耦合的 ——
     * 每次往牌库里加高费卡，起手全高费的概率都会上升，于是又要把
     * OPENING_MANA 往上调（历史上已经调过 1→2→3→4，每次加卡都要重调）。
     * 这不是可持续的修法：卡池越大越脆弱，而且 4 费开局本身已经偏高。
     *
     * 改成结构性保证：起手若全部买不起，就把第一张换成牌库里最便宜的一张。
     * 这样以后无论加多少高费卡，第一回合总有牌可打（单测用 1000 个种子钉死）。
     */
    private void drawOpeningHand(PlayerState s, int count) {
        for (int i = 0; i < count; i++) drawCard(s);

        boolean anyAffordable = false;
        for (Card c : s.hand) {
            if (c.getCost() <= s.mana) { anyAffordable = true; break; }
        }
        if (anyAffordable) return;

        // 找牌库里最便宜的一张，与手牌最贵的一张对调
        int cheapestIdx = -1;
        for (int i = 0; i < s.deck.size(); i++) {
            if (cheapestIdx < 0 || s.deck.get(i).getCost() < s.deck.get(cheapestIdx).getCost()) {
                cheapestIdx = i;
            }
        }
        if (cheapestIdx < 0) return; // 牌库也空（理论上不会）

        int priciestHandIdx = 0;
        for (int i = 1; i < s.hand.size(); i++) {
            if (s.hand.get(i).getCost() > s.hand.get(priciestHandIdx).getCost()) priciestHandIdx = i;
        }
        Card replacement = s.deck.remove(cheapestIdx);
        Card returned = s.hand.set(priciestHandIdx, replacement);
        s.deck.add(returned);
        log.add(s.name + " 起手全是高费牌，将 " + returned.getLabel()
                + " 换回牌库，改抽 " + replacement.getLabel());
    }

    /** 抽牌：自己的牌库空则疲劳伤害（递增） */
    private void drawCard(PlayerState s) {
        if (s.hand.size() >= MAX_HAND) return; // 手牌满，不抽也不爆牌（简化）
        if (s.deck.isEmpty()) {
            s.fatigue++;
            s.hp = Math.max(0, s.hp - s.fatigue);
            log.add(s.name + " 牌库已空，受到 " + s.fatigue + " 点疲劳伤害");
            return;
        }
        s.hand.add(s.deck.remove(0));
    }

    /** 终局判定，返回是否已结束 */
    private boolean checkedOver() {
        PlayerState a = states[1];
        PlayerState b = states[2];
        if (a.alive() && b.alive()) return false;
        over = true;
        if (a.alive()) winner = 1;
        else if (b.alive()) winner = 2;
        else winner = null;
        log.add(winner == null ? "双方同归于尽，平局" : state(winner).name + " 获得胜利");
        return true;
    }

    private PlayerState state(int side) {
        return side >= 1 && side <= 2 ? states[side] : null;
    }

    /** 该方当前能否打出这张手牌 */
    public boolean canPlay(int side, int handIndex) {
        PlayerState s = state(side);
        if (s == null || handIndex < 0 || handIndex >= s.hand.size()) return false;
        return s.hand.get(handIndex).getCost() <= s.mana;
    }

    public int getHp(int side) {
        PlayerState s = state(side);
        return s == null ? 0 : s.hp;
    }

    // ============ AI 查询用访问器 ============

    /** 该方手牌（只读快照，AI 用） */
    public List<Card> getHand(int side) {
        PlayerState s = state(side);
        return s == null ? Collections.emptyList() : new ArrayList<>(s.hand);
    }

    /** 该方当前法力 */
    public int getMana(int side) {
        PlayerState s = state(side);
        return s == null ? 0 : s.mana;
    }

    /** 该方场上随从数量 */
    public int getBoardCount(int side) {
        PlayerState s = state(side);
        return s == null ? 0 : s.board.size();
    }

    /** 该方可攻击的随从下标列表（供 AI 判断是否还有攻击可用） */
    public List<Integer> attackableIndexes(int side) {
        PlayerState s = state(side);
        List<Integer> result = new ArrayList<>();
        if (s == null) return result;
        for (int i = 0; i < s.board.size(); i++) {
            if (s.board.get(i).canAttack()) result.add(i);
        }
        return result;
    }

    /** 该方场上的嘲讽随从下标（AI 选目标时必须优先打这些） */
    public List<Integer> tauntIndexes(int side) {
        PlayerState s = state(side);
        List<Integer> result = new ArrayList<>();
        if (s == null) return result;
        for (int i = 0; i < s.board.size(); i++) {
            if (s.board.get(i).card.hasTaunt()) result.add(i);
        }
        return result;
    }

    /** 该方场上随从的 {atk, hp} 快照（供 AI 评估目标价值） */
    public List<int[]> getBoardForAi(int side) {
        PlayerState s = state(side);
        List<int[]> result = new ArrayList<>();
        if (s == null) return result;
        for (Minion m : s.board) result.add(new int[]{m.atk, m.hp});
        return result;
    }

    /** 该方牌库剩余张数 */
    public int getDeckLeft(int side) {
        PlayerState s = state(side);
        return s == null ? 0 : s.deck.size();
    }

    // ============ 测试钩子（包级可见，仅单元测试使用） ============

    /** 直接在某方场上放一个随从（绕过手牌与法力），用于构造嘲讽/冲锋等场景 */
    void putMinionForTest(int side, Card card) {
        PlayerState s = state(side);
        if (s != null) s.board.add(new Minion(card));
    }

    /** 清空某一方场上所有随从的召唤失调（模拟「已经过了一回合」） */
    void clearSummoningSicknessForTest(int side) {
        PlayerState s = state(side);
        if (s == null) return;
        for (Minion m : s.board) m.summonedThisTurn = false;
    }

    /** 直接给某方塞一张牌并补足法力，用于稳定复现特定卡牌的行为 */
    void giveCardForTest(int side, Card card, int mana) {
        PlayerState s = state(side);
        if (s == null) return;
        s.hand.add(card);
        s.mana = Math.max(s.mana, mana);
    }

    /** 直接设置某一方英雄生命，用于构造终局场景 */
    void setHpForTest(int side, int hp) {
        PlayerState s = state(side);
        if (s != null) s.hp = hp;
    }

    /** 场上随从上限（AI 判断是否还能铺场） */
    public static int getMaxBoard() {
        return MAX_BOARD;
    }

    /**
     * 强制结束当前回合。
     *
     * 用途：当一方既打不出牌（手牌为空或全部费用不足）时，房间层需要替他
     * 把回合交出去，否则对局会卡死在这个玩家身上。引擎本身不做自动跳过 ——
     * 那会让真实玩家莫名其妙被偷回合。
     *
     * @return true 表示成功换手
     */
    public boolean forceEndTurn(int side) {
        return endTurn(side);
    }

    /** 测试用：绕过轮次校验直接换手，专供收敛性用例模拟超时 */
    void forceEndTurnForTest() {
        if (over) return;
        int side = currentTurn;
        PlayerState actor = state(side);
        if (actor != null) autoAttack(actor);
        if (checkedOver()) return;
        currentTurn = 3 - side;
        beginTurn(state(currentTurn));
        checkFinished();
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
        if (winner == null) return "双方同归于尽或生命相同，平局";
        PlayerState w = state(winner);
        PlayerState l = state(3 - winner);
        return w.name + " 以剩余 " + w.hp + " 点生命获胜（" + l.name + " 剩余 " + l.hp + " 点）";
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
        return 45;
    }

    @Override
    public int getTotalTimeSeconds() {
        return 600;
    }

    /**
     * 按视角下发状态 —— 卡牌的隐藏信息就在这里落地。
     *
     * viewerSide 只看得到自己的手牌内容；对手手牌只给数量。
     * 观战者（0）两边手牌都看不到内容。
     */
    @Override
    public Object getViewState(int viewerSide) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", "CARD");
        view.put("turn", currentTurn);
        view.put("over", over);
        view.put("winner", winner);
        // 只暴露自己的牌库剩余（对手的牌库张数属于隐藏信息）
        PlayerState me = state(viewerSide);
        view.put("deckLeft", me != null ? me.deck.size() : 0);

        List<Map<String, Object>> playersView = new ArrayList<>();
        for (int side = 1; side <= 2; side++) {
            PlayerState s = state(side);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("side", side);
            m.put("name", s.name);
            m.put("hp", s.hp);
            m.put("maxHp", MAX_HP);
            m.put("mana", s.mana);
            m.put("maxMana", s.maxMana);
            m.put("handCount", s.hand.size());
            m.put("fatigue", s.fatigue);
            // 套牌类型是公开信息：对手看到你有多少手牌，也想知道你在打什么流派
            m.put("deck", getDeck(side).name());
            m.put("deckLabel", getDeck(side).getLabel());

            // 只给观察者自己的手牌内容
            boolean ownHand = viewerSide == side;
            if (ownHand) {
                List<Map<String, Object>> hand = new ArrayList<>();
                for (int i = 0; i < s.hand.size(); i++) {
                    Card c = s.hand.get(i);
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("index", i);
                    h.put("card", c.getIndex());
                    h.put("label", c.getLabel());
                    h.put("cost", c.getCost());
                    h.put("atk", c.getAtk());
                    h.put("hp", c.getHp());
                    h.put("desc", c.getDesc());
                    h.put("minion", c.isMinion());
                    // 前端据此决定「点击后是否进入选目标模式」
                    h.put("needsTarget", c.needsTarget());
                    h.put("playable", c.getCost() <= s.mana
                            && (!c.needsTarget() || canTargetAnything(s, c)));
                    hand.add(h);
                }
                m.put("hand", hand);
            }

            // 场上随从对所有人公开
            List<Map<String, Object>> board = new ArrayList<>();
            for (Minion mi : s.board) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("card", mi.card.getIndex());
                b.put("label", mi.card.getLabel());
                b.put("atk", mi.atk);
                b.put("hp", mi.hp);
                b.put("attacked", mi.attacked);
                b.put("taunt", mi.card.hasTaunt());
                b.put("divineShield", mi.divineShield);
                b.put("lifesteal", mi.card.hasLifesteal());
                b.put("combo", mi.card.hasCombo());
                b.put("comboActive", mi.comboActive);
                b.put("charge", mi.card.hasCharge());
                b.put("battlecry", mi.card.hasBattlecry());
                b.put("deathrattle", mi.card.hasDeathrattle());
                b.put("canAttack", mi.canAttack());
                board.add(b);
            }
            m.put("board", board);
            playersView.add(m);
        }
        view.put("players", playersView);

        // 日志是公开的，但对手抽牌的具体内容不记录，避免从日志反推手牌
        int from = Math.max(0, log.size() - 12);
        view.put("log", new ArrayList<>(log.subList(from, log.size())));
        return view;
    }
}
