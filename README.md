# health_agent

基于 Spring Boot、AgentScope/DashScope、MyBatis 和 MySQL 的健康 Agent。在旧饮食推荐 Agent 的状态机基础上，新增饮食、健身、作息三品类统一健康聊天，以及可替换的 Agent 运行接口、严格输出契约、确定性降级与链路 Trace。

这里的"多 Agent"指由 Java 状态机确定性编排的多角色工作流，不是多个自治 Agent 自主规划或调用工具。候选召回、数值、风险和状态转换由 Java 控制，LLM 负责语义理解和受约束表达。

## 当前能力（31-34 号 + #85-#87：Agent 主流程、受约束训练计划与 Trace）

- **健康聊天** `POST /api/v1/health/chat`：饮食/健身/作息三品类统一入口，`requestId` 幂等，缺省 `sessionId` 时按匿名身份派生稳定默认会话；
- **Agent 运行接口**：`AgentInvoker`（`AgentScopeInvoker` 真实 DashScope / `FixtureAgentInvoker` 固定夹具），业务模块不直接持有 `ReActAgent`；
- **Agent 契约模块**：Prompt/契约版本、JSON/Schema/枚举/候选 ID 校验、超时、失败分类（`TIMEOUT/UPSTREAM_UNAVAILABLE/INVALID_JSON/SCHEMA_VIOLATION/CANDIDATE_VIOLATION/MISSING_CONFIG`）与确定性降级，全部写入 Trace；
- **正交意图模型**：`domain(MEAL/EXERCISE/ROUTINE/COMPOSITE) × task(CHAT/BROWSE/RECOMMEND/PLAN/ADJUST) × riskFlags × phase`，支持 `preferenceSignals`；
- **领域模块**：`MealModule`（餐食混合检索）、`ExerciseModule`、`RoutineModule`，统一通过 `HealthResourceProvider` 读取审核资源（数据库审核子集 / fixture 种子两种互斥模式）；
- **Java 规则决定澄清**：ClarifyAgent 只优化措辞，模板追问可独立继续会话；
- **风险规则**：单一版本化 `RiskRuleCatalog`（唯一事实来源），`NORMAL/ADVISORY/BLOCK_PLAN` 三档与固定中文文案，三阶段 Guard（候选前/组合时/输出后）；
- **健康档案与周计划**：Mifflin-St Jeor 能量区间、DRAFT/ACTIVE/ARCHIVED 生命周期、版本快照（档案/规则/会话/事实/资源生成依据）、`/api/v1/health/plans/**`、事务化写入 + 行锁 + 数据库级 ACTIVE 唯一约束；
- **训练计划演示闭环（#85-#86）**：PLAN 上下文维护独立 `planBrief`，支持多轮收集、确认、纠正和缺档案往返；生成服务端重读已确认简报，只从审核 `plan_ready` 候选选择，确定性 Guard 负责约束，模型失败立即规则降级，返回 `planId/traceId/generationSource` 并支持七日查看和激活；
- **Trace 最小诊断工作台（#87）**：管理员可查看 `SUCCESS + DEGRADED`、总耗时/Agent 耗时、模型与 token 状态、解析/Guard/fallback 结果、按 `stepOrder` 排序的事件时间线和脱敏 JSON；API 继续受 `ADMIN_TOKEN` 保护。
- **类型化反馈**：`POST /api/v1/health/feedback`（resourceType+resourceId），DISLIKE 硬过滤、LIKE/FAVORITE/ADOPT 确定性重排；旧饮食反馈经适配层补齐类型化字段；
- **审核资源与浏览 API**：启动时幂等导入 ETL 生成的审核子集（295 条餐食、30 个 plan_ready 动作、15 条作息事实）；dev 另行幂等导入 1,324 条本地动作目录与已授权媒体，动作浏览展示完整目录，推荐和周计划仍只消费审核/计划资格动作；`GET /api/v1/health/meals`、`GET /api/v1/health/exercises` 分页浏览（size≤50、page 超上限返回 400）；
- **餐食 RAG（M5 #52 融合；#77 扩展评测）**：`EmbeddingClient`（DashScope text-embedding 适配器，失败返回 empty）+ `MealRetriever`（结构化/混合双实现），hybrid 现在执行**结构化召回 + Qdrant 独立向量召回两条路径的候选融合**（payload 过滤审核状态/来源/过敏原/排除 ID，按 ID 回查 MySQL 二次执行全部硬约束，过期索引命中直接丢弃），融合权重可经 `diet.rag.fusion-weight` 注入（默认 0.5），Embedding/Qdrant 不可用、超时或空结果时立即退回结构化检索并标记降级原因；固定标注查询集（60 条六层：精确标签/自然语言/长尾表达/同义词/排除项/过敏原）评估 `Recall@3`/MRR/NDCG@3/Precision@3/硬约束命中率/P95 延迟/降级分布，并组织权重与嵌入文本消融（见 `docs/research/meal-rag-evaluation.md`，数字以 `data/reports/rag_evaluation.json` 为准）；
- **基础设施**：Java 21 构建基线、Flyway 迁移（V1 旧库基线 → V14，含 V6 计划版本依据与 ACTIVE 约束、V8 反馈归因、V9 评估标注字段、V13 训练目标标签、V14 计划生成来源/元数据）、dev/prod 配置、HMAC 匿名 Cookie、admin token 隔离、`/actuator/health`；
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

