# health_agent

基于 Spring Boot、AgentScope/DashScope、MyBatis 和 MySQL 的健康 Agent。在旧饮食推荐 Agent 的状态机基础上，新增饮食、健身、作息三品类统一健康聊天，以及可替换的 Agent 运行接口、严格输出契约、确定性降级与链路 Trace。

这里的"多 Agent"指由 Java 状态机确定性编排的多角色工作流，不是多个自治 Agent 自主规划或调用工具。候选召回、数值、风险和状态转换由 Java 控制，LLM 负责语义理解和受约束表达。

## 当前能力（31-33 号：Agent 主流程 + 审核资源 + 浏览 API + 餐食 RAG）

- **健康聊天** `POST /api/v1/health/chat`：饮食/健身/作息三品类统一入口，`requestId` 幂等；
- **Agent 运行接口**：`AgentInvoker`（`AgentScopeInvoker` 真实 DashScope / `FixtureAgentInvoker` 固定夹具），业务模块不直接持有 `ReActAgent`；
- **Agent 契约模块**：Prompt/契约版本、JSON/Schema/枚举/候选 ID 校验、超时、失败分类（`TIMEOUT/UPSTREAM_UNAVAILABLE/INVALID_JSON/SCHEMA_VIOLATION/CANDIDATE_VIOLATION/MISSING_CONFIG`）与确定性降级，全部写入 Trace；
- **正交意图模型**：`domain(MEAL/EXERCISE/ROUTINE/COMPOSITE) × task(CHAT/BROWSE/RECOMMEND/PLAN/ADJUST) × riskFlags × phase`，支持 `preferenceSignals`；
- **领域模块**：`MealModule`（封装旧餐食检索+重排）、`ExerciseModule`（版本化种子动作，plan_ready 标记）、`RoutineModule`（结构化作息事实 + 来源引用）；
- **Java 规则决定澄清**：ClarifyAgent 只优化措辞，模板追问可独立继续会话；
- **风险规则**：版本化小规则集，`NORMAL/ADVISORY/BLOCK_PLAN` 三档与固定中文文案；
- **审核资源与浏览 API**：Flyway V3 建 `exercise_item/routine_fact/meal_item_embedding` 并扩展 `meal_item`；启动时幂等导入 ETL 生成的审核子集（288 条餐食、30 个 plan_ready 动作、15 条作息事实，媒体一律无图+保留署名）；`GET /api/v1/health/meals`、`GET /api/v1/health/exercises` 分页浏览（size≤50，越界返回 400）；
- **餐食 RAG**：`EmbeddingClient`（DashScope text-embedding 适配器，失败返回 empty）+ `MealRetriever`（结构化/混合双实现），hybrid 在结构化 top-10 池内做语义重排，embedding 不可用时自动降级；固定标注查询集对比 `Recall@3`/硬约束命中率/降级（见 `docs/research/meal-rag-evaluation.md`）；
- **基础设施**：Java 21 构建基线、Flyway 迁移（V1 旧库基线 + V2 requestId 幂等 + V3 审核资源）、dev/prod 配置、HMAC 匿名 Cookie、admin token 隔离、`/actuator/health`；
- 旧 `/api/v1/diet/**` 接口保持兼容。

## 本地启动

环境要求：Java 21、Maven 3.9+、MySQL 8。

1. 启动本机 MySQL（默认 `root/123456`）。
2. 启动应用（Flyway 自动完成建库迁移，全新库直接建表，已有旧库自动基线；启动时自动幂等导入审核资源）：

```bash
mvn -DskipTests compile
mvn spring-boot:run
```

> 若本地已有按 `src/main/resources/db/diet_db.sql` 导入的旧库，Flyway 会以 `baseline-on-migrate` 标记 V1 并只执行增量迁移，无需重复导入。该 dump 已转为 `db/migration/V1__legacy_baseline.sql` 作为基线。

3. 配置 DashScope API key（无 key 时健康接口自动确定性降级为模板，不影响演示）：

```bash
export DASHSCOPE_API_KEY=sk-xxx
mvn spring-boot:run
```

服务默认运行在 `http://localhost:8080`。

### 重建审核资源数据（可选）

审核资源由 `scripts/build_reviewed_resources.py` 生成并已提交（`src/main/resources/db/seed/reviewed_resources.sql`），通常无需重建。若需重跑：

