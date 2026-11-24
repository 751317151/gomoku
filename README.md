# 🎮 棋战大厅 · 在线棋类对战平台

Java (Spring Boot + Netty WebSocket) 实现的多游戏在线对战平台，支持**五子棋 / 黑白棋 / 围棋(9路) / 中国象棋 / 西洋跳棋 / 斗兽棋 / 暗棋 / 飞行棋 / 四子棋 / 回合制对战 / 卡牌对战 / 取石子 / 答题对战 / 骰子竞速 / 宝石拍卖 / 21 点 / 多人比大小 / UNO 对战 / 炸弹猫 / 斗地主**（20 种玩法）的实时匹配、人机对战、观战、断线重连、计时、回放与分游戏 ELO 排名。

面向持续线上运营的阶段目标、数据可靠性与留存验证安排，见 [`docs/PRODUCT_ROADMAP.md`](docs/PRODUCT_ROADMAP.md)。

> 四类玩法形态，全部复用同一套房间/计时/ELO/存档/回放：
> - **棋类**（8 种）：盘面 + 落子/走子；西洋跳棋含强制吃子、连吃与升王，斗兽棋含八兽相克、河流与陷阱，暗棋含 32 枚随机底牌、翻子/走子二选一与真实隐藏信息
> - **派对棋**：飞行棋支持 2–4 人、4 枚棋子、6 点起飞、跳格、撞回与中心终点；座位上有人退出时其余人继续
> - **回合制对战**：HP/MP + 技能
> - **卡牌对战**：手牌/法力/随从，**一回合多行动**、手动选攻击目标、**法术需指定目标**、嘲讽/冲锋/战吼/亡语/圣盾/吸血/连击七关键词、独立牌库
> - **斗地主**：3 人经典牌局，54 张牌、叫地主/底牌、单张/对子/三带/顺子/连对/飞机/四带二/炸弹/火箭，完整倍数与春天结算
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
- **等级与成就**：`/api/me/progress` 从完整对局历史派生等级、称号与 32 项成就（含 20 种玩法各自的首胜成就与「全模式制霸」）；大厅可打开成就墙按全部/已解锁/未解锁筛选，展示每项进度条。经验覆盖全部玩法（含 2–8 人休闲局），多人局与新玩法额外加成。人机局同样计入经验（单人也能成长），只是不计 ELO 与竞技战绩；长期历史存于 SQLite 摘要索引，**不受大厅最近 5000 条窗口影响**，重启或跨设备登录仍可延续。
- **每日任务**：`/api/quests` 从对局记录实时派生 5 项每日任务（完成 3 局、赢 1 局、体验 2 种玩法、3 人以上真人对局、连续活跃 3 天）的进度与奖励状态，`POST /api/quests/{id}/claim` 领取经验（+30/+40/+35/+40/+50）。只有「领取」会落盘（`data/daily_quests.json`），进度不存第二份计数；任务经验计入等级、不写对局记录，因此不刷战绩/ELO。跨天进度重置，累计任务经验终身保留。人机局计入任务进度，方便单人玩家完成。
- **每日签到与留存奖励**：`GET/POST /api/checkin`（status / 签到），签到记录独立存 `data/checkins.json`，不受对局记录 5000 条上限影响。每日基础 +15 经验，连续 3/7/14/30 天有里程碑奖励（+30/+80/+150/+400），断签 ≥3 天回归额外 +50。奖励只计入等级经验，不写成对局记录——因此对局数、胜率、ELO、排行榜都不受影响，不能靠签到刷竞技战绩。同一天重复签到不重复发奖。
- **赛季称号**：月度结算固化历史段位快照（`data/seasons.json`），赛季横幅展示称号徽章，换设备登录后可查
- **赛季段位**：`GET /api/season` 返回当前自然月赛季信息（剩余天数）、本人赛季数据与段位、赛季榜。赛季分从对局记录派生（胜 +3 / 平 +1 / 负 0，只认真人局），映射青铜→白银→黄金→铂金→钻石→王者六段位。**赛季不重置、不改写 ELO**——ELO 是长期累计资产，赛季只是「本窗口表现」的另一种视图，零迁移、零历史损失。大厅排行榜支持 ELO 总榜 / 本周活跃 / 赛季段位三态切换。
- **完整多人链路**：独立座位与总时、多名电脑、按引擎轮次行动、断线恢复原座、全体真人确认重开、多人参与者存档、并列胜者。比大小为五轮同时暗选，每人独立持有 1–10，每局一次王牌可将指定牌值翻倍；前四轮最大者得 1 分、第五轮决胜轮得 2 分（并列均得分）；对手手牌与本轮选择不会下发给其他玩家或观众。退出/超时淘汰该座位，其余人继续。
- **同时行动计时**：比大小与答题的待提交玩家独立扣时，提交后停表，整轮结束才重置步时；AI 可独立提交。卡牌出牌或攻击不会延长本回合时限。
- **计分边界**：旧的双人 ELO 仅用于无 AI、不同持久身份的双人竞技局；比大小目前为休闲局，不套用双人 ELO。多人团队胜负与排行榜需要由具体玩法补充，本次没有实现斗地主、掼蛋或麻将规则。
- **游客身份**：`POST /api/guest` 签发稳定随机 token，浏览器本地保存；服务器在 `data/guests.json` 中仅保存 token 哈希。携带 `guestToken` 的 JOIN 将新积分与战绩绑定游客身份，改昵称保留积分、重名互不合并；`GET /api/me/stats` 使用 `Authorization: Bearer <token>` 查询。旧昵称战绩不自动迁移给游客，避免冒领。旧协议客户端仍可不带 token 使用旧昵称统计。清理浏览器数据会丢失游客凭证。
- **持久账号**：登录页可注册/登录账号（用户名 + 加盐哈希密码，`data/accounts.json` 只存盐与哈希）。**注册时携带当前游客 token 会把该身份原地升级为账号**，战绩、ELO、等级、成就、每日进度一条不丢；登录后签发指向同一身份的新 token，换设备也能找回资产。接口：`POST /api/account/register|login|password|logout|logout-others`、`GET /api/account/me`。密码用 **PBKDF2-HMAC-SHA256（310,000 次）** + 每账号随机盐派生，`MessageDigest.isEqual` 恒定时间比对；旧版 SHA-256 迭代记录在下次成功登录时透明升级，账号和战绩无需迁移。用户名大小写不敏感。账号 token 与游客 token 走同一套解析链路，下游协议零改动。
- **账号安全与会话撤销**：登录页「账号安全」可改密、退出其他设备。改密会撤销该身份全部旧会话并只给当前设备换发新 token；「退出其他设备」保留本机会话，主动退出会立即撤销当前凭证。撤销同时作用于 REST 与 WebSocket：旧 token 无法继续读取资产或在大厅/房间发言，在线好友状态也会随连接断开及时更新。登录对同一用户名 10 分钟 5 次失败自动锁定，成功登录清零。
- **恢复码与密码找回**：注册成功时一次性下发 20 位恢复码（去混淆字符集 `A-HJ-NP-Z2-9`），服务端只保存 PBKDF2 哈希与随机盐，**明文仅显示一次**，不写 localStorage。登录页「忘记密码？用恢复码找回」可凭用户名 + 恢复码设置新密码；恢复码一次性使用，成功后撤销全部旧会话并换发新恢复码，所有旧设备需重新登录。已登录玩家可在「账号安全」中验证当前密码后重新生成恢复码，旧码立即失效。同一用户名 10 分钟内最多 5 次恢复失败，错误提示不区分用户名是否存在，避免枚举账号。接口：`POST /api/account/recover-password`、`POST /api/account/rotate-recovery-code`。
- **好友系统**：大厅「好友」面板支持互加好友——每个持久身份有 8 位好友码（去掉 I/O/0/1 的去混淆字符集），账号还可直接按用户名添加；申请需对方同意（可拒绝/撤回）。好友列表实时显示在线状态与所在房间，可 **一键加入好友的房间**（私有房也返回房号——房号对好友即邀请语义）。**WS 实时推送**：连接建立后前端发 `HALL_BIND` 绑定大厅身份（不进房也算在线），好友上线/下线/收到申请/申请通过都会即时推送 `FRIEND_EVENT` 弹提醒并刷新面板，15 秒轮询只作兜底。**好友私聊**：好友面板点 💬 与好友实时对话（`DIRECT_MSG`，仅好友之间、1 秒限流、200 字截断，弹窗关闭时累计未读徽标）。**对方不在线时自动转为离线留言**：暂存 `data/offline_messages.json`（每人最多 50 条、保留 7 天、超限丢最旧），对方下次进入大厅即刻补发并并入聊天历史，且只投递一次。关系按 identityId 存 `data/friends.json`（只存身份与关系，不存昵称），换设备登录不丢；好友上限 50、每方向待处理申请上限 20。展示名解析顺序：实时在线昵称 → 账号昵称 → 最近对局昵称。接口：`GET /api/friends`、`POST /api/friends/requests`、`POST /api/friends/requests/{id}/accept|decline`、`DELETE /api/friends/requests/{id}`、`DELETE /api/friends/{id}`。
- **好友组队（Party）**：好友面板每位好友旁 👥 一键邀请在线好友组队，邀请人自动建队并成为队长；邀请 2 分钟有效、队伍上限 8 人、只有队长能邀请与开局。队长选好玩法后点「▶ 带全队开局」，**服务端一次性把全队放进同一间私房并同时下发开局**——不是各自 JOIN 再补通知，所以不会出现半队进房、人数与玩法错配，也不会把正在对局的好友拽出来。人数超限、有人离线/多标签页/被隔离在别的房间时整队拒绝并提示原因，不留空房间；断线重连留下的旧席位会在开局前自动释放。房间容量取队伍人数（3 人队伍可直接开 2–8 人玩法）。队长退出自动移交，最后一人退出即解散；组队房间是私房，不进大厅公共列表（房间卡只显示队伍短码），座位显示同一队伍徽标。队伍为纯内存状态（重启即散）；接口：`GET /api/party`、`POST /api/party/invite`、`POST /api/party/invites/{id}/accept|decline`、`DELETE /api/party/members`、`POST /api/party/start`。
- **好友双人约战**：在好友面板选择玩法，点在线好友旁「约战」；对方收到实时邀请，可接受、拒绝，发起人可撤回。**发邀请不建房、不占座，接受时服务端重验双方并一次安排进入同一双人私房**；20 种现有玩法均可双人开局，多人玩法也按 2 人容量直接开始。邀请 2 分钟有效，双方不能交叉重复邀请；同一发起人最多 3 条、接收人最多 5 条。离线、封禁、多标签页或仍在其他房间时拒绝开局且保留邀请供重试；成功开局清掉双方关联的其他约战并通知受影响好友。界面有倒计时、等待回应状态与 15 秒补拉；手机接受后回到对局顶部。邀请为纯内存状态，重启失效。接口：`GET/POST /api/duels`、`POST /api/duels/{id}/accept|decline`、`DELETE /api/duels/{id}`。
- **匹配体验**：大厅每个玩法卡实时显示「⚡ N 人等待 / ▶ N 桌进行中」，数据来自只读统计 `GET /api/match/overview`（与快速匹配同一口径：公开、未满、人类座位在线；**不维护第二份排队状态**）。进入等待房后出现匹配助手条：已等待时长每秒递增、同玩法等待人数提示，以及「补齐电脑开局 / 复制邀请 / 返回大厅」三个动作——任何玩法在无人可配时都能一键补满电脑立刻开打。**同时修复一条断流缺陷**：补了一部分电脑的多人等待房曾因「AI 没有连接」被误判为掉线幽灵房，永远不会被快速匹配到；现在只按人类座位是否在线判定，电脑占座不再阻止真人补位。
- **运营治理（举报/禁言/封禁）**：房间内可举报同房间的其他真人（辱骂/刷屏/作弊/挂机/其他 + 补充说明），举报记录带房间号、玩法与双方身份快照并持久化 `data/moderation.json`；同一举报人 10 分钟内重复举报自动去重。管理员通过 `?admin=1` 输入令牌打开治理后台，可一键禁言/封禁（1h/24h/7d/30d/永久）、忽略举报或撤销处置。封禁身份连大厅都进不去，禁言身份仍可对局但发不出聊天与私聊。管理接口用 `X-Admin-Token`（`.env` 的 `ADMIN_TOKEN`，恒定时间比对；留空则整个管理接口返回 503，避免默认口令裸奔）。玩家侧接口：`GET /api/moderation/me`、`GET /api/moderation/reports/mine`。
- **运营数据看板**：`?admin=1` 进入治理后台后可切到「运营数据」页签，查看今日/窗口活跃用户、真人对局与计分局数、逐日趋势、玩法热度排行（局数/占比/平均参与人数）、24 小时时段分布，以及 D1/D7 留存。新增**产品漏斗**：进入大厅 → 选择玩法 → 成功入座 → 开局 → 完局 → 再来一局，并拆分真人/人机、按入口（快速匹配/创建房间/房号/人机/组队/约战）统计开局完局率、匹配等待时长分桶、匹配失败原因与重连成功率。漏斗事件由服务端在真实边界记录（前端无法伪造开局/完局），补上了只看对局记录时看不到的「进大厅但没开局」流失；身份只保存加盐 SHA-256 前 16 位，按日 JSON 保留 90 天。**人机局不计入真人活跃**，避免刷人机刷高日活；口径说明随数据一起下发。接口：`GET /api/admin/ops?days=14`（需 `X-Admin-Token`），事件目录可用 `gomoku.analytics.directory` 配置。
- **运营公告**：治理后台新增「公告管理」页签，可发布普通/重要/维护三档公告，选择 1 天/7 天/30 天或永久展示，并随时下线、重新上线或删除。大厅顶部横幅按新旧展示最多 3 条生效公告，级别用不同配色区分；每条公告可单独关闭，关闭记录按公告 id 存在本机，刷新不再打扰（其他玩家不受影响）。过期由服务端判定并自动隐藏，避免各端时钟差异。公告持久化 `data/announcements.json`（只保留最近 100 条，原子落盘）；玩家侧 `GET /api/announcements` 公开只读，管理侧 `GET/POST /api/admin/announcements`、`POST/DELETE /api/admin/announcements/{id}` 需 `X-Admin-Token`。新增公告不用发版、不用重启。
- **限时活动加成**：治理后台新增「活动加成」页签，可按玩法（全部或单一玩法）发布经验加成活动，比例 25%/50%/100%、时长 24 小时到 30 天，并可暂停、恢复或删除。大厅顶部用金色胶囊展示生效中的活动与剩余时间，判活按服务端时间，避免各端时钟差异；终局结算浮层追加「活动「…」+N% 经验」徽标。加成在结算时连同活动 id/标题/比例**刻进对局记录**，因此活动暂停或结束后历史经验不会缩水；多个活动同时命中时取最高比例。活动定义持久化 `data/events.json`（只保留最近 100 条，原子落盘）；玩家侧 `GET /api/events` 公开只读，管理侧 `GET/POST /api/admin/events`、`POST/DELETE /api/admin/events/{id}` 需 `X-Admin-Token`。发布活动不用发版、不用重启。
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
| ◒ 西洋跳棋 | 8×8 | 红方先手；强制吃子、连吃、升王；无子或无合法走法判负，80 个半回合无吃子/升王判和；每步 45s / 每方 8min | 吃子/升王/前压/中心控制的启发式 |
| 🐾 斗兽棋 | 9×7 | 红方先手；象>狮>虎>豹>狼>狗>猫>鼠，鼠可吃象、象不能吃鼠；仅鼠可入河，狮虎可跳河；陷阱降战力，进兽穴获胜；120 半回合无吃子判和 | 吃子价值 + 兽穴距离 + 跳河/陷阱风险 |
| 🀄 暗棋 | 4×8 | 32 枚红黑棋子全背面随机落位、红方先手；每回合翻任意暗子或移动己方明棋；帅>仕>相>车>马>炮>兵，兵可吃帅、帅不能吃兵，炮隔子吃子不受等级限制；无子或无合法行动判负，120 半回合无吃子/翻子判和；每步 30s / 每方 10min | 只读公开盘面：翻子信息价值 + 吃子/被吃风险 + 炮架机会 |
| ✈ 飞行棋 | 15×15 外圈 | 2–4 人、每方 4 枚；掷出 6 点起飞并保留回合、连续三个 6 强制换手；跳格、撞回、精确到达中心终点；每步 30s / 每方 10min | 简单随机、中等优先撞子/终点、困难增加安全格评估 |
| ◉ 四子棋 | 7×6 | 重力落子，横竖斜四子连线获胜，每步 30s / 每方 5min | 滑窗评估 + Negamax α-β（深度 2-6） |
| ⚔ 回合制对战 | 无盘面 | HP 100 / MP 40；普攻、强力一击、火球术、治疗术、防御、喝药水；灼烧与防御姿态；第 25 回合起进入决战阶段（伤害 ×1.5、治疗减半），每步 30s / 每方 5min | 规则式决策（回血 → 斩杀 → 高伤技能 → 防御回蓝） |
| 🂠 卡牌对战 | 无盘面 | 英雄 30 生命；**23 种卡（21 张可组 + 2 张衍生物）**，高费区有战争泰坦/死灵法师/血皇后等终结手段；**七种关键词：嘲讽 / 冲锋 / 战吼 / 亡语 / 圣盾 / 吸血 / 连击**，非冲锋随从有召唤失调；**双方各自 30 张独立牌库**；法力每回合 +1（上限 10，开局 3 费，起手保证至少一张可打）；手牌上限 7、每回合抽 1；牌库抽空进入疲劳；**3 套预组 + 自定义套牌（自己挑 30 张）**；一回合可连出多张牌，手动选择随从攻击目标；火球/鼓舞需指定目标；点「结束回合」收尾，每步 45s / 每方 10min | 规则式决策（先攻击 → 斩杀 → 危急回血 → 横扫清场 → 强力随从 → 鼓舞） |
| ● 取石子 | 无盘面 | 三堆石子（5/7/9），每回合选一堆拿走任意数量；拿走最后一颗者胜，每步 30s | **Bouton 定理完美解**（异或和归零），难度只调「是否总走最优」 |
| ? 答题对战 | 无盘面 | **42 道题库分 6 个主题（科学/人文/技术/地理/历史/生活）**，每局按主题轮转抽 8 题；答对 +10 / 答错 -5（下限 0）；双方答同一题、提交前互不可见，每步 25s | 按难度映射正确率（40%/70%/90%），UI 如实标注它不是真「理解」题目 |
| ♠ 21 点 | 无盘面 | 2–8 人对庄家，6 副牌；庄家 17 停牌，天然 Blackjack；每步 30s / 每方 5min | 按公开牌面估算爆牌风险与停牌收益 |
| ⚖ 多人比大小 | 无盘面 | 2–8 人五轮同时暗选；每人 1–10，王牌每局一次可翻倍指定牌；第 5 轮 2 分，并列均得分；每步 30s / 每方 5min | 结合轮次、对手风格与王牌时机出牌 |
| 🎴 UNO 对战 | 无盘面 | 2–8 人，7 张起手；颜色/数字/跳过/反转/+2/万能/+4；0 轮转手牌、7 交换手牌、喊 UNO/抓 UNO；每步 30s / 每方 5min | 优先保持手牌可出并保留变色牌收尾 |
| 🃏 斗地主 | 无盘面 | 3 人、54 张牌、每人 17 张 + 3 张底牌；叫 1–3 分或不叫，地主先手；完整牌型与压牌、炸弹/火箭翻倍、春天/反春天；每步 30s / 每方 10min | 叫分按大牌与炸弹估值；出牌优先走小牌/一手走完，农民不压队友 |
| 🎲 骰子竞速 | 无盘面 | 2–8 人六轮，稳健 1d6、冒险 2d6 出 1 爆掉、追击落后 +3；选择隐藏；每步 25s / 每方 4min | 按分差与轮次在稳健、冒险和追击间切换 |
| 💎 宝石拍卖 | 无盘面 | 2–8 人五轮，初始 30 金币；暗投 0–10，最高价获得 3/5/7/9/12 分宝石，所有人支付出价，并列最高都得分；每步 30s / 每方 5min | 按宝石价值、金币存量与对手出价节奏估值 |

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
应用容器以非 root + 只读根 + `cap_drop: ALL` 运行，内存上限 1g、进程数 512；Caddy 仅保留绑定 80/443 所需的 `NET_BIND_SERVICE`。
治理后台依赖 `.env` 的 `ADMIN_TOKEN`，留空时管理接口会安全地返回 503，`deploy/start.ps1` 会给出警告。

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
`CADDY_HTTP_PORT` / `CADDY_HTTPS_PORT` / `CADDY_HTTPS_UDP_PORT` 控制 Caddy 对外端口（默认 80/443），
与应用的 `HTTP_PORT` / `WS_PORT` 无关；本地用高位端口验收时可单独覆盖。
备份数据卷（推荐直接使用随项目提供的运维脚本，先停应用再打包，并校验归档内容）：

