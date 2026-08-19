# 发布证据与完成门槛清单（28 号票 / 36 号总验收）

> 本文档汇总 36 号（#38）最终总验收的证据位置，对应 28 号"发布证据与验收门槛"矩阵。
> 证据类型：自动化测试、接口冒烟、真实浏览器、部署检查。原始 M0-M4 基线记录于 2026-08-11；后续验收以各条目日期和最新报告为准。

## M0：Java 21 编译、环境变量与身份隔离

| 条目 | 证据 |
|---|---|
| Java 21 编译基线 | 提交 3547bc7（POM `maven.compiler.release=21` + clean build + `javap` major 65）；29 号票验收记录 |
| prod 必要配置缺失启动失败 | `ProductionConfigurationValidator`（`src/main/java/com/diet/config/`）+ `application-prod.yml`；CI 全量测试通过 |
| 匿名身份隔离（Cookie 替换 X-User-Id） | `AnonymousIdentityInterceptor` + HMAC `HEALTH_SESSION` Cookie；`HealthSessionServiceTest`（5 个用例）；浏览器验收记录第 1 节 |

## M1：Agent 垂直闭环（面试可演示门槛）

| 条目 | 证据 |
|---|---|
| 固定夹具 + 真实 Agent 适配器 | `AgentContractModuleTest`（7 个）、`FixtureAgentInvokerTest`（5 个）、`OpenAiCompatibleChatModelTest`（4 个）；真实 DashScope 冒烟记录（map.md：qwen3.8-max/qwen3.7-flash 实跑） |
| 三品类意图路由 | `com.diet.health.intent` 测试（意图域/任务/风险标记）；`HealthOrchestratorServiceTest`（13 个） |
| 输出校验 + 确定性降级 | 契约模块非法 JSON/Schema/候选越界/超时/无 key 测试；`HealthChatDegradationTest` 等降级测试 |
| Trace 内容 | `AgentTraceMapper` + Trace 查询测试；浏览器 admin 页面可查事件 JSON |
| 运行时验证 | 34 号票 fixture 模式真实启动冒烟记录（map.md） |

## M2：审核资源、浏览 API 与餐食 RAG

| 条目 | 证据 |
|---|---|
| 资源基线（295 餐食/1324 动作目录，其中 30 个 plan_ready/15 事实） | `ReviewedResourceSeedValidatorTest`、`ReviewedResourceSeeder`/`LocalMediaCatalogSeeder` 幂等导入；`DbReviewedResourceProviderTest`、`MysqlReviewedReadersIntegrationTest` |
| 浏览 API 分页边界 | `health/browse` 测试（page 超上限返回 400、size≤50）；浏览器验收第 2/3 节 |
| hybrid RAG 与结构化降级 | `health/rag` 测试；固定查询集（60 条六层，querySetVersion 1.1.0）评估见 `docs/research/meal-rag-evaluation.md`。当前报告已记录 Structured/Vector/Fused 数量、向量状态、阶段延迟，并区分 `REAL_HYBRID`、`PARTIAL_HYBRID`、`FALLBACK_ONLY`。 |
| 真实 key 下 RAG 对比 | 2026-08-19 本地 MySQL + Qdrant 1.17.0 实跑：295/295 条索引、60/60 条零降级，`REAL_HYBRID`；Recall@3 Structured/Hybrid 均为 0.351，硬约束命中率均为 1.0，Hybrid P95 为 159.407 ms。结果详见 `data/reports/rag_evaluation.json`，不宣称效果提升。 |

## M3：健康档案、周计划、风险校验与反馈

| 条目 | 证据 |
|---|---|
| Mifflin-St Jeor 能量区间 | `EnergyCalculatorTest` |
| 周计划不变量（版本/激活/归档） | `WeeklyPlanServiceTest`（20 个）+ `PlanValidationServiceTest` + `WeeklyPlanComposerServiceTest` |
| 事务化 + 行锁 + ACTIVE 唯一约束 | 真实 MySQL 集成验证：`src/test/java/com/diet/integration/MysqlTransactionIntegrationTest.java`（18 个用例，`-Ditest.mysql=true` 门控）：V1-V16 迁移、范围化生成与激活失败无半成品、并发激活唯一成功、激活后档案版本/能量区间与快照一致、档案版本号连续唯一、MySQL 不可用快速失败 |
| 三阶段风险 Guard | `HealthRiskRuleServiceTest`、`RiskRuleCatalogTest`、`PlanValidationServiceTest` |
| 类型化反馈闭环 | `FeedbackServiceTest`、`health/feedback` 测试；浏览器收藏/喜欢/采纳反馈落库 |

## M4：前端、旧兼容、部署健康检查与干净环境（完整 MVP 门槛）

