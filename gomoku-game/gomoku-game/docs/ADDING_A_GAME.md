# 新增一种玩法

这份文档用一个可运行的骨架，把「加一种玩法」拆成 6 步。目标是：**不需要改房间、匹配、计时、回放、ELO 这些通用逻辑**，只写规则、AI 和渲染。

> 下方骨架以两人棋类为例。房间已支持按玩法配置 2–8 人，`side` 表示 1 起始的座位号；0 为观众。
> 多人规则可参考 `HighCardEngine`：声明人数范围，实现 `configurePlayers`、`forfeit`、`getWinningSides`，按观看座位裁剪隐藏信息。斗地主、麻将的规则、队伍和结算仍需各自实现；实时制需要额外主循环。

---

## 0. 先看一个最小全景

| 层 | 你要提供的东西 | 通用层负责 |
|---|---|---|
| 规则 | `XxxEngine implements GameEngine`（棋盘类再加 `BoardGameEngine`） | 房间、计时、断线、回放、ELO、记录 |
| AI | `XxxAI implements GameAI` | 触发时机、难度参数、思考延迟 |
| 客户端 | `GameModules.XXX`（canvas 或 HTML 面板） | 大厅列表、匹配、重连、聊天 |
| 验证 | `XxxEngineTest.java` + `smoke-xxx.mjs` | — |

下面以「三连棋（Tic-Tac-Toe，3×3）」为例。

---

## 1. 注册游戏类型

`src/main/java/com/gomoku/game/GameType.java`

```java
TICTACTOE("三连棋", 3, 3, true),
```

参数：显示名、行、列、是否棋盘玩法。非棋盘玩法填 `0, 0, false`。

四参数构造默认固定两人；可变人数使用七参数构造，例如 `HIGH_CARD("比大小", 0, 0, false, 2, 8, 4)`，末三项为最少、最多、默认人数。多人局不使用双人 ELO。

注意：

- `rows/cols` 只给棋盘玩法用；不要用 `rows > 0` 去猜类型，用 `isBoard()`。
- 加在枚举**末尾**即可。二进制协议里 `gameType` 传的是字符串，不是序号，所以不影响旧客户端。

---

## 2. 写规则引擎

棋盘玩法实现 `BoardGameEngine`（它已经帮你把 `MoveCommand` 翻译成落子）：

```java
package com.gomoku.game;

import java.util.ArrayList;
import java.util.List;

public class TicTacToeBoard implements BoardGameEngine {

    private static final int EMPTY = 0, BLACK = 1, WHITE = 2;
    private final int[][] board = new int[3][3];
    private final List<int[]> history = new ArrayList<>();
    private final List<int[]> lastEffects = new ArrayList<>();
    private int currentTurn = BLACK;
    private boolean over = false;
    private Integer winner = null;
    private int moveSeq = 0;

    @Override public GameType type() { return GameType.TICTACTOE; }

    @Override public void reset() {
        for (int[] row : board) java.util.Arrays.fill(row, EMPTY);
        history.clear();
        lastEffects.clear();
        currentTurn = BLACK;
        over = false;
        winner = null;
        moveSeq = 0;
    }

    @Override public int getRows() { return 3; }
    @Override public int getCols() { return 3; }

    @Override public boolean placeStone(int row, int col, int side) {
        if (over || side != currentTurn) return false;
        if (row < 0 || row >= 3 || col < 0 || col >= 3) return false;
        if (board[row][col] != EMPTY) return false;

        board[row][col] = side;
        history.add(new int[]{row, col, side});
        lastEffects.clear();
        lastEffects.add(new int[]{row, col, side});
        moveSeq++;

        if (won(row, col, side)) {
            over = true;
            winner = side;
        } else if (history.size() == 9) {
            over = true;
            winner = null; // 平局
        } else {
            currentTurn = 3 - side;
        }
        return true;
    }

    private boolean won(int r, int c, int s) {
        for (int i = 0; i < 3; i++) {
            if (board[r][i] != s) break;
            if (i == 2) return true;
        }
        for (int i = 0; i < 3; i++) {
            if (board[i][c] != s) break;
            if (i == 2) return true;
        }
        if (r == c && board[0][0] == s && board[1][1] == s && board[2][2] == s) return true;
        if (r + c == 2 && board[0][2] == s && board[1][1] == s && board[2][0] == s) return true;
        return false;
    }

    @Override public int[][] getBoardSnapshot() {
        int[][] copy = new int[3][3];
        for (int i = 0; i < 3; i++) copy[i] = board[i].clone();
        return copy;
    }

    @Override public int getCurrentTurn() { return currentTurn; }
    @Override public boolean isOver() { return over; }
    @Override public Integer getWinner() { return winner; }
    @Override public List<int[]> getMoveHistory() { return history; }
    @Override public List<int[]> getLastMoveEffects() { return lastEffects; }
    @Override public int getMoveCount() { return history.size(); }
    @Override public int getMoveSeq() { return moveSeq; }
    @Override public int getStepTimeSeconds() { return 30; }
    @Override public int getTotalTimeSeconds() { return 300; }
}
```

