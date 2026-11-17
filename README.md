# 🎮 棋战大厅 · 在线棋类对战平台

Java (Spring Boot + Netty WebSocket) 实现的多游戏在线对战平台，支持**五子棋 / 黑白棋 / 围棋(9路) / 中国象棋 / 四子棋 / 回合制对战 / 卡牌对战 / 取石子 / 答题对战 / 骰子竞速 / 宝石拍卖 / 21 点 / 多人比大小 / UNO 对战**（14 种玩法）的实时匹配、人机对战、观战、断线重连、计时、回放与分游戏 ELO 排名。

> 四类玩法形态，全部复用同一套房间/计时/ELO/存档/回放：
> - **棋类**（5 种）：盘面 + 落子/走子
> - **回合制对战**：HP/MP + 技能
> - **卡牌对战**：手牌/法力/随从，**一回合多行动**、手动选攻击目标、**法术需指定目标**、嘲讽/冲锋/战吼/亡语/圣盾/吸血/连击七关键词、独立牌库
> - **取石子**：组合博弈，AI 有基于异或和（Bouton 定理）的数学完美解
> - **答题对战**：双方答同一题，**提交前互相看不到选择与答案**；连续答对从第二题起额外获得 5 分，答错清零连击
> - **骰子竞速**：2–8 人同时选择稳健/冒险/追击策略，六轮揭晓累计得分；支持隐藏选择、追赶奖励、AI 与观战
>
> 三处关键的通用能力：`getViewState(viewerSide)` **按视角裁剪状态**（隐藏手牌/对手答案）、`autoEndsTurn()`/`endTurn()` **把回合边界交给引擎**（支持一回合多行动）、`simultaneousTurn()` **支持一回合内双方各自行动**。

---

## 多人房间与部署升级（2026-11-14）

