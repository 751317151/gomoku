# 端到端冒烟脚本（真实 WebSocket 对局）

这些脚本直接连 Netty WebSocket 打真实对局，用于验证协议、规则与结算，不依赖浏览器。
相比手工点页面，它们能在 CI/命令行里跑，且断言可自证。

## 前置

服务已启动（端口按需覆盖）：

```bash
cd gomoku-game/gomoku-game
java -jar target/gomoku-game-1.0-SNAPSHOT.jar --server.port=19000 --netty.port=19001
```

## 脚本

| 脚本 | 验证内容 | 断言要点 |
|------|---------|---------|
| `smoke-account.mjs` | 账号 REST 真实链路 | 游客身份签发；带游客 token 注册后**身份不变**（战绩无损升级）；当前账号反查；旧凭证仍识别；重名 409、错密码 401；登录回同一身份并签发新 token；改密后旧密码失效新密码可用；游客访问账号接口 401。支持 `API_BASE` |
| `smoke-ops.mjs` | 运营数据看板 | 无令牌/错令牌被拒；看板结构完整（实时在线、7 天窗口、趋势补零、留存字段、口径说明）；**人机局不增加活跃对局与今日活跃**；真人按真实协议打完一局四子棋后窗口对局数增长；玩法热度含占比、时段 8 段。需服务端 `ADMIN_TOKEN` |
| `smoke-moderation.mjs` | 运营治理真实链路 | 无令牌访问管理接口被拒；同房间举报收到确认、重复举报去重、举报自己被拒；管理端举报带被举报人/原因/房间证据与时长选项；禁言后聊天被拦截但仍可对局；封禁后重连被拒绝；撤销恢复自由；已处置举报不再待处理；玩家可查自己的举报且视图不泄露对方身份 ID。需服务端 `ADMIN_TOKEN` 与脚本一致（默认 `smoke-admin-token`） |
| `smoke-friends.mjs` | 好友系统 REST + WS 真实链路 | 好友码签发稳定（8 位去混淆编码）；按好友码（大小写不敏感）与用户名发申请；双方 incoming/outgoing 可见；同意/拒绝/撤回全流程；删除双向移除；不存在 404、加自己 400、重复申请 409、未登录 401；**WS 在线状态**：好友建房（私有房）对好友可见且 `joinable`、不在公共列表、一键加入并开局、对端转 PLAYING 不可加入、断线转离线；**实时推送**：`HALL_BIND` 大厅绑定即在线（无房间）、好友上线/下线即时 `FRIEND_EVENT`（陌生人上线不推）、REST 申请与通过即时 `request`/`accepted` 推送；**好友私聊**：好友在线双向可达、1 秒限流只送首条、超长截断 200 字、非好友被拒且不送达；**离线留言**：目标离线时提示已保存、对方上线即收到、只投递一次、重复上线不重复投递。支持 `API_BASE` / `WS_URL` |
| `smoke-party.mjs` | 好友组队（真实 WS + REST） | 未登录 401；只能邀请好友；**好友离线时拒绝组队**；邀请自动建队且邀请人为队长、队伍短码 `PTY-XXXX`；被邀请人实时收到邀请并接受、队伍变 2 人；队员不能邀请；**队长一次 `/api/party/start` 后全队同时收到 `GAME_START` 并进入同一间私房**（客户端不再各自 JOIN）；座位带同一队伍标识；组队房间不进公共列表；人数超玩法上限时拒绝且**无人被塞进房间**；3 人队伍可直接开 2–8 人玩法且容量等于队伍人数；退出/移交/解散。支持 `API_BASE` / `WS_URL` |
| `smoke-ui-party.mjs` | 组队 UI（真实浏览器，两个隔离上下文） | 好友邀请在面板出现 → 点「加入」→ 双方面板 2/8 且队员在线 → 队长点「带全队开局」→ 全队同房开局 → 双方游戏区域实际可见且大厅隐藏 → 双方座位显示同一队伍短码；收集并断言双方页面无运行时异常或 console.error。需先起 Chrome（`CDP_PORT` 默认 9335）；支持 `APP_URL` / `API_BASE` / `WS_URL` / `OUT_DIR` |
| `smoke-checkin.mjs` | 签到留存 REST | 初始未签/预期连续 1；首次签到 +15；重复签到不发奖且经验不翻倍；签到经验计入 `/api/me/progress` 的 xp；**签到不虚增对局数**；未登录签到/查状态 401。支持 `API_BASE` |
| `smoke-daily-quests.mjs` | 每日任务真实链路（WS + REST） | 未登录查询/领取 401；新身份 5 项任务全不可领，领未完成 400、未知任务 404；用「建房 + 加电脑 + 认输」打 3 局 → 三局任务可领、全负时胜场任务不可领；领取 +30 XP、重复领取 409、`/api/me/progress` 经验 +30 且对局数不变；再打一局四子棋 → 多面玩家 +35、累计任务经验 65；3 人任务仍 400；刷新后已领状态与累计经验保持。支持 `REST_URL` / `WS_URL` |
| `smoke-ui-quests.mjs` | 每日任务 UI（真实浏览器） | 预置 3 局后进入大厅：5 张任务卡、三局任务显示「领取」、未完成显示进度；DOM 点击领取 → 文案变「已领取」、顶部经验刷新；REST 校验服务端 claimed 与 bonusXp=30；刷新后仍保持。需先起 Chrome（`CDP_PORT` 默认 9336）；**刷新判据用 `performance.timeOrigin` 变化**（`Page.reload` 立即返回，旧文档仍在）。支持 `APP_URL` / `API_BASE` / `WS_URL` / `OUT_DIR` |
| `smoke-ui-rewards.mjs` | 结算页成长反馈（真实浏览器） | 全新游客在页面内开一局人机并认输：终局浮层出现「本局 +45 经验」「解锁成就 初出茅庐」「每日任务 1/3 局」徽标，三局未满时不显示「可领取」；另断言布局几何（横向排列、宽>高、高<40、文字不溢出自身、徽标互不重叠）；服务端经验 45、对局 1、成就已解锁、今日任务 1 局与页面一致。需先起 Chrome（`CDP_PORT` 默认 9337）。支持 `APP_URL` / `REST_URL` / `WS_URL` / `OUT_DIR` |
| `smoke-ui-achievements.mjs` | 成就墙 UI（真实浏览器） | 全新游客 26 项成就全锁：侧栏预览 6 项、DOM 点击「查看成就墙」打开弹窗、26 张卡片、副标题 0/26、已解锁筛选空态、未解锁 26 张、多列布局；打一局人机并认输后刷新：初出茅庐已解锁、五子棋·首胜仍 0/1、筛选数量与 `/api/me/progress` 一致、全模式制霸未解锁。需先起 Chrome（`CDP_PORT` 默认 9338）。支持 `APP_URL` / `REST_URL` / `WS_URL` / `OUT_DIR` |
| `smoke-ui-match.mjs` | 匹配体验 UI（真实浏览器 + 真实 WS 对手） | 干净总览；对手建公开等待房后玩法卡出现「1 人等待」徽标、无等待玩法不显示；点快速匹配后双方进同一房间直接开局、助手条隐藏、总览转为 1 桌进行中；换 UNO 无人可配时等待助手条出现、时长递增、提示暂无别人、含补电脑/邀请/返回三动作；点「补齐电脑开局」后四座坐满、三个电脑入座、UNO 总览转为进行中。需先起 Chrome（`CDP_PORT` 默认 9340）。支持 `APP_URL` / `REST_URL` / `WS_URL` / `OUT_DIR` |
| `smoke-ui-announce.mjs` | 运营公告端到端（真实浏览器 + 管理接口） | 管理接口无/错令牌被拒、公开接口可读；发布「重要」「维护」公告后大厅按新旧显示两条、级别标签与左色条正确、布局不溢出；关闭一条后立即少一条、服务端其余玩家不受影响、刷新后按本机关闭记录仍隐藏；管理后台发布第三条后大厅即时出现；后台下线后大厅与服务端同时消失、记录保留；删除后彻底消失。需服务端 `ADMIN_TOKEN`（默认与脚本一致 `smoke-admin-token`）与 Chrome（`CDP_PORT` 默认 9341）。支持 `APP_URL` / `REST_URL` / `WS_URL` / `OUT_DIR` |
| `smoke-ui-events.mjs` | 限时活动加成端到端（真实浏览器 + 管理接口） | 管理接口无/错令牌被拒、公开接口只列生效活动；后台发布「五子棋 +50%」后大厅金色胶囊出现、含玩法与剩余时间且布局正常；人机局认输结算显示「本局 +68 经验」与活动徽标、服务端经验与记录中的活动字段一致；后台暂停后公开列表与胶囊消失、行标「已暂停」，再打一局只 +20 经验且新记录不带活动字段（历史经验不缩水）；删除后彻底消失。需服务端 `ADMIN_TOKEN`（默认与脚本一致 `smoke-admin-token`）与 Chrome（`CDP_PORT` 默认 9342）；脚本会先清理自己上次留下的「冒烟·」活动，不会动真实数据 |
| `smoke-season.mjs` | 赛季段位 REST | 匿名可查赛季；赛季 id/label/剩余天数合理；匿名 me 为 0 分青铜；带身份 me 含段位与进度；榜单每行含 points 与 tier。支持 `API_BASE` |
| `smoke-ui-checkin.mjs` | 签到 UI（真实浏览器） | 需先起 Chrome（`CDP_PORT` 默认 9334）与 `WS_URL`（默认 `ws://127.0.0.1:19001`）。断言：签到卡片渲染、点击签到、按钮变「已签到」、卡片 signed、经验刷新、刷新后仍已签 |
| `smoke-ui-account.mjs` | 账号三态 UI（真实浏览器） | 需先起 Chrome（见下，`CDP_PORT` 默认 9333）。断言：游客/登录/注册三态切换、注册后显示已登录且**切回游客态**（防重复注册 409 卡死）、刷新恢复登录态与昵称、退出清空凭证、重新登录成功 |
| `smoke-action-version.mjs` | 操作版本与过期请求恢复 | 旧题不能提交到新题；过期请求返回本人视角同步；重连恢复版本；卡牌重复点击不能消耗下一张牌，旧结束回合不能跳过下一回合；支持 `WS_URL` |
| `smoke-action-clocks.mjs` | 同时行动独立计时 | 所有待提交座位扣时、已提交者停表、提交不延长他人时限、整轮结束才重置步时；答题后手先答、第二题可继续回答、上题结果与新题答案隔离；支持 `WS_URL` |
| `smoke-room-lifecycle.mjs` | 观战与玩家重连 | 观众会话恢复且不重复占位、权威手数/序号/计时、终局结果恢复；支持 `WS_URL` |
| `smoke-multiplayer.mjs` | 8 人比大小整局 | 真实 8 席位、观众信息裁剪、第 8 席重连、五轮并列结算、8 人存档、游客鉴权；支持 `HTTP_URL` / `WS_URL`，可验证 WSS 代理 |
| `smoke-websocket-paths.mjs` | WebSocket 双路径握手 | 默认同时验证 Netty 根路径 `/` 与反向代理路径 `/ws`；设置 `WS_URL=wss://host/ws`、`WS_PATHS=/ws` 可只验证线上 WSS |
| `smoke-connect4.mjs` | 四子棋人机整局 | 每手落点按重力实时计算；校验无「位置冲突」「重力违规」；终局有 `winLine`（4 点）、`scoreSummary`、完整 `moveHistory`；AI 局 `eloChange` 必须为 0 |
| `smoke-seq.mjs` | 落子序号错位自愈 | 故意发 `moveSeq=99` → 服务端拒绝并回传真实手数 → 校准后重试成功、可继续走到终局 |
| `smoke-card-custom-deck.mjs` | 自定义套牌 | 合法 30 张配比开局后**起手只会出现所选卡牌**；总数不足 / 单卡超限 / 含衍生物三种非法输入都**安全回落为预设套牌**而不是开不了局 |
| `smoke-card-deck.mjs` | 套牌预组 | 三套套牌（均衡/快攻/控制）都能生效并在 view 里正确暴露；起手后「牌库剩余 + 手牌数 = 30」，证明所选套牌真的被拿去建库（曲线差异属静态配比，由 `DeckPresetTest` 用 100 个种子确定性断言，不在此做小样本统计） |
| `smoke-card-shield.mjs` | 圣盾 / 亡语召唤 | 场上每个随从都带 `divineShield` 字段；甲用「圣盾拉满」的自定义套牌（12/30 圣盾随从），**最多打 4 局、见到圣盾就停**（快攻可能 5 回合结束，单局不一定能打出 4 费随从）；破盾与亡语衍生物作为尽力观察项 |
| `smoke-spectator.mjs` | 观战（卡牌） | 观战者收到 `GAME_SYNC`；看到双方血量/法力/场面/**手牌数量**，但**看不到任何手牌内容**；玩家出牌后数量同步更新；观战者发落子被拒 |
| `smoke-solo.mjs` | **单机模式**：9 个玩法逐个「建房 + 加电脑」 | 每个玩法都能在无人匹配的情况下直接开局（`PLAYING` + 分配棋色）；非棋盘玩法开局即下发 `view`；人机对局不写 ELO。**这条覆盖过一个致命缺陷：单人时「添加电脑」按钮从不渲染，导致所有玩法都玩不了** |
| `smoke-card-target.mjs` | 选目标法术校验 | 手牌都带 `needsTarget`；**缺目标被拒且不扣法力/手牌**；火球指定己方目标被拒；带合法目标出牌成功并造成 4 点伤害 |
| `smoke-card-keywords.mjs` | 卡牌关键词视图校验 | 手牌都带 `desc`；场上每个随从都带 `taunt/charge/battlecry/deathrattle/canAttack` 五个布尔字段；多轮对局能抽到战吼/亡语卡（狂战士/骨龙） |
| `smoke-nim-quiz.mjs` | 取石子 + 答题双端对局 | 取石子：初始 5/7/9、取子后该堆减少、超量被拒、走到终局带棋谱；答题：双方同题、**揭晓前不下发 `correctIndex`**、**揭晓前看不到对手 `choice`**、两人答完才揭晓、分数结算、答满 8 题结束（题库 42 题/6 主题，见 `QuizQuestionBankTest`） |
| `smoke-card.mjs` | 卡牌对战双端对局（含隐藏信息） | 双方分配先后手；**己方能看见手牌、对手 `hand` 字段为 undefined（只给 `handCount`）**；起手必有可打牌；出牌推进；`action:-2` 手动攻击使敌方英雄掉血；`END_TURN` 交出行动权；整局在硬上限内收敛；终局数据不含 `"hand"` |
| `smoke-xiangqi-pvp.mjs` | 象棋 PvP 双端对局 | 两个连接走 `fromRow/fromCol` 协议；`scores[].eloChange` 非全零且双方互为相反数 |
| `smoke-record.mjs` | 对局记录持久化 + 战绩查询 | 打完一局后：记录进 `/api/games` 索引、`ranked=true`、详情含与手数等长的棋谱与 `moveEffects`、ELO 双方和为 0、`/api/stats/player/{name}` 战绩正确、非法 ID 返回非 200（目录穿越防护） |
| `smoke-record-deck.mjs` | 卡牌对局记录的套牌信息与筛选 | 记录里必须写**引擎实际生效**的套牌：自定义套牌带中文标签与配比摘要（含新卡）、预组不写摘要；AI 的套牌同样入档；`/api/games` 列表与 `/api/games/{id}` 详情都要带；`/api/games?deck=` 只返回至少一方实际使用该套牌的对局，且**先过滤后截断**（`limit=1` 仍能命中较旧匹配记录） |
| `smoke-room-invite.mjs` | 房号邀请 | 「创建房间」必须新建独立房间（两次建房房号不同）；好友用**小写/无前缀**房号能加入并开局；不存在的房号必须失败且**不回落**到自动匹配；已开局房间不能再加入 |
| `smoke-ui-record.mjs` | 战绩面板、套牌筛选与复盘弹窗（真实浏览器） | 需先起 Chrome 调试实例（见下）。断言：进入大厅、战绩面板有值；套牌筛选器仅在卡牌页签显示，切换页签后隐藏并复位；选择套牌后请求携带 `deck` 参数，列表行数与接口结果一致；复盘弹窗能打开、滑块能改步数、无控制台错误 |

```bash
node docs/smoke/smoke-websocket-paths.mjs
node docs/smoke/smoke-connect4.mjs
node docs/smoke/smoke-seq.mjs
node docs/smoke/smoke-card-custom-deck.mjs
node docs/smoke/smoke-card-deck.mjs
node docs/smoke/smoke-card-shield.mjs
node docs/smoke/smoke-spectator.mjs
node docs/smoke/smoke-solo.mjs
node docs/smoke/smoke-card-target.mjs
node docs/smoke/smoke-card-keywords.mjs
node docs/smoke/smoke-nim-quiz.mjs
node docs/smoke/smoke-card.mjs
node docs/smoke/smoke-xiangqi-pvp.mjs
node docs/smoke/smoke-record.mjs
node docs/smoke/smoke-record-deck.mjs
node docs/smoke/smoke-room-invite.mjs
node docs/smoke/smoke-party.mjs
node docs/smoke/smoke-rpg.mjs
node docs/smoke/smoke-daily-quests.mjs
node docs/smoke/smoke-ui-quests.mjs
node docs/smoke/smoke-ui-rewards.mjs
node docs/smoke/smoke-ui-achievements.mjs
node docs/smoke/smoke-ui-match.mjs
node docs/smoke/smoke-ui-announce.mjs
node docs/smoke/smoke-ui-events.mjs
```

## UI 脚本的前置（Chrome 调试实例）

`smoke-ui-record.mjs` 通过 CDP 驱动真实页面，需要先单独起一个 headless Chrome：

```bash
"C:/Program Files/Google/Chrome/Application/chrome.exe" \
  --headless=new --disable-gpu --remote-debugging-port=9222 \
  --user-data-dir="F:/tmp/chrome-prof-rec" --no-first-run about:blank

# 然后
node docs/smoke/smoke-ui-record.mjs
# 截图默认落在 F:/tmp/shot/，可用 OUT_DIR 覆盖
```

`smoke-ui-party.mjs` 用 9335 端口（`CDP_PORT` 可覆盖），在同一个 Chrome 里开两个隔离 browser context 分别扮演队长与队员；每个 context 有独立 localStorage，才能同时持有两个游客身份。`smoke-ui-quests.mjs` 用 9336、`smoke-ui-rewards.mjs` 用 9337、`smoke-ui-achievements.mjs` 用 9338、`smoke-ui-match.mjs` 用 9340、`smoke-ui-announce.mjs` 用 9341、`smoke-ui-events.mjs` 用 9342，避免与其它 UI 脚本抢同一个调试实例。公告/活动冒烟需要服务端以 `ADMIN_TOKEN=smoke-admin-token` 启动（或在脚本侧用同名环境变量覆盖），脚本开始时会清理自己上次留下的「冒烟·」公告或活动，不会动真实数据。

两个容易踩的点：

- **页面默认按 `data-port="9001"` 拼 WS 地址**。若服务用 `--netty.port=19001` 起，
  必须在脚本里显式设置 `#ws-url`，否则页面停在登录页、后面全部断言失败。
- **页面大量使用 `alert`**，原生 alert 会阻塞 JS 并把后续 CDP 调用拖到超时。
  脚本用 `Page.addScriptToEvaluateOnNewDocument` 把它替换成日志收集。

端口不同时用环境变量覆盖：`WS_URL=ws://127.0.0.1:9001 node docs/smoke/smoke-connect4.mjs`。
验证 HTTPS 入口时可用：`WS_URL=wss://game.example.com/ws WS_PATHS=/ws node docs/smoke/smoke-websocket-paths.mjs`。

## 为什么强调「按重力实时计算落点」

早期版本的脚本用固定坐标落子，第 2/3 子被服务端正确拒绝，看起来像引擎 bug，实际是脚本没有
按 `dropRow` 实时算落点。**四子棋任何落子都必须落在该列最低空位**，脚本里务必先算 `dropRow`。

## 已知脚本约束（非产品缺陷）

- `smoke-xiangqi-pvp.mjs` 用固定着法计划（`PLAN_RED` / `PLAN_BLACK`）。若改动了象棋初始局面，
  计划里的起点可能不再有子，会出现 `无效落子` —— 需同步更新计划表。
- 每个脚本自带超时兜底，超时会打印 `FAIL` 并以非 0 退出。
- UI 脚本不能用固定 sleep 等页面脚本加载（Chrome 冷启动会慢于 1.5s），应轮询 `typeof joinGame === 'function'` 这类就绪信号。
- `Page.reload` 会立即返回，旧文档仍满足就绪信号。凡刷新后继续断言的脚本，必须记录刷新前的
  `performance.timeOrigin` 并等待其变化，否则会在新旧文档之间竞态（smoke-ui-checkin / smoke-ui-quests 已按此修复）。
- `JOIN` 后服务端会连发两条 `WAITING`（房间一条、带 sessionId 一条，重连需要）。脚本里的 `ADD_AI`、
  开局类动作必须幂等，否则第二条 `WAITING` 会重复触发并在开局后被拒。
- 静态资源带 `?v=` 且 Service Worker 对 CSS/JS 走 stale-while-revalidate。改完样式必须同时提升
  `index.html` 与 `sw.js` 的版本号并更新 `CACHE_NAME`，否则复用同一浏览器配置的 UI 冒烟会先拿到旧样式；
  布局类断言必须量几何（宽高、`scrollWidth` 溢出、矩形重叠），只比对文字会在排版坏掉时假绿。