3. 在仓库根目录 `.env` 配置 DashScope API key（Spring Boot 启动时自动读取，文件已被 Git 忽略；无 key 时健康接口自动确定性降级为模板）：

```dotenv
DASHSCOPE_API_KEY=填写你的实际密钥
```

然后启动应用：

```bash
mvn spring-boot:run
```

也可以使用 `export DASHSCOPE_API_KEY=...` 或命令行参数覆盖 `.env`；系统环境变量和命令行参数的优先级更高。不要把真实 key 写入受版本控制的配置文件。

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

### Qdrant 向量索引与重建（M5 #54）

MySQL 始终是餐食事实的真相源；Qdrant 只是按 `provider + model + dimension + version`
身份可重建的向量索引。启动 Compose（`docker compose up -d`）后 qdrant 服务（1.17.0）
监听 REST 6333 / gRPC 6334，应用通过 gRPC 6334 访问。

```bash
# 把已生成的 meal_item_embedding 批量索引到 Qdrant（幂等，可重复执行）
mvn spring-boot:run -Dspring-boot.run.arguments="--diet.vectorstore.mode=qdrant --diet.vectorstore.index-on-startup=true"
# 或经 Compose 环境变量启用（DIET_VECTORSTORE_MODE=qdrant 后 app 自动带 QDRANT_HOST=qdrant）
```

- collection 名由身份派生（如 `meal_dashscope_text-embedding-v3_1024_v3-1024`），距离 Cosine；
  身份默认跟随 `diet.embedding.*`（`diet.vectorstore.model/dimension/version` 可显式覆盖）；
- **重建方式**：先跑一次"生成向量"确保 `meal_item_embedding` 完整，再以上述命令索引；
  embedding 模型/维度变更时身份自动换新 collection（或显式覆盖身份），
  旧 collection 维度不匹配时 `ensureCollection` 返回 false 并降级为结构化检索，
  `clear()` 按身份重建，索引时维度不一致的向量会被跳过；
- Qdrant 不可用或 collection 缺失时，现有结构化/hybrid 检索自动降级，不损坏 MySQL 业务数据。

### MCP Streamable HTTP 端点（M5 #51）

应用在 `/mcp` 注册单一 MCP Streamable HTTP 端点（MCP Java SDK 0.17.0 servlet transport），
供外部 MCP 客户端（Inspector/自定义客户端）发现与调用只读/纯计算工具（M5 #47 注册）。
身份与 Origin 边界由独立 Filter 承担，与匿名 Cookie、可编辑用户头和管理 token 隔离：

- 请求必须携带 `Authorization: Bearer <MCP_API_TOKEN>`，token 缺失/错误返回 401；
  未配置 `MCP_API_TOKEN` 时端点 fail-closed 拒绝所有请求；
- Origin 头存在时必须**精确命中** `MCP_ALLOWED_ORIGINS`（逗号分隔，scheme/host/port 全等）
  否则 403（#61 fail-closed：空 allowlist 不再表示任意来源，重复/前缀欺骗/大小写异常一律拒绝）；
  缺失 Origin（curl/Inspector/受控服务端客户端）按 `DIET_MCP_ALLOW_MISSING_ORIGIN` 放行
  （#61 默认 false；dev profile 在 application-dev.yml 显式放行本地 Inspector 流程）；
- prod 启用 MCP token 时必须显式配置 `MCP_ALLOWED_ORIGINS`，否则启动失败；
  占位 token、空白条目与非法 Origin URI 在任何 profile 启动阶段直接拒绝；
- DNS rebinding 风险的主缓解是 **Bearer token 强制鉴权**（非 Cookie 凭据，跨站页面无法
  携带或读取），Origin 校验是纵深防御：生产部署请配置 allowlist 并保持
  `DIET_MCP_ALLOW_MISSING_ORIGIN=false`；