- **按玩法配置人数**：`GameType` 声明 `minPlayers / maxPlayers / defaultPlayers`，建房的 `playerCount` 由服务端校验。现有双人规则保持 2 人；比大小、21 点、UNO、骰子竞速和宝石拍卖支持 **2–8 人**，默认 4 人，满员开局。宝石拍卖每轮暗投 0–10 金币，最高价获得 3/5/7/9/12 分宝石，金币余额和出价按视角保护。大厅人数选择用于建房和匹配；好友按房号加入时沿用房间人数。
- **PWA 安装**：提供 `manifest.json`、应用图标与 Service Worker，可安装到桌面/手机；静态资源支持离线外壳，API 与 WebSocket 不缓存。
- **本周活跃榜**：`/api/leaderboard/weekly` 聚合最近 7 天全部真人对局（含 2–8 人休闲局），大厅可在 ELO 总榜与本周活跃榜之间切换。
- **等级与成就**：`/api/me/progress` 从对局记录派生等级、段位与 11 项成就；经验覆盖全部玩法（含 2–8 人休闲局），多人局与新玩法额外加成，AI 局不计经验，无需额外存储即可跨设备延续。
- **每日活跃目标**：游客身份下发每日完成 3 局、赢 1 局、体验 2 种玩法和连续活跃 3 天的进度，数据按稳定身份统计，AI 局不计入任务。
- **每日签到与留存奖励**：`GET/POST /api/checkin`（status / 签到），签到记录独立存 `data/checkins.json`，不受对局记录 5000 条上限影响。每日基础 +15 经验，连续 3/7/14/30 天有里程碑奖励（+30/+80/+150/+400），断签 ≥3 天回归额外 +50。奖励只计入等级经验，不写成对局记录——因此对局数、胜率、ELO、排行榜都不受影响，不能靠签到刷竞技战绩。同一天重复签到不重复发奖。
- **赛季称号**：月度结算固化历史段位快照（`data/seasons.json`），赛季横幅展示称号徽章，换设备登录后可查
- **赛季段位**：`GET /api/season` 返回当前自然月赛季信息（剩余天数）、本人赛季数据与段位、赛季榜。赛季分从对局记录派生（胜 +3 / 平 +1 / 负 0，只认真人局），映射青铜→白银→黄金→铂金→钻石→王者六段位。**赛季不重置、不改写 ELO**——ELO 是长期累计资产，赛季只是「本窗口表现」的另一种视图，零迁移、零历史损失。大厅排行榜支持 ELO 总榜 / 本周活跃 / 赛季段位三态切换。
- **完整多人链路**：独立座位与总时、多名电脑、按引擎轮次行动、断线恢复原座、全体真人确认重开、多人参与者存档、并列胜者。比大小为五轮同时暗选，每人独立持有 1–10，每局一次王牌可将指定牌值翻倍；前四轮最大者得 1 分、第五轮决胜轮得 2 分（并列均得分）；对手手牌与本轮选择不会下发给其他玩家或观众。退出/超时淘汰该座位，其余人继续。
- **同时行动计时**：比大小与答题的待提交玩家独立扣时，提交后停表，整轮结束才重置步时；AI 可独立提交。卡牌出牌或攻击不会延长本回合时限。
- **计分边界**：旧的双人 ELO 仅用于无 AI、不同持久身份的双人竞技局；比大小目前为休闲局，不套用双人 ELO。多人团队胜负与排行榜需要由具体玩法补充，本次没有实现斗地主、掼蛋或麻将规则。
- **游客身份**：`POST /api/guest` 签发稳定随机 token，浏览器本地保存；服务器在 `data/guests.json` 中仅保存 token 哈希。携带 `guestToken` 的 JOIN 将新积分与战绩绑定游客身份，改昵称保留积分、重名互不合并；`GET /api/me/stats` 使用 `Authorization: Bearer <token>` 查询。旧昵称战绩不自动迁移给游客，避免冒领。旧协议客户端仍可不带 token 使用旧昵称统计。清理浏览器数据会丢失游客凭证。
- **持久账号**：登录页可注册/登录账号（用户名 + 加盐哈希密码，`data/accounts.json` 只存盐与哈希）。**注册时携带当前游客 token 会把该身份原地升级为账号**，战绩、ELO、等级、成就、每日进度一条不丢；登录后签发指向同一身份的新 token，换设备也能找回资产。接口：`POST /api/account/register|login|password`、`GET /api/account/me`。密码用 SHA-256 加盐多轮迭代（12000 次）派生，`MessageDigest.isEqual` 恒定时间比对；用户名大小写不敏感。账号 token 与游客 token 走同一套解析链路，下游协议零改动。
- **手机操作**：自适应大厅和座位区、触屏按钮、牌桌布局、减弱动画偏好、可持久化关闭音效。原有合成音效继续复用。
- **可选 AI 导师**：配置 `.env` 的 `COACH_ENABLED / COACH_ENDPOINT / COACH_API_KEY / COACH_MODEL` 后启用。仅把当前请求者可见的同步快照交给管理员配置的 OpenAI 兼容 Chat Completions 服务；密钥留在服务端。默认关闭，限制并发和请求频率。模型建议不保证最优，也不会取代规则 AI。
- **部署与健康**：沿用 Docker + Caddy HTTPS/WSS + 数据卷；新增 `deploy/start.ps1` 和 `deploy/start.sh` 一键检查、构建、等待健康。健康检查同时覆盖 HTTP 与 Netty WebSocket，WebSocket 监听失败会使启动失败。同一 IP 连接数上限调整为 16，可容纳 8 人同网对战和观战。

Windows：

```powershell
# HTTP
./deploy/start.ps1
# HTTPS：域名应已解析到部署机器，80/443 可访问
./deploy/start.ps1 -Https -Domain play.example.org -Email admin@example.org
# 仅校验 Compose
./deploy/start.ps1 -CheckOnly
```

Linux / macOS：

```sh
sh deploy/start.sh http
# 先编辑 .env 中的 DOMAIN / ACME_EMAIL
sh deploy/start.sh https
sh deploy/start.sh http --check
```

