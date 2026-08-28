# health_agent

基于 Spring Boot、AgentScope/DashScope、MyBatis 和 MySQL 的健康 Agent。在旧饮食推荐 Agent 的状态机基础上，新增饮食、健身、作息三品类统一健康聊天，以及可替换的 Agent 运行接口、严格输出契约、确定性降级与链路 Trace。

这里的"多 Agent"指由 Java 状态机确定性编排的多角色工作流，不是多个自治 Agent 自主规划或调用工具。候选召回、数值、风险和状态转换由 Java 控制，LLM 负责语义理解和受约束表达。

## 当前能力（#90–#94：计划语义收敛、范围隔离与详情优先）

- **健康聊天** `POST /api/v1/health/chat`：饮食/健身/作息三品类统一入口，`requestId` 幂等，缺省 `sessionId` 时按匿名身份派生稳定默认会话；
- **Agent 运行接口**：`AgentInvoker`（`AgentScopeInvoker` 真实 DashScope / `FixtureAgentInvoker` 固定夹具），业务模块不直接持有 `ReActAgent`；
- **Agent 契约模块**：Prompt/契约版本、JSON/Schema/枚举/候选 ID 校验、超时、失败分类（`TIMEOUT/UPSTREAM_UNAVAILABLE/INVALID_JSON/SCHEMA_VIOLATION/CANDIDATE_VIOLATION/MISSING_CONFIG`）与确定性降级，全部写入 Trace；
- **正交意图模型**：`domain(MEAL/EXERCISE/ROUTINE/COMPOSITE) × task(CHAT/BROWSE/RECOMMEND/PLAN/ADJUST) × riskFlags × phase`，支持 `preferenceSignals`；
- **领域模块**：`MealModule`（餐食两段式检索路由）、`ExerciseModule`、`RoutineModule`，统一通过 `HealthResourceProvider` 读取审核资源（数据库审核子集 / fixture 种子两种互斥模式）；
- **Java 规则决定澄清**：ClarifyAgent 只优化措辞，模板追问可独立继续会话；
- **风险规则**：单一版本化 `RiskRuleCatalog`（唯一事实来源），`NORMAL/ADVISORY/BLOCK_PLAN` 三档与固定中文文案，三阶段 Guard（候选前/组合时/输出后）；
- **健康档案与范围化周计划**：Mifflin-St Jeor 能量区间、DRAFT/UNENABLED/ENABLED/HISTORY 生命周期（V18 起统一综合计划语义）、版本快照（档案/规则/会话/事实/资源生成依据）、`/api/v1/health/plans/**`、事务化写入 + 行锁 + 数据库级按用户 ENABLED 唯一约束；新计划只允许 `EXERCISE`、`MEAL`、`COMPOSITE`，并在计划和版本快照中保存 `planScope`；
- **口语化计划简报（#91）**：训练/餐食分别维护独立简报，确定性解析星期和中文时间，解释结果区分 `EXTRACTED/PARTIAL/AMBIGUOUS/UNRELATED/INVALID`；规则无法安全解析且仍疑似回答当前字段时才调用一次结构化提取 Agent，候选经 Java 校验后合并；话题切换会保留未完成简报并回到完整意图链；
- **隔离生成闭环（#92）**：训练、餐食分别受约束生成，综合计划仅在两个子简报分别确认后由确定性服务合并并一次性持久化；任何新计划不隐式加入餐食、训练或作息之外的项目；V15 清理旧测试计划并增加范围约束，V16 扩展生成来源字段；
- **详情优先计划页（#93）**：历史计划收敛为选择器，当前详情占主宽度；纯训练/纯餐食只展示相关统计与操作，综合计划支持全部/训练/餐食筛选；宽屏、中等宽度和移动端按内容重排，保留详情编辑抽屉；
- **Trace 最小诊断工作台（#87）**：管理员可查看 `SUCCESS + DEGRADED`、总耗时/Agent 耗时、模型与 token 状态、解析/Guard/fallback 结果、按 `stepOrder` 排序的事件时间线和脱敏 JSON；API 继续受 `ADMIN_TOKEN` 保护。
- **类型化反馈**：`POST /api/v1/health/feedback`（resourceType+resourceId），DISLIKE 硬过滤、LIKE/FAVORITE/ADOPT 确定性重排；旧饮食反馈经适配层补齐类型化字段；
- **审核资源与浏览 API**：启动时幂等导入 ETL 生成的审核子集（295 条餐食、30 个 plan_ready 动作、15 条作息事实）；dev 另行幂等导入 1,324 条本地动作目录与已授权媒体，动作浏览展示完整目录，推荐和周计划仍只消费审核/计划资格动作；`GET /api/v1/health/meals`、`GET /api/v1/health/exercises` 分页浏览（size≤50、page 超上限返回 400）；
- **餐食 RAG（M5 #52 融合；#77 扩展评测）**：线上检索经 `MealRetrievalRouter` 两段式路由（含餐次/过敏原/排除项等强约束或常规表达走结构化，无强约束的主观/长尾表达进入语义实验），`EmbeddingClient`（DashScope 默认适配器，可经 `diet.embedding.provider=minimax` 切换实验适配器，失败返回 empty）+ `MealRetriever`（结构化/混合双实现），hybrid 现在执行**结构化召回 + Qdrant 独立向量召回两条路径的候选融合**（payload 过滤审核状态/来源/过敏原/排除 ID，按 ID 回查 MySQL 二次执行全部硬约束，过期索引命中直接丢弃），融合权重可经 `diet.rag.fusion-weight` 注入（默认 0.5），Embedding/Qdrant 不可用、超时或空结果时立即退回结构化检索并标记降级原因；固定标注查询集（60 条六层：精确标签/自然语言/长尾表达/同义词/排除项/过敏原）评估 `Recall@3`/MRR/NDCG@3/Precision@3/硬约束命中率/P95 延迟/降级分布，并组织权重与嵌入文本消融（见 `docs/research/meal-rag-evaluation.md`，数字以 `data/reports/rag_evaluation.json` 为准）；
- **基础设施**：Java 21 构建基线、Flyway 迁移（V1 旧库基线 → V20，含 V6 计划版本依据与 ACTIVE 约束、V8 反馈归因、V9 评估标注字段、V13 训练目标标签、V14 计划生成来源/元数据、V15 计划范围与旧测试数据清理、V16 生成来源扩容、V18 统一综合计划生命周期与写入幂等（ACTIVE 约束收敛为按用户 ENABLED 唯一）、V20 动作源保真与资格审计）、dev/prod 配置、HMAC 匿名 Cookie、admin token 隔离、`/actuator/health`；
- 旧 `/api/v1/diet/**` 接口保持兼容。

