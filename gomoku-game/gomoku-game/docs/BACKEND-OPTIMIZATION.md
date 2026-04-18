# 后端优化文档 v3

## 已完成

| 优化项 | 实施版本 | 说明 |
|--------|----------|------|
| ChannelGroup 广播 | v1 | 用 Netty ChannelGroup 替代手动遍历 |
| 僵尸房间清理 | v1 | 定时任务每 60s 扫描，5 分钟无活动的空房间自动清理 |
| 消息幂等 (moveSeq) | v2 | GameMessage 新增 moveSeq，服务端验证序列号连续性 |
| 审计日志 | v2 | 所有关键操作输出 [AUDIT] 前缀的结构化日志 |
| IP 连接限制 | v2 | 单 IP 最大 5 连接，超限拒绝 |
| AI 动态深度 | v2 | 候选数 >40→depth=2, 20-40→depth=4, <20→depth=6 |

## 本轮实施

| # | 优化项 | 说明 | 收益 |
|---|--------|------|------|
| 1 | Spring Actuator 监控 | pom.xml 加 actuator，RoomManager 暴露自定义指标 | 运行时可观测 |
| 2 | Handler Registry | GameServerHandler 的 switch-case 改为 Map<Type, BiConsumer> | 可扩展性 |
| 3 | ReadWriteLock | GameRoom 的 synchronized 改为 ReentrantReadWriteLock | 读操作并发度提升 |
| 4 | 优雅停机 | @PreDestroy 广播 SHUTDOWN 消息给所有客户端 | 用户体验 |
| 5 | 状态方法重构 | 提取 canMove/canRestart/canSurrender 语义方法 | 可读性 |

## 未来规划（大改动，暂不实施）

| 优化项 | 说明 | 原因 |
|--------|------|------|
| Protobuf 协议 | 自定义二进制协议替代 JSON | 需重写整个消息层，改动量巨大 |
| Netty Recycler 对象池 | 池化 GameMessage/Frame | Recycler 已逐步内部化，外部使用不推荐 |