- 鉴权结果经 transport context 传入工具 handler（`principal=mcp-client`）；
- **该实现不是 MCP OAuth 2.1，也不声明最新规范全量合规**，仅用于本地演示与面试讲解。

### 四个公共 MCP Tools 与 Skills Resources（M5 #47/#50）

`/mcp` 只暴露四个只读或纯计算工具（M5 #47），handler 直接调用既有领域服务，不通过
本应用 HTTP API 回调自身，也不产生任何业务写入：

| 工具 | 作用 | 复用领域服务 |
|---|---|---|
| `search_meals` | 按健康槽位检索审核餐食（含硬约束与混合检索） | `MealModule.recommendMeals` |
| `get_meal_detail` | 按资源 ID 查审核餐食详情 | `HealthResourceProvider.mealById` |
| `get_routine_facts` | 按关键词查结构化作息事实 | `RoutineModule.lookup` |
| `calculate_targets` | 确定性计算每日能量区间（不写档案） | `EnergyCalculator` |

参数错误映射 `INVALID_PARAMS`，资源不存在映射 `RESOURCE_NOT_FOUND`，业务失败返回
`isError` 结果。三个版本化技能 manifest（`skills/*.yaml`，固定
`name/version/description/input_schema/output_schema/allowed_tools/risk_level` 字段）由
Skills Registry 在启动时校验（Schema 可解析、`allowed_tools` 属于四工具 allowlist、
name 唯一），并以稳定 URI `skill://<name>` 通过 `resources/list` 与 `resources/read`
暴露原始 YAML（M5 #50）；非法 manifest 直接拒绝启动。

## 配置注入

| 环境变量 | 用途 | 默认 |
|---|---|---|
| `DASHSCOPE_API_KEY` | DashScope 模型 key（可由根目录 `.env` 或系统环境变量注入，空则降级/失败） | 空 |
| `DASHSCOPE_BASE_URL` | DashScope 兼容端点 | 官方地址 |
| `DASHSCOPE_EMBEDDING_BASE_URL` | Embedding 原生端点（专属 MaaS 空间与聊天兼容端点不同时需单独配置） | 回退聊天端点 |
| `DIET_LLM_MAIN_MODEL` | 推荐/计划等主生成模型 | `qwen-turbo` |
| `DIET_LLM_LIGHT_MODEL` | 意图/澄清等轻量模型 | `qwen3.7-flash` |
| `DIET_SESSION_SECRET` | 匿名 Cookie HMAC 密钥 | `dev-only-change-me` |
| `ADMIN_TOKEN` | admin 调试入口 token | 空（dev 不启用保护） |
| `DATABASE_URL/USERNAME/PASSWORD` | prod 数据源 | dev 用本地 root/123456 |
| `DIET_VECTORSTORE_MODE` | 餐食向量索引模式（in-memory/qdrant） | `in-memory` |
| `DIET_VECTORSTORE_INDEX_ON_STARTUP` | 启动时批量索引审核餐食向量到向量存储 | `false` |
| `QDRANT_HOST/QDRANT_GRPC_PORT` | Qdrant gRPC 地址（qdrant 模式） | `localhost`/`6334` |
| `MCP_API_TOKEN` | /mcp 端点 Bearer token（未配置 fail-closed） | 空 |
| `MCP_ALLOWED_ORIGINS` | /mcp Origin allowlist（逗号分隔；#61 空 allowlist + 非空 Origin 一律 403，prod 启用 token 时必须配置） | 空 |
| `DIET_MCP_ALLOW_MISSING_ORIGIN` | 缺失 Origin 是否放行（#61 默认 false fail-closed；dev 显式 true 保留 Inspector 流程） | `false` |

- **dev**（默认 profile）：允许 `X-User-Id` 回退、admin 不保护、Cookie 不强制 Secure；
- **prod**（`--spring.profiles.active=prod`）：拒绝 `X-User-Id`、强制 `ADMIN_TOKEN` 保护、Cookie Secure；缺少 `DASHSCOPE_API_KEY/DIET_SESSION_SECRET/ADMIN_TOKEN` 时启动失败。
- **Agent 模式**：`diet.agent.mode=fixture` 使用固定夹具离线演示（三品类确定性闭环），默认 `agentscope` 走真实模型；prod 强制 `agentscope`。

## 测试

```bash
mvn test
```