## 本地启动

环境要求：Java 21、Maven 3.9+、MySQL 8。

### 一键启动（推荐）

```bash
./scripts/start-local.sh     # 启动并自动打开 http://localhost:8092/#/chat
./scripts/stop-local.sh      # 停止后端与前端 Nginx 容器（不动 MySQL/Qdrant 数据）
```

脚本行为：检查 Java 21 / Maven / Docker / 本机 MySQL → 编译并后台启动 Spring Boot（默认 `8082`）→ 等待 `/actuator/health` 变为 `UP` → 以无状态容器 `health-agent-local-nginx` 在默认 `8092` 端口重建前端 Nginx 并同源反代 `/api/` 与健康检查 → 自检通过后打开浏览器。日志在 `.local-run/logs/`（已 gitignore），后端 pid 在 `.local-run/backend.pid`。复用本机 MySQL 而非 Compose MySQL，不触发 prod 必填配置校验，也不占用 80 端口；Compose 的 MySQL/Qdrant 及其数据卷不受影响。

- **端口占用**：若目标端口已被本应用占住且健康则直接复用；被无关进程占用会报错退出，绝不静默换端口。可用环境变量换端口：`BACKEND_PORT=8083 FRONTEND_PORT=8093 ./scripts/start-local.sh`。
- **其他可选项**：`WAIT_TIMEOUT_SECS`（等 UP 的超时秒数，默认 300）、`NO_OPEN=1`（不自动开浏览器）。未配置 `DASHSCOPE_API_KEY` 时服务照常启动，聊天回答确定性降级为模板文案。

