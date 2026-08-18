# 04 — 全链路回归、Compose 修复和基本验收

**Parent:** GitHub #78

**GitHub:** [#82](https://github.com/Leewwp/health_agent/issues/82)

**What to build:** 从新匿名会话开始，餐食、健身、作息、无关对话、风险和跨领域切换都能稳定演示；Compose 配置可以通过静态校验。该票只收口验证和必要的部署边界，不扩展新的业务能力。

**Blocked by:** #79 / 01 — 健身最小推荐闭环与意图安全基础；#80 / 02 — 餐食推荐、调整和跨品类切换闭环；#81 / 03 — 作息事实和计划入口最小闭环。

**Status:** resolved

- [x] 修复 Compose `depends_on` YAML 结构，`docker compose config --quiet` 成功，且不改变现有 Qdrant 可选降级语义。
- [x] 新匿名会话完成“帮我推荐一下”“中午吃什么”“适合新手的轻量训练”“胸肌/大腿/臀部”“换一批”“停止喝咖啡”“推荐电影”和餐食/健身互切脚本。
- [x] 所有响应的 `displayBlocks` 类型与 `domain` 一致；风险、旧饮食接口、计划、反馈和资源读取边界不回归。
- [x] 执行编译、聚焦测试、完整测试、可用时的 MySQL 门控，以及一次真实模型和浏览器冒烟；模型文案不做易变的精确断言。
- [x] 验收证据记录用户输入、响应阶段、领域、资源类型和环境门控结果；不记录真实 key、完整 prompt 或敏感用户数据。

## Answer

确定性与集成收口已完成：`docker compose config --quiet`、656 个完整测试（619 通过 + 37 环境门控跳过）以及 MySQL 门控 653 通过（仅 3 个 Qdrant 门控跳过）均通过。

2026-08-18 使用当前代码、AgentScope 模式与同源 Nginx 在 `http://127.0.0.1:18090/#/chat` 完成 Chromium 降级链路复验：健身请求澄清后输入“胸肌”只返回 3 张 EXERCISE 卡；同会话切换“中午吃什么”并回答“清淡点”只返回 2 张审核 MEAL 卡；咖啡因问题直接返回带权威来源的 ROUTINE 事实；“推荐一部电影”返回 `OTHER + CHAT` 且无资源卡。领域、任务、阶段和资源类型全部一致，未记录凭证或完整提示词。

但退出日志确认 `qwen3.7-flash` 请求均超时，餐食 Embedding 也返回 404，以上浏览器结果来自确定性降级而不是成功的 live model 响应。因此本票曾恢复为 `ready-for-human`，等待可用 DashScope 配置补一次成功 live smoke。

2026-08-18 最终 live 验收已通过。修复 `.env` 属性文件中的 Agent/Embedding 超时变量未被 `application.yml` 消费的问题，并补齐“这周/本周的健身计划”PLAN 表达；真实浏览器同时发现“新会话”只清空前端 ID、后端仍恢复匿名默认会话并继承旧风险信号，现改为每次显式生成新的 `sess_web_*` 会话 ID。Chromium 在 `http://127.0.0.1:18091/#/chat` 验证 PLAN 入口、健身两轮、健身→餐食、作息、OTHER 与风险阻断；PLAN 点击进入 `#/plans` 后仍显示无计划，证明聊天不创建草稿。

本轮 8 条 Trace 全为 SUCCESS：IntentAgent=`qwen3.7-flash`、RecommendResponseAgent 请求配置名=`qwen-turbo`，Agent 调用耗时 10.5–21.8 秒；意图 `degraded=false/fallbackReason=null`，三次响应 Agent `fallbackReason=null`，整批无 timeout/error。Embedding 不再出现 `embedding_unavailable`；因验收启动参数关闭向量索引，餐食检索仅按设计记录 `no_vector_hits` 后走 STRUCTURED。本票可关闭。

后续开发前审计发现旧 `AgentScopeInvoker` 以历史名称 `qwen-max` 判断是否选择主模型 Bean，因此上述 Trace 能证明真实 Agent 调用成功，但不能证明 `RecommendResponseAgent` 底层实际使用了主模型 Bean。现已改为显式 `MAIN/LIGHT` 职责路由并增加回归测试；最新门控为普通全量 658（621 通过 + 37 环境门控跳过）、真实 MySQL 门控 658（655 通过 + 3 个 Qdrant 门控跳过）、Compose 静态解析通过。修复后的主模型 live 延迟证据由 #84 重新采集，不回写夸大本轮历史证据。
