package com.gomoku.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 答题对战（Quiz）— 第四个非棋盘玩法
 *
 * 与前几个玩法的差别在于「行动语义」和「信息节奏」都不一样：
 * - 行动是「选第几个选项」（值域很小，适合 action 通道）
 * - 一回合内双方**各自独立回答同一道题**，不是轮流出手
 * - 作答前看不见对手选了哪个（隐藏信息），提交后一起揭晓
 *
 * 这带来一个此前没有的语义：**同一步里两个玩家都要行动**。
 * 实现上没有改房间的轮次机制，而是让「同一个回合内先后提交」，等两人都答完再揭晓：
 * 回合仍由 currentTurn 推进，但提交被记录在 pendingAnswer 里，两人都交了才结算。
 * 这样房间完全不需要改动 —— 外部看来仍然是「一方一手」，只是引擎在内部做了缓冲。
 *
 * 规则：
 * - 共 8 题；每题 4 个选项，答对 +10 分，答错 -5 分（最低 0）
 * - 每题限时由房间的步时机制约束（超时未答视为答错）
 * - 8 题结束比分高者胜；同分则平局
 */
public class QuizBattleEngine implements GameEngine {

    static final int ROUNDS = 8;
    /** 答对得分 */
    static final int SCORE_CORRECT = 10;
    /** 答错扣分 */
    static final int SCORE_WRONG = -5;

    static final int STEP_TIME_SECONDS = 25;
    static final int TOTAL_TIME_SECONDS = 400;

    public static final int HIST_SIDE = 0;
    public static final int HIST_CHOICE = 1;
    public static final int HIST_SCORE = 2;

    /** 一道题 */
    static final class Question {
        final String category;
        final String text;
        final String[] options;
        final int correctIndex;

        Question(String category, String text, String[] options, int correctIndex) {
            this.category = category;
            this.text = text;
            this.options = options;
            this.correctIndex = correctIndex;
        }
    }

    private final List<Question> questions = new ArrayList<>();
    private final List<int[]> history = new ArrayList<>();
    private final List<int[]> lastEffects = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final Random random;

    private int currentTurn = 1;
    private int moveSeq = 0;
    private boolean over = false;
    private Integer winner;

    private final int[] score = new int[3];      // 下标 1、2
    private final int[] streak = new int[3];     // 连续答对次数
    private final int[] answers = new int[3];    // 本回合已提交的选项，-1 = 未提交
    /** 当前题号（0-based） */
    private int roundIndex = 0;
    /** 本题是否已揭晓（两人都答完后为 true） */
    private boolean revealed = false;
    /** 上一题的正确答案与双方选择，供前端展示揭晓动画 */
    private int lastCorrect = -1;
    private final int[] lastChoices = new int[]{-1, -1, -1};

    public QuizBattleEngine() {
        this(new Random());
    }

    QuizBattleEngine(Random random) {
        this.random = random;
        reset();
    }

    @Override
    public GameType type() {
        return GameType.QUIZ;
    }

    @Override
    public void reset() {
        buildQuestions();
        history.clear();
        lastEffects.clear();
        log.clear();
        currentTurn = 1;
        moveSeq = 0;
        over = false;
        winner = null;
        score[1] = 0;
        score[2] = 0;
        streak[1] = 0;
        streak[2] = 0;
        answers[1] = -1;
        answers[2] = -1;
        roundIndex = 0;
        revealed = false;
        lastCorrect = -1;
        lastChoices[1] = -1;
        lastChoices[2] = -1;
        log.add("答题对战开始：共 " + ROUNDS + " 题，答对 +" + SCORE_CORRECT
                + " 分，答错 " + SCORE_WRONG + " 分");
    }