### 手动启动

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

线上默认路线是 Structured；MiniMax 仅用于独立实验 collection 对照。MySQL 是餐食事实源，
Qdrant 是可重建索引。评测报告会记录查询集、语料版本、provider/model/dimension、collection、
git commit 和工作树状态。

```bash
# 生成向量（幂等写入 meal_item_embedding）
mvn spring-boot:run -Dspring-boot.run.arguments="--diet.embedding.generate-on-startup=true"
# 运行固定查询集评估 → data/reports/rag_evaluation.json
mvn spring-boot:run -Dspring-boot.run.arguments="--diet.rag.eval-run=true"
```

MiniMax 对照需在本地 `.env` 配置 `DIET_EMBEDDING_PROVIDER=minimax`、
`MINIMAX_API_KEY`、`MINIMAX_EMBEDDING_MODEL=embo-01`、`DIET_EMBEDDING_DIMENSIONS=1536`
和可选 `MINIMAX_GROUP_ID`，并使用独立报告路径：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--diet.embedding.provider=minimax --diet.embedding.model=embo-01 --diet.embedding.dimensions=1536 --diet.vectorstore.provider=minimax --diet.vectorstore.model=embo-01 --diet.vectorstore.dimension=1536 --diet.vectorstore.version=minimax-1536 --diet.vectorstore.mode=qdrant --diet.rag.mode=hybrid --diet.rag.eval-run=true --diet.rag.eval-report-path=data/reports/rag_evaluation_minimax.json"
```

MiniMax 评测只用于效果对照，不改变 `diet.rag.mode=structured` 的线上默认。不要提交 `.env`
或任何真实 API key。

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
| `DIET_LLM_LIGHT_MODEL` | 意图/澄清等轻量模型 | `qwen-turbo` |
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

核心自动化覆盖：Agent 契约（合法/非法 JSON、Schema/候选越界、超时、无 key）、夹具适配器、多品类意图路由、口语化计划简报与话题切换、风险拦截（目录一致性）、候选为空、幂等与 Trace 内容、领域模块、资源 Provider 双模式、浏览 API 分页边界、类型化反馈迁移与健康反馈 API、范围化周计划事务/行锁/激活不变量、训练/餐食/综合受约束生成与 fallback、计划上下文和选择器交互回归、Trace 诊断、版本生成依据，以及 MCP/Qdrant 兼容性冒烟、VectorStore 适配器、MCP 端点安全边界（token/Origin）、Trace 脱敏、MCP 四工具与 Skills Registry/Resources、hybrid 独立向量召回融合与二次硬约束、餐食两段式检索路由、MiniMax 嵌入适配器与语料清单校验。当前 Surefire 基线为 729 个测试：`mvn test` 725 通过 + 4 个环境门控跳过；MySQL 门控 725 通过 + 4 个独立门控跳过。前端行为与交互契约测试为 31/31。

### 真实 MySQL 集成测试（事务回滚与行锁）

39 号票剩余项已在 38 号总验收落地：独立测试库 `diet_db_itest`（自动建库 + Flyway 迁移 V1-V20）上验证 saveProfile/范围化生成/启用任一步写入失败时数据库无半成品、并发启用只有一份 ENABLED、激活后档案版本与能量区间与快照一致；#90–#94 另外覆盖训练/餐食/综合 requestId 幂等、失败回滚、候选 Guard、范围一致性和激活重检。需要本机 MySQL（root/123456，与 dev 配置一致）：

```bash
mvn test -Ditest.mysql=true
```

CI 的 MySQL 服务容器以同一账号启动，因此 CI 会运行 40 个 MySQL 集成场景（事务 18 + reviewed 计划/餐食 7 + reviewed readers 15）；本次本机 MySQL 门控结果为 711 通过、4 个独立门控跳过。Qdrant 1.17.0 在本机 gRPC 6334 端口运行时，`mvn test -Ditest.mysql=true -Ditest.qdrant=true` 结果为 714 通过、仅 1 个显式 live-model 门控跳过。

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