```bash
# Windows（默认保留最近 7 份）
./deploy/backup.ps1
# Linux / macOS
sh deploy/backup.sh

# 恢复：先自动保护性备份当前卷，再清空目标卷解压，最后等待健康检查
./deploy/restore.ps1 -BackupFile backups/gomoku-data-20261121-120000.tgz -ConfirmRestore
sh deploy/restore.sh backups/gomoku-data-20261121-120000.tgz gomoku --confirm
```

卷名默认是 `<Compose 项目名>_gomoku-data`（仓库根目录直接启动时通常为 `gomoku_gomoku-data`）。手动打包时请先 `docker compose stop app`，保证 SQLite 主文件与 WAL 是同一时刻的一致快照；`deploy/backup.*` 就是这么做的。备份默认落在 `backups/`（已加入 `.gitignore` 和 `.dockerignore`），请再复制到另一台机器或对象存储——只留在同一块磁盘不算灾备。

**备份完整性**：对局历史由 `data/games/index.jsonl`（可重放摘要日志）和 `data/games/archive.sqlite`（全量索引）共同承载，详情在 `data/games/*.json`。在线备份必须同时包含 SQLite 主文件与 `archive.sqlite-wal`、`archive.sqlite-shm`，或先停应用再打包；只拷 SQLite 主文件会丢掉尚未 checkpoint 的 WAL 记录。`index.jsonl` 与详情 JSON 是重建来源，不要单独删除。`archive.sqlite` 丢失后会在下次启动时从 `index.jsonl` 全量重建（已迁移的记录重新导入是幂等的，不会重复发经验）；反之，日志被截断不会删除已提交的索引记录。上述流程已在本地隔离环境验证，真实公网备份/恢复演练仍待生产服务器执行。