### 必须守住的语义

| 方法 | 约定 |
|---|---|
| `side` | 两人棋类为 `1 = 黑/先手`、`2 = 白/后手`；多人规则为 `1..targetPlayers` 座位号，每局轮换座位 |
| `applyMove` | 返回 `false` 表示**指令非法**（不改状态）。房间会回 `无效落子` 并带上真实手数 |
| `getCurrentTurn` | 终局后仍返回最后行动方即可；判断终局只认 `isOver()` |
| `getWinner` | `null = 平局`。注意「未结束」也返回 null，所以调用方必须先看 `isOver()` |
| `getMoveHistory` | 每手一个 `int[]`，回放按它重建。棋盘类约定 `{row,col,side}`；象棋是 5 元素 |
| `getLastMoveEffects` | 本手引起的盘面变化，随 `GAME_MOVE.data.effects` 下发。没有就返回空列表 |
| `moveSeq` | 每接受一手 +1。非棋盘玩法不需要自己校验它（房间只对 `BoardGameEngine` 做幂等检查） |
| `reset()` | 必须在 `setDeck`/`setPlayerName` 之后被房间调用；自己不要依赖构造顺序 |

### 隐藏信息（重要）

如果玩法有「对手不能看到」的信息（手牌、选择、暗子身份），**不要**把它塞进 `GAME_MOVE.data`。

非棋盘玩法覆写 `getViewState(viewerSide)`，按观众裁剪：

```java
@Override
public Object getViewState(int viewerSide) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("turn", currentTurn);
    view.put("players", /* 按 viewerSide 裁剪后的双方状态 */);
    return view;
}
```

房间会为**每个观看者**单独调用一次（`viewerSide = 0` 是观战者），所以同一手广播里每个人收到的 `data.view` 不同。这是卡牌/答题能正确隐藏信息的原因。

棋盘玩法的公开盘面**默认**来自 `getBoardSnapshot()`，房间用它广播 `GAME_SYNC`。如果盘面上也有底牌，必须额外覆写 `getVisibleBoardSnapshot()`：

```java
@Override
public int[][] getVisibleBoardSnapshot() {
    int[][] visible = getBoardSnapshot();
    // 把当前不能公开的身份统一替换成一个公开占位编码。
    for (int r = 0; r < visible.length; r++)
        for (int c = 0; c < visible[r].length; c++)
            if (isHidden(r, c)) visible[r][c] = HIDDEN;
    return visible;
}
```

约定：

- `getBoardSnapshot()` 仍返回**服务器内部真实盘面**，只给规则与 AI 使用；`GameRoom` 的玩家同步与观战使用 `getVisibleBoardSnapshot()`。
- 翻子这一手可以在本手 `effects` 中公开新身份，但不要把整张真实盘面顺带下发。
- 如果身份本身也是隐藏信息，AI 也必须读取公开版本，否则电脑会偷看底牌。
- 第三方客户端也不能把 `getBoardSnapshot()` 当作对外协议；它只属于服务端内部接口。

随机开局的棋盘还要覆写 `getInitialBoardSnapshot()`，否则复盘无法还原洗牌结果：

```java
@Override
public int[][] getInitialBoardSnapshot() {
    return visibleInitialBoardCopy; // 已按公开视角编码
}
```

默认返回 `null`，表示使用前端模块的固定 `initialBoard()`。返回非 `null` 时会随 `GAME_OVER`、房间数据和 `GameRecord.initialBoard` 一起持久化，前端复盘优先使用它。开局快照也必须是观众可见的信息，不能为了“便于复盘”泄漏底牌。

测试至少覆盖三件事：玩家与观战视角在翻牌前看不到身份；翻牌后只公开本手身份；重启或从记录加载后，复盘从保存的随机初始盘面开始而不是空盘。

---

## 3. 写 AI

`src/main/java/com/gomoku/game/GameAI.java` 的默认 `decide()` 已经分流：

- 棋盘玩法 → 覆写 `getBestMove(...)`
- 非棋盘 → 覆写 `chooseAction(...)`
- 一回合多行动 → 再覆写 `shouldEndTurn(...)`

```java
public enum TicTacToeAI implements GameAI {
    INSTANCE;

    @Override
    public int[] getBestMove(BoardGameEngine engine, int side, int difficulty) {
        int[][] b = engine.getBoardSnapshot();
        // 难度只影响搜索深度/随机性，接口不变
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++)
                if (b[r][c] == 0) return new int[]{r, c};
        return null; // 无子可下
    }
}
```