    /**
     * 从题库按「主题轮转」抽 8 题。
     *
     * 为什么不是简单 shuffle 后取前 8：题库里技术类占比高时，一局可能全是技术题，
     * 玩家会觉得「这游戏只有一种题」。按主题轮转能保证一局里主题尽量分散，
     * 同时仍然保留随机性（主题顺序、主题内顺序都是随机的）。
     */
    static List<Question> selectQuestions(List<Question> pool, Random random) {
        Map<String, List<Question>> byCategory = new LinkedHashMap<>();
        for (Question q : pool) {
            byCategory.computeIfAbsent(q.category, k -> new ArrayList<>()).add(q);
        }
        List<String> categories = new ArrayList<>(byCategory.keySet());
        java.util.Collections.shuffle(categories, random);
        for (List<Question> group : byCategory.values()) {
            java.util.Collections.shuffle(group, random);
        }

        List<Question> picked = new ArrayList<>();
        // 轮转取题：每轮从每个主题各取一题，直到凑够 ROUNDS
        int depth = 0;
        while (picked.size() < ROUNDS) {
            boolean tookAny = false;
            for (String cat : categories) {
                List<Question> group = byCategory.get(cat);
                if (depth >= group.size()) continue;
                picked.add(group.get(depth));
                tookAny = true;
                if (picked.size() >= ROUNDS) break;
            }
            if (!tookAny) break; // 题库总量不足（正常不会发生）
            depth++;
        }
        return picked;
    }

    private void buildQuestions() {
        questions.clear();
        questions.addAll(selectQuestions(allQuestions(), random));
    }