`/actuator/health` 仅返回聚合健康状态；`/actuator/prometheus` 提供 JVM/HTTP 等指标。
Actuator 仅暴露 `health,info,prometheus`（`metrics` 默认关闭），且 Caddy 公网入口对 `/actuator`、`/actuator/*`
统一返回 404。Prometheus 请从 Compose 内网 `http://app:9000/actuator/prometheus` 抓取，或按需在内网反向代理放行，
不要直接暴露给公网。

**已完成的本地生产容器验收（可复现）**：`scripts/verify-production.ps1` 用真实 Dockerfile 从零构建 Java 11 生产镜像，并以独立项目名启动 Compose，逐项验证：

- 非 root（uid 10001）、只读根文件系统、`no-new-privileges`，以及 `tmpfs /tmp:size=64m,exec`。SQLite JDBC 会把原生库解压到临时目录再 `dlopen`，tmpfs 默认 `noexec` 会导致容器启动即崩，Compose 已显式允许执行。
- 健康检查 UP、真实 WebSocket 双人对局与存档、`ADMIN_TOKEN` 透传后运营/治理接口可用。
- 重启经过 Spring 优雅停机（容器 `stop_grace_period` 30s，覆盖 20s 停机窗口），重启后对局列表逐字一致。
- `deploy/backup.ps1` 停写备份 `archive.sqlite` 与 `index.jsonl` 到全新卷，恢复容器的对局列表与复盘棋谱和原卷一致；再用 `deploy/restore.ps1` 把主卷回滚到该备份，校验列表不变且确实生成了保护性 `pre-restore-*.tgz`。