1. 准备离线输入：`data/meal/processed/healthy_recipes_1000.csv`（`scripts/prepare_meal_dataset.py` 产物，本地文件）与 `data/exercise/raw/exercises.json`（可经 `https://cdn.jsdelivr.net/gh/hasaneyldrm/exercises-dataset@main/data/exercises.json` 下载）；
2. 运行 `python3 scripts/build_reviewed_resources.py`，输出 seed SQL 与 `data/reports/resource_etl_report.json`；
3. 重启应用，`ReviewedResourceSeeder` 幂等导入（INSERT IGNORE）。

### 餐食向量与 RAG 评估（需要真实 API key）

```bash
# 生成向量（幂等写入 meal_item_embedding）
mvn spring-boot:run -Dspring-boot.run.arguments="--diet.embedding.generate-on-startup=true"
# 运行固定查询集评估 → data/reports/rag_evaluation.json
mvn spring-boot:run -Dspring-boot.run.arguments="--diet.rag.eval-run=true"
```

## 配置注入

| 环境变量 | 用途 | 默认 |
|---|---|---|
| `DASHSCOPE_API_KEY` | DashScope 模型 key | 占位符（降级模式） |
| `DASHSCOPE_BASE_URL` | DashScope 兼容端点 | 官方地址 |
| `DIET_SESSION_SECRET` | 匿名 Cookie HMAC 密钥 | `dev-only-change-me` |
| `ADMIN_TOKEN` | admin 调试入口 token | 空（dev 不启用保护） |
| `DATABASE_URL/USERNAME/PASSWORD` | prod 数据源 | dev 用本地 root/123456 |

- **dev**（默认 profile）：允许 `X-User-Id` 回退、admin 不保护、Cookie 不强制 Secure；
- **prod**（`--spring.profiles.active=prod`）：拒绝 `X-User-Id`、强制 `ADMIN_TOKEN` 保护、Cookie Secure；缺少 `DASHSCOPE_API_KEY/DIET_SESSION_SECRET/ADMIN_TOKEN` 时启动失败。
- **Agent 模式**：`diet.agent.mode=fixture` 使用固定夹具离线演示（三品类确定性闭环），默认 `agentscope` 走真实模型；prod 强制 `agentscope`。

## 测试

```bash
mvn test
```

核心自动化覆盖：Agent 契约（合法/非法 JSON、Schema/候选越界、超时、无 key）、夹具适配器、多品类意图路由、澄清继续会话、风险拦截、候选为空、幂等与 Trace 内容、领域模块。固定场景集在无 API key 下可复现。

## 冒烟示例

```bash
# 健康聊天（三品类）
curl -X POST http://localhost:8080/api/v1/health/chat -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-1","message":"午餐想吃清淡的"}'
# 风险拦截
curl -X POST http://localhost:8080/api/v1/health/chat -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-2","message":"我怀孕了怎么安排饮食"}'
# 审核资源浏览（分页，size≤50）
curl "http://localhost:8080/api/v1/health/meals?page=1&size=20"
curl "http://localhost:8080/api/v1/health/exercises?page=1&size=20"
# 健康检查
curl http://localhost:8080/actuator/health
# 旧饮食接口
curl -X POST http://localhost:8080/api/v1/diet/chat -H 'Content-Type: application/json' -H 'X-User-Id: 1' \
  -d '{"message":"中午吃什么","sourceMode":"PUBLIC"}'
```

## 健康 Agent 规划

- [正式规格](.scratch/health-agent/spec.md)
- [实施计划](docs/health-agent-implementation-plan.md)
- [Agent MVP 面试适用性复核](docs/agent-mvp-suitability-review.md)
- [实施票据地图](.scratch/health-agent/map.md)

两级门槛：31-33 号（已完成）交付可测试、可降级、可追踪的三品类 Agent 垂直闭环与审核资源/浏览 API/餐食 RAG；34-36 号继续完成健康档案、周计划、前端和部署证据，通过后才可称为完整健康 Agent MVP。

## 边界

这是面试展示项目，不包含医疗诊断、真实账号体系、生产级安全平台、多实例锁、消息队列或长期行为追踪。聊天链路仍使用 32 号的版本化种子动作/作息事实种子（Java 内，用于离线演示），浏览与检索使用的正式审核资源由 33 号 ETL 导入数据库。完整 MVP 中 Agent 仍只负责意图理解和解释；候选召回、数值计算、计划规则和风险结论由 Java 确定性逻辑控制。
