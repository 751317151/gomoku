# 🎮 五子棋在线对战游戏

Java WebSocket 实现的五子棋在线对战系统，支持两人实时对战。

---

## 📁 项目结构

```
gomoku-game/
├── pom.xml                          # Maven 配置
├── frontend/
│   └── index.html                   # 游戏前端（直接用浏览器打开）
└── src/main/java/com/gomoku/
    ├── server/
    │   └── GameServer.java          # WebSocket 服务器主入口
    ├── game/
    │   ├── GomokuBoard.java         # 棋盘逻辑 & 胜负判断
    │   ├── GameRoom.java            # 游戏房间管理
    │   └── RoomManager.java         # 全局房间 & 玩家管理
    └── model/
        ├── Player.java              # 玩家模型
        └── GameMessage.java         # WebSocket 消息协议
```

---

## 🚀 快速启动

### 方式一：Maven 编译运行

**前置要求：** JDK 11+，Maven 3.6+

```bash
# 1. 进入项目目录
cd gomoku-game

# 2. 编译打包
mvn clean package -q

# 3. 启动服务器（默认端口 9001）
java -jar target/gomoku-server-jar-with-dependencies.jar

# 或指定端口
java -jar target/gomoku-server-jar-with-dependencies.jar 9000
```

### 方式二：IDE 运行

用 IntelliJ IDEA / Eclipse 导入 Maven 项目，运行 `GameServer.main()`

---

## 🎮 游戏方法

1. 启动服务器后，用浏览器打开 `frontend/index.html`
2. **同时打开两个浏览器窗口/标签页**（模拟两个玩家）
3. 两个窗口都输入服务器地址（默认 `ws://localhost:9001`）和昵称
4. 点击「进入对战」，系统自动匹配对手
5. 黑棋先手，轮流点击棋盘落子
6. 五子连珠获胜！

---

## 📡 WebSocket 消息协议

### 客户端 → 服务端

| 类型 | 说明 | 必填字段 |
|------|------|---------|
| `JOIN` | 加入游戏 | `playerName` |
| `MOVE` | 落子 | `row`, `col` |
| `CHAT` | 聊天 | `message` |
| `RESTART` | 请求重玩 | — |
| `LEAVE` | 离开 | — |

### 服务端 → 客户端

| 类型 | 说明 |
|------|------|
| `WAITING` | 等待对手 |
| `GAME_START` | 游戏开始（含房间、棋色信息） |
| `GAME_MOVE` | 落子广播 |
| `GAME_OVER` | 游戏结束（含胜者、分数） |
| `GAME_CHAT` | 聊天广播 |
| `ERROR` | 错误消息 |

---

## ⚙️ 核心设计

- **自动匹配**：玩家加入时自动寻找等待中的房间，无则创建新房间
- **轮次管理**：黑棋先手，交替对弈，服务端校验落子合法性
- **胜负判断**：四方向（横/竖/斜）检测五子连珠，支持平局检测
- **断线处理**：对手断线时另一方自动获胜，房间自动清理
- **多局对战**：支持连续多局，自动交换先后手
- **聊天系统**：实时房间内聊天

---

## 🔧 扩展建议

- 增加 AI 对战（Minimax + Alpha-Beta 剪枝）
- 添加禁手规则（职业五子棋）
- 观战模式
- 玩家排行榜（接 Redis/数据库）
- HTTPS + WSS 安全连接
- Docker 容器化部署