| 条目 | 证据 |
|---|---|
| 前端模块与用户页面 | 35 号已验收：`frontend/` 原生 ES Modules 迁移，桌面 1280×800 与移动端 390×844 真实浏览器验收，见 `docs/frontend-browser-acceptance.md`（9 处问题已修复） |
| 真实浏览器三条主流程 | 桌面聊天→详情→反馈、餐食/动作浏览→筛选→收藏、计划 DRAFT 编辑→激活；移动端无横向溢出（验收记录第 1-4 节 + 移动端节） |
| 旧 `/api/v1/diet/**` 兼容 | `#/diet` 旧入口浏览器验收（第 5 节）；旧饮食服务测试 |
| CI：Java 21 + MySQL 服务容器 | **本次新增** `.github/workflows/ci.yml`：Java 21 编译 + `mvn -Ditest.mysql=true test`（含真实 MySQL 集成测试） |
| Compose 部署拓扑 | **本次新增** `docker-compose.yml`（mysql 8.4 + app + nginx 同源反代）+ `Dockerfile`（多阶段 Java 21）+ `deploy/nginx.compose.conf` + `deploy/.env.example` |
| 健康检查 | `/actuator/health`（`management.endpoints.web.exposure` 已暴露）；Compose 中 MySQL healthcheck |
| 失败样例固定结果 | 非法 JSON/API key 缺失/候选为空/重复 requestId/Cookie 篡改/admin 未授权 均由契约与降级测试覆盖；Embedding 不可用降级见 RAG 记录；**MySQL 不可用**：连接快速失败且错误类型确定（`DataAccessResourceFailureException`），见 `MysqlTransactionIntegrationTest`；媒体 404 前端兜底已实现（待真实媒体 URL 复验） |
| 干净环境复现 | `mvn clean test`（本机全量 286 个测试通过：276 单元 + 10 集成）；CI 从干净环境复现（GitHub Actions run 31469690191，push 51ebbf3 全绿，日志确认 `MysqlTransactionIntegrationTest` 10 用例真实执行、Skipped 0） |

## 面试可演示门槛 vs 完整 MVP 门槛

- **面试可演示门槛 = M0 + M1**：三品类 Agent 路由、严格契约、确定性降级、Trace 可查，由 31/32 号交付。
- **完整 MVP 门槛 = M0-M4 全部**：资源/浏览/RAG（33 号）、档案/计划/风险/反馈（34 号）、前端与部署（35 号）+ 本清单全部证据（36 号）。
- 两级门槛分别标记，不得用前者冒充后者；README 已按此口径描述。

## #85-#87 健康 Agent 演示核心流程（2026-08-19）

| 条目 | 证据 |
|---|---|
| 训练计划需求简报与确认 | `PlanBriefServiceTest`、`HealthSessionServiceTest`、`HealthOrchestratorServiceTest`；独立 `planBrief` JSON、PLAN 上下文合并/纠正/确认和缺档案保留简报；V13 训练目标标签迁移兼容 fresh/legacy DB |
| 受约束 Agent 训练计划 | `TrainingPlanGenerationServiceTest`、MySQL 事务门控；服务端重读确认简报，审核 `plan_ready` 候选白名单，确定性 Guard/fallback，V14 生成来源/元数据和 requestId 幂等；fixture 场景完成草稿查看、七日安排和激活 |
| Trace 最小诊断工作台 | `AgentTraceDiagnosticTest` 与既有 admin token/脱敏测试；支持 `SUCCESS + DEGRADED`、耗时聚合、模型/token/解析/Guard/fallback、按 `stepOrder` 的时间线和脱敏嵌套 JSON |
| 真实浏览器 | `docs/frontend-browser-acceptance.md` 当前 #85-#87 段：`http://localhost:18092/#/chat`、`#/plans`、`#/admin/traces`；桌面 1710×983、移动 390×844；生成/激活、Trace 展示、401 鉴权边界和移动端无文档级横向溢出通过 |
| 历史自动化结果 | 本表上方记录的是 #85–#87 当时的 684-test 运行；本轮 #90–#94 的最新结果见下方，不用历史数字代表当前工作树 |
| 外部模型证据 | `mvn -q -Dtest=LiveTrainingPlanSmokeTest -Ditest.live-model=true test` 通过：真实 `qwen-turbo`、`generationSource=AGENT`、`parseStatus=PARSED`、实际模型元数据已落库；本地 Chromium 同样完成 Agent 计划与对应 Trace。公网发布仍由 #89 验收。 |

## #90–#94 计划语义收敛（2026-08-20）

