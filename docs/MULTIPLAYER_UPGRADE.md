# 多人房间升级

## 人数与兼容

`GameType.resolvePlayerCount(0)` 使用玩法默认人数，超出玩法范围直接拒绝。`GameRoom` 的目标人数在建房后固定，达到目标人数自动开局；不能中途插入参战玩家。`RoomManager` 只匹配同玩法、同目标人数的公共等待房；房号加入沿用目标房间设置。

旧玩法全部声明为 2–2；新玩法 HIGH_CARD 声明为 2–8，默认 4；UNO 声明为 2–4，默认 2。双人 `GameEngine.configurePlayers` 默认拒绝非 2 人。多人引擎必须实现人数配置、轮次、`forfeit` 淘汰和 `getWinningSides` 结果，房间不能把第一位对手当作多人唯一胜者。

`stone` 为兼容保留的协议字段，现在语义为从 1 起的座位号；0 为观众。每局轮换先手座位，已有双人黑白交换行为保持一致。卡牌的 targetSide 是其自身双人规则，未推广成多人卡牌。

## 协议

JSON JOIN 新增可选 `playerCount` 和 `guestToken`。二进制 JOIN 在旧字段末尾追加 `createRoom` byte 与 `playerCount` byte，旧帧仍可解码。网页对含游客凭证、action 或 END_TURN 的消息使用 JSON，避免丢失扩展字段。二进制 GAME_START 末尾追加可选 sessionId。

ROOM_INFO / GAME_START / GAME_SYNC 的 roomData 与房间列表携带 `minPlayers / maxPlayers / targetPlayers`。TIMER_SYNC 保留 blackTotal/whiteTotal，同时增加按座位顺序的 seatTotals、seatSteps 和待提交座位 awaitingSides。GAME_MOVE 携带同结构的 timers，行动后立即更新时钟。GAME_OVER 附加 winningSides，空列表代表平局；多个座位可共同获胜。旧 winner 字段仅表达唯一胜者，多个胜者通过新字段解释。

房间消息统一排入连接 EventLoop，避免一个连接内联写越过其他线程已经排队的消息，导致新视图被旧视图覆盖。所有可见状态按座位分别裁剪；导师复用同一裁剪入口。

显式离场立即停止接收原房间消息；多人进行中暂留历史席位用于完整存档，终局后释放并回到等待状态，剩余玩家可补人开局。网络断线仍按原宽限期允许重连。

## 身份

游客 token 由服务端签发，随机 256 位；磁盘保存 SHA-256 哈希到稳定 ID 的映射，原 token 仅保存在浏览器。积分按稳定 ID + 游戏分池，新对局参与者额外记录 identityId。`/api/me/stats` 需要游客 Bearer token。旧昵称接口保持为公开历史查询，不为新游客认领旧战绩。账号体系、找回和跨设备迁移仍为后续工作。

## 验证

```powershell
mvn -f gomoku-game/gomoku-game/pom.xml package
node gomoku-game/gomoku-game/docs/smoke/smoke-multiplayer.mjs
```

多人冒烟默认 HTTP 19000 / WS 19001，支持 HTTP_URL / WS_URL 环境变量。它验证 8 个真实 WebSocket 座位、观众裁剪、最后座位重连、五轮整局、并列结果、参与者落盘、身份鉴权及导师默认关闭。

AI 导师通过本地 HTTP 模型模拟测试验证消息格式、只发送当前视角以及不发送凭证；不依赖付费模型。生产模型质量、真实外网 TLS 和服务器容量需在实际部署环境验证。

2026-11-14 本轮验证：238 项单元测试零失败；8 人真实 WebSocket 与本地 Caddy HTTPS/WSS 五轮整局通过；原有 9 种单机开局、卡牌整局、房号邀请通过；PowerShell 与 Linux 启动脚本配置校验通过。容器使用本机缓存 Java 21 运行时挂载本轮 jar 验证，生产 Dockerfile 的 Java 11 基础镜像下载未完成，因此尚未验证该 Dockerfile 的完整构建与公网 ACME 证书。

2026-11-15 补测：再次通过 238 项测试与 WSS 整局；连续 7 个 ADD_AI 请求可填满 8 人房，避免 UI 定时器被合并后触发限流而卡在 7/8。

## 2026-11-15 会话与房间生命周期修复