备份 `gomoku-data` 时也包含游客身份；请同时保护 `.env` 中的导师密钥。真实公网证书签发需要部署者的域名与可达端口，不能由本地配置校验代替。

协议与验证说明见 [`docs/MULTIPLAYER_UPGRADE.md`](docs/MULTIPLAYER_UPGRADE.md)。

---

## 🎲 游戏列表

| 游戏 | 棋盘 | 规则要点 | AI |
|------|------|---------|-----|
| ⚫ 五子棋 | 15×15 | 黑先手，五连即胜（自由规则），每步 30s / 每方 5min | Minimax + α-β 剪枝（深度 2-6） |
| ◐ 黑白棋 | 8×8 | 夹击翻转；无处可落自动停一手；终局子多者胜，每步 60s | 位置权重 + 行动力 + 浅层搜索 |
| ◆ 围棋 9路 | 9×9 | 气/提子/禁自杀/简单劫；双方连续停一手终局，数子法黑贴 7.5 目，每步 60s / 每方 10min | 启发式（提子/逃叫吃/扩张/避填真眼） |
| ♞ 中国象棋 | 9×10 | 完整走子规则（蹩马腿/塞象眼/过河兵/炮打隔子/九宫），将帅照面禁着，将死/困毙获胜，每步 60s / 每方 10min | 材料评估 + Negamax α-β（深度 1-3） |
| ◉ 四子棋 | 7×6 | 重力落子，横竖斜四子连线获胜，每步 30s / 每方 5min | 滑窗评估 + Negamax α-β（深度 2-6） |
| ⚔ 回合制对战 | 无盘面 | HP 100 / MP 40；普攻、强力一击、火球术、治疗术、防御、喝药水；灼烧与防御姿态；第 25 回合起进入决战阶段（伤害 ×1.5、治疗减半），每步 30s / 每方 5min | 规则式决策（回血 → 斩杀 → 高伤技能 → 防御回蓝） |
| 🂠 卡牌对战 | 无盘面 | 英雄 30 生命；**23 种卡（21 张可组 + 2 张衍生物）**，高费区有战争泰坦/死灵法师/血皇后等终结手段；**七种关键词：嘲讽 / 冲锋 / 战吼 / 亡语 / 圣盾 / 吸血 / 连击**，非冲锋随从有召唤失调；**双方各自 30 张独立牌库**；法力每回合 +1（上限 10，开局 3 费，起手保证至少一张可打）；手牌上限 7、每回合抽 1；牌库抽空进入疲劳；**3 套预组 + 自定义套牌（自己挑 30 张）**；一回合可连出多张牌，手动选择随从攻击目标；火球/鼓舞需指定目标；点「结束回合」收尾，每步 45s / 每方 10min | 规则式决策（先攻击 → 斩杀 → 危急回血 → 横扫清场 → 强力随从 → 鼓舞） |
| ● 取石子 | 无盘面 | 三堆石子（5/7/9），每回合选一堆拿走任意数量；拿走最后一颗者胜，每步 30s | **Bouton 定理完美解**（异或和归零），难度只调「是否总走最优」 |
| ? 答题对战 | 无盘面 | **42 道题库分 6 个主题（科学/人文/技术/地理/历史/生活）**，每局按主题轮转抽 8 题；答对 +10 / 答错 -5（下限 0）；双方答同一题、提交前互不可见，每步 25s | 按难度映射正确率（40%/70%/90%），UI 如实标注它不是真「理解」题目 |

---

## 🚀 快速启动

**前置要求：** JDK 11+，Maven 3.6+

```bash
cd gomoku-game/gomoku-game

# 编译 + 跑单测
mvn clean package

# 启动（Netty WS 9001 + Spring Boot HTTP 9000）
java -jar target/gomoku-game-1.0-SNAPSHOT.jar
```