`difficulty` 约定：`2 = 简单`、`4 = 中等`、`6 = 困难`。不需要严格对应搜索深度，但难度必须**真的影响行为**——纯随机但三档一样，玩家会发现。

---

## 4. 接线（两行 switch）

`src/main/java/com/gomoku/game/GameRoom.java`

```java
private static GameEngine createEngine(GameType type) {
    switch (type) {
        // ...
        case TICTACTOE: return new TicTacToeBoard();
        // ...
    }
}

private GameAI aiFor() {
    switch (gameType) {
        // ...
        case TICTACTOE: return TicTacToeAI.INSTANCE;
        // ...
    }
}
```

就这些。匹配、计时、断线重连、回放、记录、ELO 都按 `GameType` 自动生效。

如果你需要：

- **停一手**（围棋）：实现 `pass(int side)`
- **一回合多行动**（卡牌）：`autoEndsTurn()` 返回 `false`，并实现 `endTurn(int side)`
- **双方同时行动**（答题）：`simultaneousTurn()` 返回 `true`
- **战斗日志文案用真实昵称**：实现 `setPlayerName(int side, String name)`
- **需要起点与终点**（象棋、跳棋）：`MOVE` 同时带 `fromRow/fromCol` 和 `row/col`。二进制帧在目标坐标后写 `0x80,fromRow,fromCol`，再写 4 字节序号；旧的无起点帧继续兼容。客户端两阶段选子优先复用 `GameModules[type].moveSelect`。
- **服务端自动掷骰/发牌**（飞行棋）：`reset()` 只清状态，另提供明确的开局 hook（如 `beginTurnRoll()`），由 `GameRoom.startGame()` 在注入昵称后调用。不要在构造器和 `reset()` 各掷一次，否则首个骰子会被覆盖。
- **多人淘汰**（飞行棋）：实现 `forfeit(int side)`，只淘汰该座位并在 `getViewState` 暴露 `eliminated`；只剩最后一人时再判胜。房间会继续轮转其他座位，并为飞行棋广播退出状态。
- **无棋可走自动跳过**：引擎负责 `skipTurn/advance` 并写入 `moveHistory`+`moveSeq`；房间层 `autoSkipIfStuck()` 会补一条空效果到 `moveEffectsHistory`。两边都推进，复盘滑块才能与棋谱逐手对齐。

---

## 5. 前端模块

`src/main/resources/static/js/games.js`

### 棋盘玩法（canvas）

```js
const TICTACTOE = {
  name: '三连棋',
  rows: 3, cols: 3,
  stepTime: 30, totalTime: 300,
  footer: '3×3 · 横竖斜三连获胜',
  icon: '#',
  sides: [{ icon: 'black', label: '黑' }, { icon: 'white', label: '白' }],
  canvasSize() { return 360; },
  initialBoard() { return Array.from({ length: 3 }, () => [0, 0, 0]); },
  draw(ctx, board, opts) { /* 画格子 + 棋子 */ },
  hitTest(mx, my) { /* 像素 → {row, col}，越界返回 null */ },
  drawGhost(ctx, row, col, side) { /* 落子预览，可空实现 */ },
  isLegal(board, row, col) { return board[row] && board[row][col] === 0; },
  legalMoves() { return []; },
  applyEffects(board, effects) { /* 服务端效果回放，可返回 null */ return null; }
};
```

### 非棋盘玩法（HTML 面板）

```js
const TICTACTOE = {
  name: '三连棋',
  html: true,
  panel: 'tictactoe-panel',   // index.html 里的容器 id
  render: 'renderTicTacToePanel', // app.js 里的渲染函数名
  stepTime: 30, totalTime: 300,
  footer: '...', icon: '#',
  actionLabels: ['第 1 格', '第 2 格' /* ... 回放文字 */],
  initialBoard() { return null; },
  draw() {}, hitTest() { return null; }, drawGhost() {},
  isLegal() { return false; }, applyEffects() { return null; }, legalMoves() { return []; }
};
```

最后注册：

```js
window.GameModules = { GOMOKU, REVERSI, GO9, XIANGQI, CONNECT4, RPG, CARD, NIM, QUIZ, TICTACTOE };
```

大厅卡片、匹配、棋盘/面板切换都会自动出现——`buildGameSelector()` 和 `initGameModule()` 都是按 `GameModules` 遍历的。

如果用了 `html: true`，记得在 `index.html` 加面板容器：

```html
<div class="game-panel" id="tictactoe-panel" style="display:none"></div>
```

并在 `app.js` 里实现 `renderTicTacToePanel()`（参照 `renderNimPanel`，数据从 `rpgView` 读）。

