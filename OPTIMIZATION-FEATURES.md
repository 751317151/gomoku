# 五子棋项目 — 功能增强优化文档

## 一、走棋计时器

### 设计
- 每步限时 30 秒，总时间每方 5 分钟
- 落子后重置步时，扣减己方总时间
- 步时或总时耗尽 → 超时判负
- 服务端用 `ScheduledFuture` 管理倒计时，每秒推送剩余时间

### 后端改动
| 文件 | 改动 |
|------|------|
| `GameMessage.java` | 新增 `TIMER_SYNC` 类型，添加 `timeLeft`/`totalTimeLeft` 字段 |
| `GameRoom.java` | 新增 `startTurnTimer()`/`cancelTurnTimer()`，handleMove 中重置计时器，超时自动判负 |
| `GomokuAI.java` | AI 回合跳过计时 |

### 前端改动
| 文件 | 改动 |
|------|------|
| `index.html` | 玩家卡片中添加计时显示元素 |
| `app.js` | 处理 `TIMER_SYNC`，更新计时显示 |
| `style.css` | 计时器样式（低时间警告动画） |

---

## 二、对局回放

### 设计
- 游戏结束时保存 `moveHistory` 到 `GameRoom` 中
- 前端可进入回放模式：进度条 + 播放/暂停 + 倍速（1x/2x/4x）
- 回放数据随 `GAME_OVER` 一起发送

### 后端改动
| 文件 | 改动 |
|------|------|
| `GameMessage.java` | `GAME_OVER` 的 data 中附带 `moveHistory` |
| `GameRoom.java` | `endGame()` 中将 `board.getMoveHistory()` 序列化到 data |

### 前端改动
| 文件 | 改动 |
|------|------|
| `index.html` | 回放控制栏（进度条 + 按钮 + 倍速） |
| `app.js` | 回放状态机 + `setInterval` 驱动逐步回放 |
| `style.css` | 回放控制栏样式 |

---

## 三、AI 难度选择

### 设计
- 三档难度：简单(深度2) / 中等(深度4) / 困难(深度6)
- 前端在"添加电脑"按钮旁提供难度选择下拉
- 后端 `addAI` 接收 difficulty 参数，传给 `GomokuAI`

### 后端改动
| 文件 | 改动 |
|------|------|
| `GomokuAI.java` | `getBestMove` 增加 `maxDepth` 参数 |
| `GameRoom.java` | `addAI(difficulty)` 存储 AI 难度，`triggerAIMove` 传递深度 |
| `GameMessage.java` | `ADD_AI` 支持 `data` 字段传递 difficulty |
| `GameServerHandler.java` | 解析 difficulty 参数 |

### 前端改动
| 文件 | 改动 |
|------|------|
| `app.js` | `addAI()` 发送难度参数，`updatePlayerPanel` 渲染难度下拉 |
| `style.css` | 下拉选择器样式 |

---

## 四、ELO 积分排名

### 设计
- 初始 ELO = 1200，K=32
- 每局结束自动计算 ELO 变化
- 积分持久化到 `elo_ratings.json` 文件
- REST API `GET /api/leaderboard` 返回排行榜
- 大厅页面显示排行榜

### ELO 算法
```
Ea = 1 / (1 + 10^((Rb-Ra)/400))
Ra' = Ra + K * (Sa - Ea)    // Sa = 实际得分 (胜1, 负0, 平0.5)
```

### 后端改动
| 文件 | 改动 |
|------|------|
| 新建 `EloService.java` | ELO 计算引擎 + 文件持久化 + 排行榜查询 |
| `Player.java` | 添加 `elo` 字段 |
| `GameRoom.java` | `endGame()` 中调用 `EloService.updateRating()` |
| `PlayerInfoDto.java` | 添加 `elo` 字段 |
| `GameApiController.java` | 添加 `GET /api/leaderboard` |
| `RoomListItemDto.java` | 玩家信息中包含 ELO |

### 前端改动
| 文件 | 改动 |
|------|------|
| `index.html` | 大厅中添加排行榜区域 |
| `app.js` | 渲染排行榜，玩家面板显示 ELO |
| `style.css` | 排行榜样式 |
