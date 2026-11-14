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

如果玩法有「对手不能看到」的信息（手牌、选择），**不要**把它塞进 `GAME_MOVE.data`，而是覆写：

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

---

## 8. 检查清单

- [ ] `GameType` 加枚举（棋盘填 rows/cols）
- [ ] `XxxEngine` 实现 `GameEngine` / `BoardGameEngine`
- [ ] `XxxAI` 实现 `GameAI`，三档难度行为不同
- [ ] `GameRoom.createEngine` / `aiFor` 各加一行
- [ ] `GameModules.XXX` 注册（canvas 或 html 面板）
- [ ] `XxxBoardTest` 覆盖合法/非法/胜负/平局/重置
- [ ] `smoke-xxx.mjs` 跑通一局
- [ ] `PROGRESS.md` / `README.md` 更新玩法表