    /** 题库：按主题分组，供 {@link #selectQuestions} 抽题 */
    static List<Question> allQuestions() {
        List<Question> pool = new ArrayList<>();

        // ---------- 科学 ----------
        pool.add(new Question("科学", "太阳系中体积最大的行星是？",
                new String[]{"地球", "木星", "土星", "火星"}, 1));
        pool.add(new Question("科学", "光在真空中的速度约为？",
                new String[]{"3×10⁵ km/s", "3×10⁵ m/s", "3×10⁸ km/s", "3×10⁶ km/s"}, 0));
        pool.add(new Question("科学", "水的化学式是？",
                new String[]{"CO₂", "H₂O", "O₂", "NaCl"}, 1));
        pool.add(new Question("科学", "人体最大的器官是？",
                new String[]{"肝脏", "皮肤", "肺", "大脑"}, 1));
        pool.add(new Question("科学", "声音在下列哪种介质中传播最快？",
                new String[]{"空气", "水", "钢铁", "真空"}, 2));
        pool.add(new Question("科学", "地球大气中含量最多的气体是？",
                new String[]{"氧气", "氮气", "二氧化碳", "氢气"}, 1));
        pool.add(new Question("科学", "一个标准大气压下，水的沸点是？",
                new String[]{"90°C", "100°C", "110°C", "120°C"}, 1));

        // ---------- 人文 ----------
        pool.add(new Question("人文", "《红楼梦》的作者是？",
                new String[]{"罗贯中", "施耐庵", "曹雪芹", "吴承恩"}, 2));
        pool.add(new Question("人文", "《西游记》的作者是？",
                new String[]{"吴承恩", "曹雪芹", "蒲松龄", "冯梦龙"}, 0));
        pool.add(new Question("人文", "“床前明月光”出自哪位诗人？",
                new String[]{"杜甫", "李白", "白居易", "王维"}, 1));
        pool.add(new Question("人文", "《命运交响曲》的作者是？",
                new String[]{"莫扎特", "巴赫", "贝多芬", "肖邦"}, 2));
        pool.add(new Question("人文", "名画《蒙娜丽莎》的作者是？",
                new String[]{"梵高", "毕加索", "达·芬奇", "拉斐尔"}, 2));
        pool.add(new Question("人文", "下列哪部不属于“四书”？",
                new String[]{"《大学》", "《中庸》", "《诗经》", "《孟子》"}, 2));
        pool.add(new Question("人文", "下列哪部不属于莎士比亚四大悲剧？",
                new String[]{"《哈姆雷特》", "《奥赛罗》", "《罗密欧与朱丽叶》", "《李尔王》"}, 2));

        // ---------- 技术 ----------
        pool.add(new Question("技术", "计算机中 1 字节等于多少位？",
                new String[]{"4 位", "8 位", "16 位", "32 位"}, 1));
        pool.add(new Question("技术", "下列哪个不是编程语言？",
                new String[]{"Java", "Python", "HTTP", "Rust"}, 2));
        pool.add(new Question("技术", "HTTP 状态码 404 表示？",
                new String[]{"服务器错误", "未找到资源", "权限不足", "请求成功"}, 1));
        pool.add(new Question("技术", "HTTP 状态码 500 表示？",
                new String[]{"服务器内部错误", "未找到资源", "请求成功", "重定向"}, 0));
        pool.add(new Question("技术", "下列哪个是关系型数据库？",
                new String[]{"Redis", "MySQL", "MongoDB", "Elasticsearch"}, 1));
        pool.add(new Question("技术", "Git 中用于提交改动的命令是？",
                new String[]{"git push", "git commit", "git clone", "git branch"}, 1));
        pool.add(new Question("技术", "IPv4 地址由多少位二进制组成？",
                new String[]{"16 位", "32 位", "64 位", "128 位"}, 1));

        // ---------- 地理 ----------
        pool.add(new Question("地理", "中国最长的河流是？",
                new String[]{"黄河", "长江", "珠江", "黑龙江"}, 1));
        pool.add(new Question("地理", "世界上面积最大的国家是？",
                new String[]{"中国", "美国", "俄罗斯", "加拿大"}, 2));
        pool.add(new Question("地理", "世界上海拔最高的山峰是？",
                new String[]{"乔戈里峰", "珠穆朗玛峰", "干城章嘉峰", "洛子峰"}, 1));
        pool.add(new Question("地理", "中国面积最大的省级行政区是？",
                new String[]{"西藏", "新疆", "内蒙古", "青海"}, 1));
        pool.add(new Question("地理", "赤道穿过的大洲中不包括？",
                new String[]{"亚洲", "非洲", "南美洲", "欧洲"}, 3));
        pool.add(new Question("地理", "世界上最深的海沟是？",
                new String[]{"波多黎各海沟", "马里亚纳海沟", "爪哇海沟", "日本海沟"}, 1));
        pool.add(new Question("地理", "长江发源于哪个省级行政区？",
                new String[]{"四川", "西藏", "青海", "云南"}, 2));

        // ---------- 历史 ----------
        pool.add(new Question("历史", "第一届现代奥运会举办于哪一年？",
                new String[]{"1896 年", "1900 年", "1904 年", "1888 年"}, 0));
        pool.add(new Question("历史", "中华人民共和国成立于哪一年？",
                new String[]{"1945 年", "1949 年", "1950 年", "1954 年"}, 1));
        pool.add(new Question("历史", "秦始皇统一六国是在公元前哪一年？",
                new String[]{"前 221 年", "前 230 年", "前 206 年", "前 256 年"}, 0));
        pool.add(new Question("历史", "第一次世界大战爆发于哪一年？",
                new String[]{"1912 年", "1914 年", "1918 年", "1939 年"}, 1));
        pool.add(new Question("历史", "“贞观之治”出现在哪个朝代？",
                new String[]{"汉朝", "隋朝", "唐朝", "宋朝"}, 2));
        pool.add(new Question("历史", "活字印刷术的发明者是？",
                new String[]{"蔡伦", "毕昇", "张衡", "沈括"}, 1));
        pool.add(new Question("历史", "哥伦布首次到达美洲是在哪一年？",
                new String[]{"1453 年", "1492 年", "1519 年", "1588 年"}, 1));

        // ---------- 生活 ----------
        pool.add(new Question("生活", "一个三角形的内角和是多少度？",
                new String[]{"90 度", "180 度", "270 度", "360 度"}, 1));
        pool.add(new Question("生活", "成年人正常体温约为？",
                new String[]{"35.0°C", "36.5°C", "38.0°C", "39.0°C"}, 1));
        pool.add(new Question("生活", "哪种维生素主要通过晒太阳合成？",
                new String[]{"维生素 A", "维生素 C", "维生素 D", "维生素 B12"}, 2));
        pool.add(new Question("生活", "油锅起火时正确的做法是？",
                new String[]{"立即倒水", "盖上锅盖", "用嘴吹灭", "端锅跑出去"}, 1));
        pool.add(new Question("生活", "成年人每天建议的睡眠时长约为？",
                new String[]{"3-4 小时", "5-6 小时", "7-9 小时", "11-13 小时"}, 2));
        pool.add(new Question("生活", "下列哪种食物蛋白质含量最高？",
                new String[]{"米饭", "鸡蛋", "白菜", "苹果"}, 1));
        pool.add(new Question("生活", "摄氏温标下冰的熔点是？",
                new String[]{"-10°C", "0°C", "4°C", "10°C"}, 1));

        return pool;
    }