- 重连成功清除旧断线 TTL，避免在线玩家被误清理；替换连接后旧连接失效，已绑定其他身份的连接不能覆盖另一会话。
- 观众收到独立重连凭证，断线保留到 TTL；重复观战保持单条记录，观众转参战后移除旧观众记录。参战者不能同时观战自己的房间。
- 切换房间使用独立 Player 对象，旧局历史席位保持原值；最后一名真人离开后停止剩余 AI 与计时任务并回收空房间。
- GAME_SYNC 增加权威 `moveCount / moveSeq / timers`，终局额外包含原 `gameOver` 消息，恢复计分、结算与再开局入口。围棋停一手和提子不再靠盘面棋子数量推算手数。旧客户端可忽略新字段。
- 重连会话使用 32 位随机十六进制串，旧凭证在当前服务进程内仍可恢复；二进制 RESTART_REQUEST 在末尾追加可选 playerId，解决重名时无法确认再开局的问题。
- 244 项测试零失败；真实 WebSocket 生命周期、8 人整局、卡牌观战、房号邀请通过；390px 手机浏览器终局断线重连恢复通过，未发现横向溢出。

复测命令：`node gomoku-game/gomoku-game/docs/smoke/smoke-room-lifecycle.mjs`。未重新验证生产 Java 11 镜像与公网证书。

## 2026-11-15 同时行动与计时修复

- `GameEngine.isAwaitingAction(side)` 公开当前待提交座位，`getActionRound()` 标识同时行动轮次。比大小和答题为所有待提交真人扣除独立步时与总时；提交者停表，整轮推进才重置步时，总时保持累计。AI 按各自待提交状态调度，无需等待真人或兼容 currentTurn。
- 房间每局保持单个秒钟节拍，行动不会重排下一次扣时。卡牌出牌与攻击不会重置回合时限；合法结束回合才重置。多人同一秒超时全部处理，所有剩余玩家同时超时为平局；单人淘汰不会延长仍待提交玩家的时限。
- 答题支持任一座位先答，已提交者无法重复作答。进入新题时 `revealed=false` 且隐藏 `correctIndex`，上一题答案与双方选择分别保留在 `lastCorrect / lastChoices`，修复第二题选项被锁住的问题。
- 页面显示各座位步时与提交状态，行动后的 timers 立即更新；观战提示显示同时提交语义。缓存版本 37。
- 验证：251 项测试零失败，Maven package 通过；真实 WS 独立计时、8 人五轮整局、7 AI 独立提交、答题八题整局、卡牌整局、原 9 种人机开局及会话重连通过。390px / 320px 浏览器实测第二题四选项可用、上一题结果可见、无横向溢出、无页面错误。

新增复测命令：`node gomoku-game/gomoku-game/docs/smoke/smoke-action-clocks.mjs`。本轮未重新验证生产镜像与公网证书。

## 2026-11-15 操作版本与同时答题入口

- 修复 `sendAction` 遗留的单人轮次判断：答题按钮按本人是否已提交发送，后手无需等先手。非棋盘操作不再提前自增服务端权威序号。
- 房间数据与 GAME_MOVE 增加公开 `actionVersion`，由房间号、对局次数和行动阶段组成。同时行动只绑定整轮，其他座位先提交不会使当前玩家请求失效；顺序行动绑定 moveSeq，防止旧手牌下标、旧攻击或旧结束回合在新局面生效。重开后即使序号回到零，旧操作仍会失效。
- 网页 MOVE / PASS / END_TURN 自动携带版本，使用 JSON 保留字段；服务端在房间写锁内校验版本与参战资格。未生效操作返回当前连接所属玩家裁剪后的 GAME_SYNC，恢复版本、手数与计时，页面不需要刷新。观众仍为公开视角。
- 保留未携带版本的旧 JSON/二进制协议兼容；旧客户端仍依赖原有规则与棋盘序号校验，不具备新增过期操作保护。actionVersion 为公开并发校验值，不是身份凭证。
- 新增 ActionVersionTest 8 项与 smoke-action-version.mjs。261 项测试零失败，Maven package 通过；真实 WS 过期操作、重连、独立计时、8 人整局、答题八题、卡牌整局及旧序号错位恢复通过。修复旧序号冒烟重复处理 WAITING 而重复添加 AI 的脚本问题。
- 320px 手机浏览器实测座位 2 在 currentTurn=1 时先答并进入第二题，四选项仍可用，无横向溢出。缓存版本 38。本轮未重新验证生产镜像与公网证书。

## 2026-11-15 持续验证入口

- `scripts/verify-project.ps1` 统一运行 261 项 Java 测试、所有冒烟脚本语法检查、静态缓存版本检查和两套 Compose 配置校验。
- `.github/workflows/ci.yml` 在 `master` push 与 Pull Request 上使用 Java 11 和 Node 20 执行轻量验证；不会在 CI 中强制构建生产镜像或申请公网证书。