浏览器打开 `http://localhost:9000` → 选游戏类型 → 点「▶ 人机对战」（卡片上或大厅顶部）→ 选难度 → 立刻开打（无需等对手）。
也可以「创建房间」等人匹配，或加入别人的房间 / 观战；「快速开始」会创建公共房间等待匹配。

配置见 `src/main/resources/application.yml`（`server.port` HTTP / `netty.port` WebSocket）。

### Docker / HTTPS

Docker 部署使用多阶段构建，运行用户为非 root；对局记录与 ELO 数据挂载到 `gomoku-data:/app/data`，容器重建不会丢失。
需要 Docker Compose v2+（已在 Docker 29.9.3 + Compose v5.7.0 上实测支持 HTTPS 覆盖文件中的 `!reset`）。

```bash
# HTTP：应用直接暴露 9000/9001
cp .env.example .env
docker compose up -d --build

# HTTPS/WSS：仅 Caddy 暴露 80/443，app 端口只在 Compose 网络内可见
docker compose -f docker-compose.yml -f docker-compose.https.yml up -d --build
```

HTTPS 模式下浏览器固定连接同源 `wss://<host>/ws`，由 Caddy 转发到 Netty 9001；本地 HTTP 开发仍可直接连接 `ws://localhost:9001/`。
HTTPS 覆盖文件会开启 `NETTY_TRUST_FORWARDED_FOR=true`，从 Caddy 写入的 `X-Forwarded-For` 恢复真实客户端 IP，
避免所有用户被算成同一个容器 IP 而触发连接数限流。该选项默认关闭；只有在 9001 端口仅对受信代理开放时才应开启，
否则直连客户端可以伪造请求头绕过 IP 限流。
备份数据卷示例：

```bash
docker run --rm -v gomoku-data:/data -v "${PWD}":/backup alpine tar czf /backup/gomoku-data.tgz -C /data .
```

`/actuator/health` 仅返回聚合健康状态；`/actuator/prometheus` 提供 JVM/HTTP 等指标。
Actuator 仅暴露 `health,info,prometheus`（`metrics` 默认关闭），且 Caddy 公网入口对 `/actuator`、`/actuator/*`
统一返回 404。Prometheus 请从 Compose 内网 `http://app:9000/actuator/prometheus` 抓取，或按需在内网反向代理放行，
不要直接暴露给公网。

本轮容器验证使用已有 JRE 21 镜像挂载新构建的 jar，通过本地 Caddy HTTPS/WSS 整局测试；生产 Dockerfile 声明的 Java 11 基础镜像下载未完成，其完整构建及公网证书仍需部署环境验证。

---

## 🎮 平台能力

- **游戏大厅**：按游戏类型选择、匹配同类型房间、房间列表实时刷新
- **房号邀请**：创建**私有房间**（不出现在大厅列表、不参与自动匹配）拿 6 位房号，好友输入房号或直接打开 `/?room=XXXXXX` 自动进房；房号大小写、`ROOM-` 前缀都不敏感
- **单机模式 / 人机对战**：卡片或大厅顶部一键进入，**界面内难度弹层**（简单/中等/困难），9 种玩法都能直接开局（人机局不计 ELO）
- **观战模式**：实时同步棋盘（`GAME_SYNC`），房间内可见观战者昵称列表
- **断线重连**：断线后席位保留 60 秒宽限期，可凭 sessionId 重连；超时未归判负
- **计时系统**：步时 + 总时，超时判负（时限由各游戏引擎定义）
- **对局回放**：终局后可逐步/倍速回放（含提子/翻转效果重建）
- **对局记录持久化**：每局落盘 `data/games/`（摘要索引 + 完整棋谱），重启不丢，可随时从大厅列表点开复盘
- **对局记录含套牌**：卡牌对局会记下双方**实际生效**的套牌（预组名或自定义配比摘要）；非法自定义回落均衡时记录也不会误写成「自定义」
- **按套牌筛选最近对局**：卡牌战绩页可按均衡/快攻/控制/自定义筛选；服务端**先过滤后截断**，任意一方使用目标套牌即命中
- **个人战绩**：按游戏统计胜/负/平/胜率与当前 ELO（只统计双方真人的计分局，AI 局不计）
- **ELO 排名**：按游戏分池（互不影响），REST 排行榜 `/api/leaderboard?gameType=`
- **聊天系统**：房间内实时聊天（限流）
- **双协议**：JSON 文本帧 + 自定义二进制帧（魔数 0xCAFE）

