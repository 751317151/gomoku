# 五子棋在线对战 - 优化清单

> 基于 2026-04-18 代码审查生成，同日完成全部修复

## P0 - 必须修复（正确性/安全性）

| # | 问题 | 状态 | 说明 |
|---|------|------|------|
| 1 | 并发安全 | ✅ | `CopyOnWriteArrayList` + `synchronized` |
| 2 | JSON 手拼注入 | ✅ | DTO + Gson 序列化 |
| 3 | 前端 XSS | ✅ | `handleRoomList` 中 roomId/playerName 使用 `escHtml()` |
| 4 | Timer 不安全 | ✅ | `ScheduledExecutorService` 替换 `Timer` |
| 5 | **AI 竞态条件** | ✅ | `getBestMove()` 改用 `getBoardSnapshot()` 快照，不再直接修改原棋盘 |
| 6 | **前端 XSS (updatePlayerPanel)** | ✅ | 玩家名使用 `escHtml()` 转义后再插入 innerHTML |

## P1 - 应该修复（稳定性/一致性）

| # | 问题 | 状态 | 说明 |
|---|------|------|------|
| 7 | GameServerHandler 线程安全 | ✅ | `RoomManager` 方法加 `synchronized` |
| 8 | 断线/重连 | ✅ | `sessionId` + `RECONNECT` 消息 + localStorage |
| 9 | AI 对局重启 | ✅ | 双方确认机制（AI 直接同意） |
| 10 | 棋盘状态一致性 | ✅ | `GAME_SYNC` + `getBoardSnapshot()` |
| 11 | **重连身份丢失** | ✅ | `handleGameSync` 根据 `msg.playerId/msg.stone` 区分观战和重连 |
| 12 | **AI 估值重复计算** | ✅ | 方向级评估改为去重评估，用起始位置+方向做 key 避免重复 |
| 13 | **冲四=活三** | ✅ | `SCORE_RUSH_FOUR` 调整为 10000，大于 `SCORE_LIVE_THREE=5000` |
| 14 | **操作无限流** | ✅ | `Player.canPerformAction()` 300ms 间隔限流，MOVE/JOIN/ADD_AI/RESTART 均接入 |
| 15 | **WebSocket 帧无大小限制** | ✅ | `WebSocketServerProtocolHandler` 配置 `maxFramePayloadLength=65536` |

## P2 - 建议优化（代码质量/可维护性）

| # | 问题 | 状态 | 说明 |
|---|------|------|------|
| 16 | 前端去重 | ✅ | 保留大厅版，删除 `static/index.html` |
| 17 | GameRoom 职责 | 🔶 | DTO 已提取，消息广播仍在 Room 内（可接受） |
| 18 | 日志和异常处理 | ✅ | `channelRead0` 全局 try-catch + logger |
| 19 | 玩家名截断 | ✅ | `NameSanitizer` 工具类 |
| 20 | 魔数常量 | ✅ | AI 评分常量 + `AI_DELAY_MS` 已提取 |
| 21 | **日志框架不统一** | ✅ | 全部从 JUL 迁移到 SLF4J/Logback，与 Spring Boot 一致 |
| 22 | **getBoard() 暴露内部数组** | ✅ | 降级为包级访问（无 public），AI 使用 `getBoardSnapshot()` |
| 23 | **端口不一致** | ✅ | `application.yml` 统一为 8080，日志动态读取 `server.port` |
| 24 | **RoomListItemDto 无用 import** | ✅ | 已移除 `com.gomoku.model.Player` 和 `java.util.stream.Collectors` |
| 25 | **aiExecutor 无优雅关闭** | ✅ | 新增 `shutdownAIExecutor()` 方法，`GomokuApplication@PreDestroy` 调用 |

## P3 - 增强功能（用户体验/可扩展性）

| # | 问题 | 状态 | 说明 |
|---|------|------|------|
| 26 | AI 强度 | ✅ | Minimax + Alpha-Beta 剪枝（深度2） |
| 27 | **AI 搜索深度** | ✅ | 深度提升到 4 + 着法排序（`sortCandidates`）+ 候选数限制（`MAX_CANDIDATES=20`） |
| 28 | 观战体验 | ✅ | `SPECTATE` + `GAME_SYNC` |
| 29 | 前端响应式 | ✅ | 媒体查询已实现 |
| 30 | 游戏记录 | 🔶 | `moveHistory` 已有，未持久化 |
| 31 | 心跳/超时 | ✅ | `IdleStateHandler` + 客户端心跳 |
| 32 | 聊天限流 | ✅ | `Player.canSendChat()` 令牌桶 |
| 33 | Spring Boot 配置 | ✅ | `application.yml` |
| 34 | **触屏支持** | ✅ | Canvas 添加 `touchstart` 事件，移动端可正常落子 |
| 35 | **落子音效** | ✅ | Web Audio API 生成落子/胜利/失败音效 |
| 36 | **认输功能** | ✅ | 新增 `SURRENDER` 消息类型 + `GameRoom.surrender()` + 前端认输按钮 |

## 修改文件清单

| 文件 | 修改内容 |
|------|---------|
| `GomokuAI.java` | 竞态修复(getBoardSnapshot)、估值去重、冲四分数、搜索深度4、着法排序、候选数限制、findUrgentMove优化 |
| `GomokuBoard.java` | `getBoard()` 降级为包级访问 |
| `GameRoom.java` | surrender()方法、SLF4J、aiExecutor优雅关闭 |
| `RoomManager.java` | SLF4J、移除无用import |
| `GameServerHandler.java` | SLF4J、操作限流、SURRENDER处理、WAITING消息恢复 |
| `NettyWebSocketServer.java` | SLF4J、帧大小限制、端口动态读取 |
| `Player.java` | canPerformAction()限流 |
| `GameMessage.java` | SURRENDER枚举 |
| `GomokuApplication.java` | @PreDestroy调用shutdownAIExecutor |
| `application.yml` | 端口修正8090→8080 |
| `index.html` | XSS修复(escHtml)、重连身份修复、触屏支持、音效、认输按钮 |
| `RoomListItemDto.java` | 移除无用import |