镜像与 Caddy 的 json-file 日志均限制为 20MB × 5 个文件，避免长期运行把磁盘写满。

`scripts/verify-https.ps1` 使用 `docker-compose.https.yml` + Caddy 本地 CA 验证：应用端口不发布到宿主机、HTTPS 首页 200、公网 `/actuator/health` 固定 404、`wss://<host>/ws` 握手成功、经 HTTPS/WSS 完成整局对局；Node 通过 `NODE_EXTRA_CA_CERTS` 信任导出的 Caddy 根证书，未关闭 TLS 校验。

仍未验证、也不能由本地脚本替代的部分：真实公网域名的 ACME 证书签发与 DNS、不同网络真实用户的延迟/重连体验、容量压测、多实例水平扩容。正式上线前必须在实际服务器完成一次同等流程并记录回滚方式。

---

## 🎮 平台能力

- **游戏大厅**：按游戏类型选择、匹配同类型房间、房间列表实时刷新；新增服务端可解释的「下一局玩什么」推荐，优先展示真人正在等待的玩法，其次引导探索未体验或少量体验的玩法，并用最近 7 天真人热度兜底，匿名和已识别游客都可使用
- **房号邀请**：创建**私有房间**（不出现在大厅列表、不参与自动匹配）拿 6 位房号，好友输入房号或直接打开 `/?room=XXXXXX` 自动进房；房号大小写、`ROOM-` 前缀都不敏感
- **单机模式 / 人机对战**：卡片或大厅顶部一键进入，**界面内难度弹层**（简单/中等/困难），20 种玩法都能直接开局（人机局不计 ELO）
- **观战模式**：实时同步棋盘（`GAME_SYNC`），房间内可见观战者昵称列表
- **断线重连**：断线后席位保留 60 秒宽限期，可凭 sessionId 重连；超时未归判负
- **计时系统**：步时 + 总时，超时判负（时限由各游戏引擎定义）
- **对局回放**：终局后可逐步/倍速回放（含提子/翻转效果重建）
- **对局记录持久化**：每局落盘 `data/games/`（可重放摘要日志 + SQLite 全量索引 + 完整棋谱），重启不丢，可随时从大厅列表点开复盘
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
| `GET /api/match/overview` | 按玩法统计可匹配的公开等待房、等待人数与进行中对局（大厅徽标与等待提示） |
| `GET /api/recommendations?limit=3` | 可解释的「下一局」推荐；可选游客 Bearer 身份，匿名也可用。优先级：真人等待 → 未体验/少玩 → 最近 7 天真人热门 |
| `GET /api/announcements` | 大厅可见公告（生效且未过期，公开只读） |
| `GET /api/admin/announcements` | 全部公告及级别/有效期选项（需 `X-Admin-Token`） |
| `POST /api/admin/announcements` | 发布公告（`title/body/level/ttl`，需 `X-Admin-Token`） |
| `POST /api/admin/announcements/{id}` | 上线/下线公告（`{"active":true\|false}`，需 `X-Admin-Token`） |
| `DELETE /api/admin/announcements/{id}` | 删除公告（需 `X-Admin-Token`） |
| `GET /api/events` | 生效中的限时经验加成活动（公开只读，含游戏显示名与服务端时间） |
| `GET /api/admin/events` | 全部活动及比例/时长/玩法选项（需 `X-Admin-Token`） |
| `POST /api/admin/events` | 发布活动（`title/gameType/xpPercent/duration`，需 `X-Admin-Token`） |
| `POST /api/admin/events/{id}` | 暂停/恢复活动（`{"active":true\|false}`，需 `X-Admin-Token`） |
| `DELETE /api/admin/events/{id}` | 删除活动（需 `X-Admin-Token`） |
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
  index.jsonl                        # 每局一行摘要（不含棋谱），可重放的历史日志
  archive.sqlite                     # 全量摘要/参与者索引，长期战绩与成长查询不再受 5000 条大厅窗口限制
  archive.sqlite-wal / -shm          # SQLite 运行期文件；备份时必须与主文件一起取
  20261111-203022-a1b2c3.json        # 单局完整记录（含棋谱，供复盘）