核心自动化覆盖：Agent 契约（合法/非法 JSON、Schema/候选越界、超时、无 key）、夹具适配器、多品类意图路由、澄清继续会话、风险拦截（目录一致性）、候选为空、幂等与 Trace 内容、领域模块、资源 Provider 双模式、浏览 API 分页边界、类型化反馈迁移与健康反馈 API、周计划事务/行锁/激活不变量、planBrief 与受约束训练计划生成/fallback、Trace 诊断、版本生成依据，以及 MCP/Qdrant 兼容性冒烟、VectorStore 适配器、MCP 端点安全边界（token/Origin）、Trace 脱敏、MCP 四工具与 Skills Registry/Resources、hybrid 独立向量召回融合与二次硬约束。固定场景集在无 API key 下可复现；当前 `mvn test` 发现 670 个测试（633 通过，37 个环境门控按条件跳过）。

### 真实 MySQL 集成测试（事务回滚与行锁）

39 号票剩余项已在 38 号总验收落地：独立测试库 `diet_db_itest`（自动建库 + Flyway 迁移 V1-V14）上验证 saveProfile/createDraft/activate 任一步写入失败时数据库无半成品、并发激活只有一个有效 ACTIVE、激活后档案版本与能量区间与快照一致；#86 另外覆盖训练计划 requestId 幂等、失败回滚、候选 Guard 和激活重检。需要本机 MySQL（root/123456，与 dev 配置一致）：

```bash
mvn test -Ditest.mysql=true
```

CI 的 MySQL 服务容器以同一账号启动，因此 CI 会运行 34 个 MySQL 集成场景（事务 18 + reviewed 计划/餐食 4 + reviewed readers 12）；本机启用该门控时结果为 667 通过、仅 3 个 Qdrant 场景跳过。Qdrant 1.17.0 在本机 gRPC 6334 端口运行时，可用 `mvn test -Ditest.mysql=true -Ditest.qdrant=true` 执行全部 670 个测试。

## 部署（Compose，spec 11）

单实例 Nginx + Spring Boot + MySQL，Nginx 托管 `frontend/` 并同源反代 `/api/`：

```bash
cp deploy/.env.example .env   # 填写 DIET_SESSION_SECRET / ADMIN_TOKEN 等
docker compose up -d --build
```

打开 `http://localhost`。prod profile 强制真实模型与 admin token 保护，缺少必要环境变量时启动失败（特性）。

### 备份与回退（spec 11）

- **迁移前备份**：Flyway 迁移前执行 `mysqldump` 备份（Compose 场景：`docker compose exec -T mysql mysqldump -uroot -p diet_db > diet_db_$(date +%Y%m%d).sql`）；
- **失败回退**：回退上一个应用镜像（`docker compose up -d` 使用上次镜像 tag）或前向修复，不执行破坏性自动回滚；
- **健康检查**：`curl http://localhost/actuator/health`（Nginx 已反代该路径；后端直连为 `curl http://localhost:8080/actuator/health`）。

## CI

`.github/workflows/ci.yml`：Java 21（Temurin）编译 + MySQL 8.4 服务容器 + `mvn -Ditest.mysql=true test` 全量测试，失败自动上传 surefire 报告。

## 完成证据

[M0-M4 完成证据清单](docs/release-evidence.md) 汇总 28 号验收矩阵在 36 号总验收中的所有证据位置（自动化、浏览器、真实 DashScope 冒烟、Compose/CI、干净环境复现）。

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

两级门槛：31-33 号交付可测试、可降级、可追踪的三品类 Agent 垂直闭环与审核资源/浏览 API/餐食 RAG；34 号完成健康档案、周计划、风险 Guard 与计划 API；审计修复批次 39-44 号补齐事务化、统一资源 Provider、类型化反馈、版本生成依据、聊天稳定性与风险规则目录，均已有自动化证据。35 号前端与 36 号最终验收（真实 DashScope 冒烟、MySQL 迁移、浏览器流程、Compose/CI、干净环境复现）通过后即完整健康 Agent MVP，证据见 [M0-M4 完成证据清单](docs/release-evidence.md)。

## 边界

这是面试展示项目，不包含医疗诊断、真实账号体系、生产级安全平台、多实例锁、消息队列或长期行为追踪。健康聊天、周计划与反馈使用 `HealthResourceProvider`；正式审核库浏览、Structured/Hybrid RAG、Embedding、向量索引与评估使用专用 `health/reader` 读取模块。正式模式（`diet.resource.mode=reviewed`，默认）读数据库审核子集，fixture 模式（`diet.resource.mode=fixture`，生产禁止）读内存种子用于离线演示；fixture 不提供正式审核库浏览和批处理能力，两种模式由 providerMode 标识隔离、禁止混用。完整 MVP 中 Agent 仍只负责意图理解和解释；候选召回、数值计算、计划规则和风险结论由 Java 确定性逻辑控制。