    /**
     * 提交答案：action = 选项下标（0..3）。
     *
     * 同一步里两人都要提交 —— 用 answers[] 缓冲，两人都交了才揭晓并推进题号。
     */
    @Override
    public boolean applyMove(MoveCommand command, int side) {
        if (command == null || !command.hasAction()) return false;
        if (over) return false;
        if (!isAwaitingAction(side)) return false;

        int choice = command.getAction();
        if (choice < 0 || choice >= 4) return false;
        if (answers[side] >= 0) return false; // 本题已答过

        lastEffects.clear();
        moveSeq++;
        answers[side] = choice;
        history.add(new int[]{side, choice, score[side]});

        boolean bothAnswered = answers[1] >= 0 && answers[2] >= 0;
        if (!bothAnswered) {
            // 兼容轮次字段指向尚未作答的一方，双方提交顺序不限。
            currentTurn = 3 - side;
            log.add(nameOf(side) + " 已提交答案，等待对手");
            return true;
        }

        // 两人都答完 → 揭晓并结算
        revealAndScore();
        return true;
    }

    /** 揭晓本题：算分、记录、推进到下一题 */
    private void revealAndScore() {
        Question q = currentQuestion();
        if (q == null) return;

        revealed = true;
        lastCorrect = q.correctIndex;
        lastChoices[1] = answers[1];
        lastChoices[2] = answers[2];

        StringBuilder sb = new StringBuilder("答案：" + q.options[q.correctIndex] + " — ");
        for (int side = 1; side <= 2; side++) {
            boolean right = answers[side] == q.correctIndex;
            int bonus = right && streak[side] > 0 ? 5 : 0;
            if (right) streak[side]++; else streak[side] = 0;
            score[side] = Math.max(0, score[side] + (right ? SCORE_CORRECT + bonus : SCORE_WRONG));
            sb.append(nameOf(side)).append(right ? " 答对" : " 答错");
            if (right && bonus > 0) sb.append("（连击 +").append(bonus).append("）");
            if (side == 1) sb.append("，");
        }
        log.add(sb.toString());

        // 推进题号
        roundIndex++;
        answers[1] = -1;
        answers[2] = -1;

        if (roundIndex >= ROUNDS) {
            over = true;
            if (score[1] == score[2]) winner = null;
            else winner = score[1] > score[2] ? 1 : 2;
            log.add(winner == null ? "全部答完，双方同分，平局"
                    : nameOf(winner) + " 以 " + score[winner] + " 分获胜");
            return;
        }

        // 新题双方均可提交；兼容轮次字段从座位 1 开始。
        currentTurn = 1;
        // 当前视图展示的是下一题；上一题答案只通过 lastCorrect / lastChoices 展示。
        revealed = false;
    }

    private Question currentQuestion() {
        return roundIndex >= 0 && roundIndex < questions.size() ? questions.get(roundIndex) : null;
    }