```

摘要与详情分开存：列表/战绩只读摘要索引，不必解析几十 KB 的棋谱；写盘走单线程守护线程，
不阻塞终局广播。单局写入顺序为「详情原子替换 → 摘要日志强制落盘 → 索引事务」；索引失败会保留待写记录并重试，
`/actuator/health` 在失败期间转 DOWN，不会静默丢局。大厅内存仍只缓存最近 5000 条用于实时列表，
成长、成就、累计战绩、赛季与运营窗口改读 SQLite 全量历史。超过 600 手的对局只留元信息、不存棋谱，避免单文件过大。

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
mvn test    # 535 个用例
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
- **账号安全**：PBKDF2 密码与恢复码哈希、一次性恢复码、改密/找回后的会话撤销、恢复失败限流、错误语义防账号枚举与旧哈希透明升级

端到端冒烟（连真实 WebSocket / 真实浏览器，详见 [`docs/smoke/README.md`](docs/smoke/README.md)）：

```bash
# 协议冒烟：覆盖 20 种玩法 + 单机 + 观战 + 回放 + 记录 + 房号邀请 + WS 双路径 + 账号安全
node docs/smoke/smoke-websocket-paths.mjs # Netty 根路径 / 与代理路径 /ws 握手
node docs/smoke/smoke-solo.mjs            # 20 种玩法逐个「建房 + 加电脑」
node docs/smoke/smoke-bomb-cat.mjs        # 炸弹猫 4 人同房、隐藏手牌、行动上限与淘汰
node docs/smoke/smoke-doudizhu.mjs        # 斗地主 3 人叫地主、底牌、出牌与视角裁剪
node docs/smoke/smoke-checkers.mjs        # 西洋跳棋双端对局（强制吃子/连吃）
node docs/smoke/smoke-jungle.mjs          # 斗兽棋双端对局（八兽/河流/陷阱）
node docs/smoke/smoke-flight-chess.mjs    # 飞行棋 4 人对局 + 多人淘汰
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
node docs/smoke/smoke-account.mjs         # 持久账号、身份升级与会话
node docs/smoke/smoke-account-security.mjs# 恢复码、密码找回与会话撤销