---

## 6. 验证

### 单测（必须）

`src/test/java/com/gomoku/game/TicTacToeBoardTest.java`

至少覆盖：

1. 合法手推进回合
2. 非法手被拒且**不改变状态**（重复落子 / 越界 / 非当前回合）
3. 胜负判定（横、竖、斜）
4. 平局
5. 终局后不再接受落子
6. `reset()` 真的清空

### 冒烟（强烈建议）

`docs/smoke/smoke-tictactoe.mjs`：用原生 `WebSocket` 打一局人机或双人对局，断言：

- 双方分到不同 side
- 非法落子被拒且手数不变
- 终局有 `winner` 和完整 `moveHistory`

（可直接复制 `smoke-tictactoe` 最近的同类脚本改游戏名。）

---

## 7. 常见坑

1. **隐藏信息漏进公共字段**：非棋盘玩法把对手手牌/选择放进 `GAME_MOVE.data` 而不是 `getViewState(viewerSide)`，观战者和对手都能看到。卡牌、答题都在这上面踩过。
2. **一回合多行动却忘了 `autoEndsTurn()`**：默认 `true`，出第一张牌回合就交出去，玩家会觉得「只能出一张」。
3. **同时行动忘了 `simultaneousTurn()`**：答题会变成轮流答。
4. **`reset()` 里读玩家配置**：套牌/昵称必须在 `reset()` 之前注入；反过来的话，`reset()` 会把配置冲掉。
5. **`getWinner()` 在未结束时返回 null，被当成平局**：先判 `isOver()`。
6. **`moveSeq` 自己乱校验**：房间只对棋盘玩法做幂等检查；非棋盘玩法自己维护即可。
7. **AI 无子可下时返回坐标 `{-1,-1}`**：应返回 `null`，否则房间会把它当一手合法棋发出去。
8. **`reset()` 后房间行动者过期**：引擎可能在初始化时自动跳过无法行动的座位（21 点天然黑杰克、飞行棋首骰无棋）。`GameRoom.startGame()` 现在会在 `reset()` 后按 `engine.getCurrentTurn()` 重新绑定 `currentPlayer`；新玩法如果有类似「开局自动推进」逻辑，必须保证 `getCurrentTurn()` 返回真实可行动座位，不要只在开局时假设 1 号座位。
9. **多人淘汰只广播空移动**：多人玩法要让 `forfeit(side)` 只淘汰该座位，并在只剩最后一人时由引擎判胜；`GameRoom` 会为飞行棋额外广播 `PLAYER_STATUS`。新玩法若也有淘汰语义，应像飞行棋一样把历史、效果与 `moveSeq` 对齐，避免复盘错位。
10. **自动跳过没有入谱**：房间自动 AI 跳过或引擎掷骰后直接轮转时，若只改状态不追加历史，`moveHistory` 与 `moveEffectsHistory` 会逐手错位。新增「无棋可走/自动跳过」分支时，要同时推进 `moveSeq` 并记录空效果；当前 `GameRoom.autoSkipIfStuck()` 可作参考。
11. **服务端自动掷骰的首骰重复**：像飞行棋这种「开局即掷骰」的玩法，不要在 `reset()` 和房间开局各掷一次；应在房间注入昵称后调用一次明确的开局 hook（如 `beginTurnRoll()`），并用单测断言首骰只执行一次。

---

## 8. 检查清单

- [ ] `GameType` 加枚举（棋盘填 rows/cols）
- [ ] `XxxEngine` 实现 `GameEngine` / `BoardGameEngine`
- [ ] `XxxAI` 实现 `GameAI`，三档难度行为不同
- [ ] `GameRoom.createEngine` / `aiFor` 各加一行
- [ ] `GameModules.XXX` 注册（canvas 或 html 面板）
- [ ] `XxxBoardTest` 覆盖合法/非法/胜负/平局/重置
- [ ] `smoke-xxx.mjs` 跑通一局；走子类玩法至少覆盖强制规则、连续走子、回放和协议非法输入
- [ ] 有真实交互界面的玩法补 `smoke-ui-xxx.mjs`，验证桌面/390px、鼠标命中、AI 行动和运行时异常
- [ ] 多人淘汰玩法补 `forfeit` 单测：一人退出其余人继续、最后一人获胜、`scores` 含全部座位且不进双人 ELO
- [ ] 如果开局会自动掷骰/跳过座位，断言 `startGame()` 后的 `currentPlayer` 与 `engine.getCurrentTurn()` 一致，且首骰只执行一次
- [ ] 新增玩法后同步成就总数（基础 11 + 全模式制霸 + 当前玩法数）、规则卡、约战和结算冒烟
- [ ] `PROGRESS.md` / `README.md` 更新玩法表