    private static String nameOf(int side) {
        return side == 1 ? "红方" : "蓝方";
    }

    public int getScore(int side) {
        return side >= 1 && side <= 2 ? score[side] : 0;
    }

    public int getRoundIndex() {
        return roundIndex;
    }

    /**
     * 一题需要双方各答一次，单次提交不代表整轮结束。
     * 房间通过 simultaneousTurn / isAwaitingAction / getActionRound 管理计时和 AI。
     */
    @Override
    public boolean autoEndsTurn() {
        return false;
    }

    /** 同一道题双方各自作答：同一回合内两个人都要行动 */
    @Override
    public boolean simultaneousTurn() {
        return true;
    }

    @Override public boolean isAwaitingAction(int side) {
        return !over && side >= 1 && side <= 2 && answers[side] < 0;
    }

    @Override public int getActionRound() { return roundIndex; }

    /**
     * 答题按提交自动结算，不支持显式结束回合。
     */
    @Override
    public boolean endTurn(int side) {
        return false;
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
        return "红方 " + score[1] + " 分 : 蓝方 " + score[2] + " 分";
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
        return STEP_TIME_SECONDS;
    }

    @Override
    public int getTotalTimeSeconds() {
        return TOTAL_TIME_SECONDS;
    }

    /**
     * 按视角下发状态 —— 本题的关键隐藏信息在这里。
     *
     * 题目和选项是公开的（双方答同一题），但：
     * - 自己的选择：可见
     * - 对手的选择：揭晓前不可见（只给「已提交」标记）
     * - 正确答案：揭晓前不下发
     */
    @Override
    public Object getViewState(int viewerSide) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", "QUIZ");
        view.put("turn", currentTurn);
        view.put("over", over);
        view.put("winner", winner);
        view.put("round", Math.min(roundIndex + 1, ROUNDS));
        view.put("totalRounds", ROUNDS);
        view.put("revealed", revealed);

        Question q = currentQuestion();
        if (q != null) {
            Map<String, Object> qv = new LinkedHashMap<>();
            qv.put("category", q.category);
            qv.put("text", q.text);
            // 选项顺序固定（正确答案下标由服务端持有），前端只展示文本
            List<String> opts = new ArrayList<>();
            for (String o : q.options) opts.add(o);
            qv.put("options", opts);
            view.put("question", qv);
        }

        // 正确答案只在揭晓后公开
        view.put("correctIndex", revealed ? lastCorrect : null);

        List<Map<String, Object>> playersView = new ArrayList<>();
        for (int side = 1; side <= 2; side++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("side", side);
            m.put("name", nameOf(side));
            m.put("score", score[side]);
            m.put("streak", streak[side]);
            m.put("answered", answers[side] >= 0);

            // 揭晓前只让自己的选择可见；揭晓后双方选择都公开
            boolean showChoice = revealed || viewerSide == side;
            if (showChoice) {
                int choice = revealed ? lastChoices[side] : answers[side];
                m.put("choice", choice >= 0 ? choice : null);
            }
            playersView.add(m);
        }
        view.put("players", playersView);

        // 上一题的结果（答案 + 双方选择）：独立于 revealed，
        // 这样进入新题后前端仍能显示「上一题你答对了/答案是 B」。
        if (lastCorrect >= 0) {
            view.put("lastCorrect", lastCorrect);
            List<Integer> cs = new ArrayList<>();
            cs.add(lastChoices[1]);
            cs.add(lastChoices[2]);
            view.put("lastChoices", cs);
        }

        int from = Math.max(0, log.size() - 10);
        view.put("log", new ArrayList<>(log.subList(from, log.size())));
        return view;
    }

    /** 当前题目的正确选项（仅揭晓后由外部安全读取；AI 用它时说明是「作弊」，见 QuizAI） */
    int correctIndexForAi() {
        Question q = currentQuestion();
        return q == null ? -1 : q.correctIndex;
    }
}