---

## 📡 消息协议（WebSocket）

### 客户端 → 服务端

| 类型 | 说明 | 关键字段 |
|------|------|---------|
| `JOIN` | 加入/创建房间 | `playerName`, `gameType`, `roomId?` |
| `SPECTATE` | 观战 | `roomId` |
| `ADD_AI` | 添加电脑 | `data`=难度(2/4/6) |
| `MOVE` | 落子 | `row`, `col`, `moveSeq` |
| `PASS` | 停一手（围棋）；对卡牌语义化为「结束回合」 | — |
| `END_TURN` | 结束回合并结算随从攻击（卡牌） | — |
| `CHAT` | 聊天 | `message` |
| `RESTART` | 请求重玩 | — |
| `SURRENDER` | 认输 | — |
| `RECONNECT` | 断线重连 | `sessionId` |
| `LEAVE` / `GET_ROOMS` / `PING` | 离开 / 刷新列表 / 心跳 | — |

### 服务端 → 客户端

| 类型 | 说明 |
|------|------|
| `GAME_START` | 开局（含 `gameType`、棋色、时限） |
| `GAME_MOVE` | 落子广播（`data`: `{turn, pass, effects}`，effects 为提子/翻转列表） |
| `GAME_OVER` | 终局（`data`: 分数、ELO、`scoreSummary`、`moveHistory`+`moveEffects` 回放数据） |
| `TIMER_SYNC` | 每秒计时同步 |
| `PLAYER_STATUS` | 玩家断线/重连通知（不重置棋盘） |
| `WAITING` / `ROOM_INFO` / `ROOM_LIST` / `GAME_SYNC` / `GAME_CHAT` / `RESTART_REQUEST` / `ERROR` | 常规消息 |

### REST API（HTTP 9000）

| 端点 | 说明 |
|------|------|
| `GET /api/rooms` | 房间列表（含 gameType） |
| `GET /api/leaderboard?gameType=` | 分游戏 ELO 排行榜（已排除 AI） |
| `GET /api/stats` | 在线人数 / 房间统计 |
| `GET /api/games?limit=&gameType=&deck=` | 最近对局列表（不含棋谱，供大厅战绩页；`deck` 按实际生效套牌筛选） |
| `GET /api/games/{id}` | 单局详情（含完整棋谱 + 每手效果，供复盘） |
| `GET /api/stats/player/{name}?gameType=` | 玩家战绩汇总（总分/胜/负/平/胜率） |
| `GET /api/player/{name}/games?limit=` | 玩家最近对局 |
| `GET /actuator/health` | 健康检查（不暴露组件细节） |
| `GET /actuator/prometheus` | Prometheus 指标（仅内网直连；公网入口 404） |

### 对局记录存储

```
data/games/
  index.jsonl                        # 每局一行摘要（不含棋谱），启动时加载供列表/战绩查询
  20261111-203022-a1b2c3.json        # 单局完整记录（含棋谱，供复盘）
```

摘要与详情分开存：列表/战绩只读 `index.jsonl`，不必解析几十 KB 的棋谱；写盘走单线程守护线程，
不阻塞终局广播。超过 600 手的对局只留元信息、不存棋谱，避免单文件过大。

---

## 🏗️ 架构设计