| 范围 | 实现与自动化证据 |
|---|---|
| #91 口语化澄清与可中断续轮 | `PlanBriefServiceTest`、`MealPlanBriefServiceTest`、`PlanBriefExtractionAgentServiceTest`、`HealthOrchestratorServiceTest`、`HealthIntentRevisionServiceTest`、`HealthSessionServiceTest`；覆盖“二四六/周一二三/周一到周三”、中文数字和点半/一刻/上午下午晚上、单起点追问、五种解释状态、Agent 候选 Java 校验、解析失败/空候选、餐食/作息/OTHER 切换与简报保留。 |
| #92 范围契约与生成隔离 | `PlanScope`、`PlanScopeGuard`、`CompositePlanGenerationService`、`MealPlanGenerationService`；`PlanScopeGuardTest`、`WeeklyPlanServiceTest`、`WeeklyPlanComposerServiceTest`、`MysqlReviewedDbPlanMealIntegrationTest`、`MysqlTransactionIntegrationTest`。EXERCISE/MEAL/COMPOSITE 分别约束项目类型，综合计划分别生成后确定性合并；任何新计划无 ROUTINE。 |
| #92 迁移与旧数据清理 | `V15__plan_scope_and_test_data_cleanup.sql` 按 `weekly_plan_item → weekly_plan_version → weekly_plan` 外键顺序清理旧测试计划并增加 scope CHECK；`V16__plan_generation_source_length.sql` 扩展生成来源；`PlanScopeMigrationTest` 固定 SQL 顺序/范围，真实 MySQL 门控在 `diet_db_itest` 验证 V1-V16、迁移幂等、范围写入、ACTIVE 唯一约束与失败回滚。 |
| #92 故障路径 | 训练 Plan Agent 超时/非法输出、候选为空、写入异常和激活重检由计划生成/事务测试覆盖；失败响应不会留下 `weekly_plan`、版本或项目半成品。 |
| #93 详情优先布局 | `frontend/assets/js/pages/plans.js`、`frontend/assets/css/app.css`、`frontend/tests/chat-plan-actions.test.mjs`；详情占主宽度，历史计划收敛为选择器，COMPOSITE 提供全部/训练/餐食筛选，保留键盘可达选择、筛选、项目和详情编辑抽屉。 |

### 本轮自动化门槛

| 命令 | 结果 |
|---|---|
| `node --test frontend/tests/*.test.mjs` | 20/20 通过 |
| `mvn test` | 662 tests：621 通过、41 环境门控跳过、0 failures/errors |
| `mvn test -Ditest.mysql=true` | 662 tests：658 通过、4 个独立门控跳过、0 failures/errors；真实 MySQL 场景为事务 18 + reviewed 计划/餐食 7 + reviewed readers 12 |
| `mvn test -Ditest.mysql=true -Ditest.qdrant=true` | 662 tests：661 通过、1 个显式 live-model 门控跳过、0 failures/errors；Qdrant adapter 2 + gate smoke 1 实际执行 |
| `mvn -q -Dtest=LiveTrainingPlanSmokeTest -Ditest.live-model=true test` | 1/1 通过；本地配置可用时完成真实模型训练计划生成，未记录凭证 |
| `docker compose config --quiet` | 通过 |
| JavaScript 语法检查 / `git diff --check` | 通过 |

### 真实浏览器与 #89 交接

- 真实 Chromium（ego-browser task space 38）使用本地同源入口 `http://localhost:8090/#/chat`、`#/profile`、`#/plans`、`#/admin/traces` 完成：口语训练简报 → 餐食话题切换 → 返回继续 → 缺档案往返 → 训练/餐食/综合分别确认与生成 → 计划查看/激活/编辑抽屉 → Trace。综合计划筛选结果为全部 24、训练 3、餐食 21；Trace 显示 `PLAN_PERSISTED` 与 `planScope=COMPOSITE`。
- 计划页在 1710、1024、768、414、390、375、320px 宽度检查 `clientWidth=scrollWidth`，无页面横向滚动；长名称、1/3/21/29 项目、状态标签、周日项目和操作控件均未叠加或裁切。桌面/平板/移动交互结果记录在 `docs/frontend-browser-acceptance.md`，截图存于 `docs/evidence/issue-90/`。
- 故障路径的 Agent 解析失败、Plan Agent 超时/非法输出、候选为空、数据库失败分别由新增单测和真实 MySQL 回滚/Guard 集成测试覆盖；没有用普通单测替代 MySQL 事务验证。
- #90 及 #91–#94 的本地实现、测试和浏览器证据完成后，#89 具备开始条件；本轮只留下交接说明，不登录云服务器、不改 DNS/TLS、不上传镜像、不运行远端 Compose、不配置云端凭证、不访问生产数据库。
