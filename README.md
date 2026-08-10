# health_agent

基于 Spring Boot、AgentScope/DashScope、MyBatis 和 MySQL 的饮食推荐 Agent。当前已提交代码仍是饮食主流程；健康 Agent 扩展已完成规格和实施票据，本次文档提交不包含开发实现。

## 当前能力

- 会话状态机：意图识别、槽位合并、澄清、餐食检索/排序、应答、风险提示和持久化；
- 多角色 Agent：`IntentAgent`、`ClarifyAgent`、`RecommendResponseAgent`、`PlanResponseAgent` 和离线评估 Judge；
- 结构化 JSON 输出解析、关键词/模板降级、请求 Trace、反馈和餐食数据管理；
- vanilla JS 单页前端，无 Node/npm 构建步骤。

这里的“多 Agent”指由 Java 状态机确定性编排的多角色工作流，不是多个自治 Agent 自主规划或调用工具。候选、数值、风险和状态转换由 Java 控制，LLM 负责语义理解和受约束表达。

## 本地启动

环境要求：Java 21、Maven、MySQL 8。

1. 启动本机 MySQL，并使用 `root/123456` 连接。
2. 导入 `src/main/resources/db/diet_db.sql`。`createDatabaseIfNotExist=true` 只会创建空数据库，不会创建表和种子数据。
3. 在 `src/main/resources/application.yml` 配置 DashScope key，或使用 Spring 配置覆盖。
4. 编译并启动：

```bash
mvn -DskipTests compile
mvn spring-boot:run
```

服务默认运行在 `http://localhost:8080`。接口请求可携带 `X-User-Id`，未提供时旧饮食 Controller 默认使用用户 `1`。

## 健康 Agent 规划

- [正式规格](.scratch/health-agent/spec.md)
- [实施计划](docs/health-agent-implementation-plan.md)
- [Agent MVP 面试适用性复核](docs/agent-mvp-suitability-review.md)
- [实施票据地图](.scratch/health-agent/map.md)

规划采用两级门槛：31-32 号先交付可测试、可降级、可追踪的三品类 Agent 垂直闭环；33-36 号再完成审核资源、餐食 RAG、健康档案、周计划、前端和部署证据。31 号目前存在未提交且未运行验收的本地代码草稿，后续开发需要先独立审查，不能视为已完成能力。

## 边界

这是面试展示项目，不包含医疗诊断、真实账号体系、生产级安全平台、多实例锁、消息队列或长期行为追踪。完整 MVP 中 Agent 仍只负责意图理解和解释；候选召回、数值计算、计划规则和风险结论由 Java 确定性逻辑控制。