```
com.gomoku/
├── game/
│   ├── GameType.java        # 游戏类型枚举（含 isBoard() 区分棋类/非棋类）
│   ├── GameEngine.java      # 通用对战骨架：出招/停一手/终局/手数/时限/view 快照
│   ├── BoardGameEngine.java # 棋盘子接口：盘面/落子/走子/连线（非棋类不实现）
│   ├── MoveCommand.java     # 行动指令：坐标（at/from）或行动序号（action）
│   ├── GameAI.java          # AI 抽象：decide() 统一产出 MoveCommand
│   ├── GomokuBoard.java     # 五子棋引擎
│   ├── ReversiBoard.java    # 黑白棋引擎
│   ├── GoBoard.java         # 围棋引擎（气/提子/劫/数子）
│   ├── XiangqiBoard / ConnectFourBoard
│   ├── RpgBattleEngine.java # 回合制对战引擎（第一个非棋牌玩法）
│   ├── GomokuAI / ReversiAI / GoAI / XiangqiAI / ConnectFourAI / RpgAI
│   ├── GameRoom.java        # 房间：流程/计时/断线宽限/ELO/存档
│   ├── RoomManager.java     # 匹配/会话/重连/IP限流/清理
│   ├── GameRecord.java      # 对局记录模型（参与者 + 棋谱 + ELO 前后值）
│   ├── GameRecordService.java # 记录落盘(data/games) + 战绩/列表查询
│   └── EloService.java      # 分游戏 ELO 计算 + 持久化(data/elo_ratings.properties)
├── server/                  # Netty WS 管线：路由/二进制编解码/消息分发/REST
├── model/                   # Player / GameMessage
└── dto/                     # Gson 序列化 DTO
```

**加新游戏只需要**：实现 `GameEngine`（规则）+ `GameAI`（电脑对手）+ 前端 `GameModules` 渲染模块，注册到 `GameType` 与 `GameRoom.createEngine/aiFor` 即可。

- 棋类：额外实现 `BoardGameEngine`（提供盘面），出招走坐标，前端用 canvas 渲染
- 非棋类：只实现 `GameEngine`，出招走 `MoveCommand.action(n)`，前端设 `html: true` 并用 HTML 面板渲染 `getViewState()`
- 完整步骤、代码骨架与常见坑：[`docs/ADDING_A_GAME.md`](docs/ADDING_A_GAME.md)

---

## 🧪 测试

```bash
mvn test    # 261 个用例
```

项目还提供统一验证脚本：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify-project.ps1
```

脚本会运行 Java 测试、所有冒烟脚本的 JavaScript 语法检查、静态资源缓存版本检查，以及 HTTP/HTTPS 两套 Compose 配置校验。推送到 `master` 或提交 Pull Request 时，GitHub Actions 会自动执行同一组检查。

覆盖范围：

- **棋盘规则**：五子棋五连/长连、黑白棋翻转与自动 pass、围棋提子/禁自杀/劫/数子、象棋走子、四子棋重力
- **非棋盘玩法**：回合制对战（蓝耗/药水上限/灼烧/防御/决战阶段/收敛性）、卡牌（23 张卡 / 7 关键词 / 3 预组 / 自定义校验 / 起手可打保证 / 隐藏信息）、取石子（Bouton 定理）、答题（同时作答 + 揭晓前信息隔离 + 题库主题覆盖）
- **协议与并发**：`MoveCommand` 桥接语义（坐标与行动序号不得串线）、落子序号幂等
- **房间与匹配**：房号归一化、私有房不进列表/不被匹配、显式房号不回落、幽灵房间不参与匹配
- **存档与积分**：对局记录落盘与重启恢复、记录里的实际套牌与按套牌筛选、ELO 分池与迁移、AI 与排行榜隔离

端到端冒烟（连真实 WebSocket / 真实浏览器，详见 [`docs/smoke/README.md`](docs/smoke/README.md)）：

```bash
# 协议冒烟（17 个）：覆盖 9 种玩法 + 单机 + 观战 + 回放 + 记录 + 房号邀请 + WS 双路径
node docs/smoke/smoke-websocket-paths.mjs # Netty 根路径 / 与代理路径 /ws 握手
node docs/smoke/smoke-solo.mjs            # 9 种玩法逐个「建房 + 加电脑」
node docs/smoke/smoke-connect4.mjs        # 四子棋人机整局（重力落子）
node docs/smoke/smoke-seq.mjs             # 落子序号错位自愈
node docs/smoke/smoke-xiangqi-pvp.mjs     # 象棋 PvP 双端 + ELO 对称
node docs/smoke/smoke-card.mjs            # 卡牌双端对局 + 隐藏信息
node docs/smoke/smoke-card-shield.mjs     # 圣盾 / 亡语召唤
node docs/smoke/smoke-card-keywords.mjs   # 关键词字段完整性
node docs/smoke/smoke-card-target.mjs     # 选目标法术校验
node docs/smoke/smoke-card-deck.mjs       # 三套预组生效 + 牌库完整
node docs/smoke/smoke-card-custom-deck.mjs# 自定义套牌校验与回落
node docs/smoke/smoke-spectator.mjs       # 观战者看不到手牌
node docs/smoke/smoke-nim-quiz.mjs        # 取石子 + 答题
node docs/smoke/smoke-rpg.mjs             # 回合制对战整局
node docs/smoke/smoke-record.mjs          # 对局记录持久化 + 战绩查询
node docs/smoke/smoke-record-deck.mjs     # 记录套牌信息 + deck 筛选
node docs/smoke/smoke-room-invite.mjs     # 房号邀请（建房/加入/私有房）

