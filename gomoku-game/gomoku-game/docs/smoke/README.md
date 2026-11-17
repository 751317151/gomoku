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
| `smoke-friends.mjs` | 好友系统 REST + WS 真实链路 | 好友码签发稳定（8 位去混淆编码）；按好友码（大小写不敏感）与用户名发申请；双方 incoming/outgoing 可见；同意/拒绝/撤回全流程；删除双向移除；不存在 404、加自己 400、重复申请 409、未登录 401；**WS 在线状态**：好友建房（私有房）对好友可见且 `joinable`、不在公共列表、一键加入并开局、对端转 PLAYING 不可加入、断线转离线；**实时推送**：`HALL_BIND` 大厅绑定即在线（无房间）、好友上线/下线即时 `FRIEND_EVENT`（陌生人上线不推）、REST 申请与通过即时 `request`/`accepted` 推送；**好友私聊**：好友在线双向可达、1 秒限流只送首条、超长截断 200 字、非好友被拒且不送达、离线好友明确报错。支持 `API_BASE` / `WS_URL` |
| `smoke-checkin.mjs` | 签到留存 REST | 初始未签/预期连续 1；首次签到 +15；重复签到不发奖且经验不翻倍；签到经验计入 `/api/me/progress` 的 xp；**签到不虚增对局数**；未登录签到/查状态 401。支持 `API_BASE` |
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
node docs/smoke/smoke-rpg.mjs
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