# 浏览器冒烟：需先起 headless Chrome（见 docs/smoke/README.md）
node docs/smoke/smoke-ui-record.mjs       # 战绩面板与复盘弹窗
node docs/smoke/smoke-ui-rpg.mjs          # RPG 面板与文字战报
node docs/smoke/smoke-ui-match.mjs        # 大厅等待徽标 + 双真人匹配开局 + 等待房补电脑
node docs/smoke/smoke-ui-announce.mjs     # 公告发布→大厅横幅→关闭记忆→后台下线/删除
node docs/smoke/smoke-ui-rewards.mjs      # 结算浮层本局经验/成就/任务进度
node docs/smoke/smoke-ui-events.mjs       # 活动发布→大厅胶囊→结算加成→暂停后历史不缩水
node docs/smoke/smoke-ui-account.mjs      # 登录/注册恢复与页签状态
node docs/smoke/smoke-ui-account-security.mjs # 恢复码、找回、轮换与移动端层级
node docs/smoke/smoke-ui-jungle.mjs       # 斗兽棋画布点选、规则与移动端
node docs/smoke/smoke-ui-recommendations.mjs # 推荐 REST + 真人等待房 + 真实浏览器
```

---

## 🗺️ 路线图

- ~~**象棋（Xiangqi）**~~：已完成（引擎 + AI + 前端棋子渲染）
- ~~**对局记录持久化 + 战绩页**~~：已完成（`data/games/` + `/api/games` + 大厅战绩面板 + 独立复盘弹窗）
- ~~**非棋牌玩法**~~：已完成回合制对战（`GameEngine` 拆出 `BoardGameEngine` + `MoveCommand` + `RpgBattleEngine` + HTML 面板渲染 + 文字战报复盘）
- ~~**卡牌对战**~~：已完成（23 张卡 / 7 关键词 / 3 预组 + 自定义套牌 / 逐视角隐藏信息）
- ~~**取石子 + 答题对战**~~：已完成（`simultaneousTurn` 同时作答 + 揭晓前信息隔离；答题 42 题 / 6 主题）
- ~~**单机模式 / 人机对战**~~：已完成（20 种玩法一键开局，界面内难度选择）
- ~~**房号邀请**~~：已完成（私有房 + 6 位房号 + `/?room=` 邀请链接）
- ~~**对局记录含套牌**~~：已完成（记录实际生效套牌与自定义配比摘要）
- ~~**对局记录按套牌筛选**~~：已完成（`/api/games?deck=` 先过滤后截断 + 卡牌战绩页筛选器）
- ~~**新增玩法开发指南**~~：已完成（[`docs/ADDING_A_GAME.md`](docs/ADDING_A_GAME.md)，含可运行骨架与常见坑）
- **禁手规则（连珠）**：五子棋目前是自由规则，加禁手需要引擎支持三三/四四/长连判定
- **斗地主 / 掼蛋 / 麻将**：房间已支持按玩法配置 2–8 人；仍需实现牌型/组队/叫牌/吃碰杠胡等专属规则、AI 和前端。
- **实时动作类 / 开放世界 RPG**：需要 20-60Hz 游戏循环、帧同步与客户端预测回滚，且 WS/TCP 队头阻塞会毁掉手感（需换 UDP/WebRTC），与当前回合制架构不同源，不建议并入
- ~~**Docker 化部署 / WSS/TLS**~~：已完成（多阶段构建、非 root、健康检查、持久化卷 + Caddy HTTPS/WSS）
- **游戏内悔棋**：需要双端确认协议


---