# 浏览器冒烟（2 个）：需先起 headless Chrome（见 docs/smoke/README.md）
node docs/smoke/smoke-ui-record.mjs       # 战绩面板与复盘弹窗
node docs/smoke/smoke-ui-rpg.mjs          # RPG 面板与文字战报
```

---

## 🗺️ 路线图

- ~~**象棋（Xiangqi）**~~：已完成（引擎 + AI + 前端棋子渲染）
- ~~**对局记录持久化 + 战绩页**~~：已完成（`data/games/` + `/api/games` + 大厅战绩面板 + 独立复盘弹窗）
- ~~**非棋牌玩法**~~：已完成回合制对战（`GameEngine` 拆出 `BoardGameEngine` + `MoveCommand` + `RpgBattleEngine` + HTML 面板渲染 + 文字战报复盘）
- ~~**卡牌对战**~~：已完成（23 张卡 / 7 关键词 / 3 预组 + 自定义套牌 / 逐视角隐藏信息）
- ~~**取石子 + 答题对战**~~：已完成（`simultaneousTurn` 同时作答 + 揭晓前信息隔离；答题 42 题 / 6 主题）
- ~~**单机模式 / 人机对战**~~：已完成（9 种玩法一键开局，界面内难度选择）
- ~~**房号邀请**~~：已完成（私有房 + 6 位房号 + `/?room=` 邀请链接）
- ~~**对局记录含套牌**~~：已完成（记录实际生效套牌与自定义配比摘要）
- ~~**对局记录按套牌筛选**~~：已完成（`/api/games?deck=` 先过滤后截断 + 卡牌战绩页筛选器）
- ~~**新增玩法开发指南**~~：已完成（[`docs/ADDING_A_GAME.md`](docs/ADDING_A_GAME.md)，含可运行骨架与常见坑）
- **禁手规则（连珠）**：五子棋目前是自由规则，加禁手需要引擎支持三三/四四/长连判定
- **斗地主 / 掼蛋 / 麻将**：房间已支持按玩法配置 2–8 人；仍需实现牌型/组队/叫牌/吃碰杠胡等专属规则、AI 和前端。
- **实时动作类 / 开放世界 RPG**：需要 20-60Hz 游戏循环、帧同步与客户端预测回滚，且 WS/TCP 队头阻塞会毁掉手感（需换 UDP/WebRTC），与当前回合制架构不同源，不建议并入
- ~~**Docker 化部署 / WSS/TLS**~~：已完成（多阶段构建、非 root、健康检查、持久化卷 + Caddy HTTPS/WSS）
- **游戏内悔棋**：需要双端确认协议

