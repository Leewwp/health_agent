# 发布证据与完成门槛清单（28 号票 / 36 号总验收）

> **2026-08-28 阶段口径**：面试 MVP 的本地验收范围与长期 Future Work 已单独整理在 [docs/mvp-phases.md](mvp-phases.md)。本清单中 M0-M4 的完整 MVP 门槛不再作为面试演示的前置条件；面试验收优先记录推荐、档案回跳和三类计划主流程。

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
| 资源基线（295 餐食/1324 动作目录/15 事实；dev 启动后完整动作目录自动取得 plan_ready） | `ReviewedResourceSeedValidatorTest`（30 条审核种子）以及 `ReviewedResourceSeeder`/`LocalMediaCatalogSeeder` 幂等导入；`DbReviewedResourceProviderTest`、`MysqlReviewedReadersIntegrationTest` |
| 浏览 API 分页边界 | `health/browse` 测试（page 超上限返回 400、size≤50）；浏览器验收第 2/3 节 |
| hybrid RAG 与结构化降级 | `health/rag` 测试；固定查询集（60 条六层，querySetVersion 1.1.0）评估见 `docs/research/meal-rag-evaluation.md`。当前报告已记录 Structured/Vector/Fused 数量、向量状态、阶段延迟，并区分 `REAL_HYBRID`、`PARTIAL_HYBRID`、`FALLBACK_ONLY`。 |
| 真实 key 下 RAG 对比 | 2026-08-27 本地 MySQL + Qdrant 1.17.0 实跑：295/295 条索引、60/60 条零降级，`REAL_HYBRID`；Recall@3 Structured 0.2646 / Hybrid 0.2716（+0.0070），硬约束命中率均为 1.0，Hybrid P95 为 247.361 ms（Structured 9.121 ms）。结果详见 `data/reports/rag_evaluation.json`；单次运行不能宣称稳定效果提升。 |
| 两段式语义挑战 | 2026-08-27 `semantic-challenge-v1`：12 条人工复核查询，Structured/Hybrid/TwoStage 均零硬约束违规；Recall@3 分别为 0.0333/0.1000/0.0333，平均延迟 5.68/212.20/83.70 ms。原始报告见 `data/reports/semantic_challenge_v1.json`；仅作为路由实验依据，不替代 60 条主评测。 |
| MiniMax 对照 | 2026-08-27 使用 `embo-01` 1536 维独立 collection，60/60 条主查询零降级；Structured/Hybrid Recall@3 为 0.2646/0.2776（+0.0131），P95 延迟 2.75/479.83 ms，硬约束命中率均 1.0。原始报告见 `data/reports/rag_evaluation_minimax.json`；单轮结果不能宣称稳定收益。 |

## M3：健康档案、周计划、风险校验与反馈

| 条目 | 证据 |
|---|---|
| Mifflin-St Jeor 能量区间 | `EnergyCalculatorTest` |
| 周计划不变量（版本/激活/归档） | `WeeklyPlanServiceTest`（20 个）+ `PlanValidationServiceTest` + `WeeklyPlanComposerServiceTest` |
| 事务化 + 行锁 + ENABLED 唯一约束 | 真实 MySQL 集成验证：`src/test/java/com/diet/integration/MysqlTransactionIntegrationTest.java`（18 个用例，`-Ditest.mysql=true` 门控）：V1-V20 迁移、范围化生成与启用失败无半成品、并发启用唯一成功、激活后档案版本/能量区间与快照一致、档案版本号连续唯一、MySQL 不可用快速失败 |
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
| 训练计划需求简报（无确认阶段） | `PlanBriefServiceTest`、`HealthSessionServiceTest`、`HealthOrchestratorServiceTest`；独立 `planBrief` JSON、PLAN 上下文合并/纠正和缺档案保留简报；简报完整即提供开始生成/补充，服务端重读当前完整简报，无独立确认阶段（ADR-0016）；V13 训练目标标签迁移兼容 fresh/legacy DB |
| 受约束 Agent 训练计划 | `TrainingPlanGenerationServiceTest`、MySQL 事务门控；服务端重读当前完整简报（无独立确认阶段，ADR-0016），审核 `plan_ready` 候选白名单，确定性 Guard/fallback，V14 生成来源/元数据和 requestId 幂等；fixture 场景完成草稿查看、七日安排和激活 |
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

## 本轮产品体验增强验收（2026-08-20）

- 真实浏览器入口：`http://127.0.0.1:8092/#/chat`、`#/plans`；Spring Boot `8082` + Nginx `8092`，健康检查返回 `UP`。
- 桌面 `1710×983`：清淡晚餐口语请求在一轮内识别并推荐；“换一批”通过聊天 API 提交候选排除并返回新资源；收藏、减少推荐、撤销及反馈失败回滚通过；计划简报生成草稿、详情抽屉、激活、设为当前、取消当前、编辑副本和草稿删除确认通过。
- 移动 `390×844`：聊天消息区独立滚动；计划详情抽屉中长来源版本号可换行；文档、主体和抽屉内容均无横向溢出。
- 详细交互记录与截图见 `docs/frontend-browser-acceptance.md` 的“产品体验增强收尾验收”段及 `docs/evidence/product-refinement/`。

### 目录详情与单次推荐边界复核（2026-08-20）

- 动作目录详情新增独立目录查询：待审核动作不再因正式 Provider 的 `APPROVED` 过滤返回 404；本地接口 `GET /api/v1/health/exercises/1531` 返回 200，且保留 `reviewStatus=PENDING`、`planReady=false`。
- 单次动作推荐改用完整目录候选；正式审核资源列表和周计划候选仍分别沿用 `APPROVED` 与 `plan_ready` 白名单，dev 启动时由确定性资格服务将完整目录中字段齐全的动作扩展到计划边界。
- 替代推荐候选耗尽时新增可选 `resultCode=CANDIDATES_EXHAUSTED`，普通无候选响应保持 `resultCode=null`，并继续提供显式放宽/重复操作。
- `mvn test`：686 tests，0 failures，43 环境门控跳过；`mvn test -Ditest.mysql=true`：686 tests，0 failures，4 独立门控跳过；`node --test frontend/tests/*.test.mjs`：20/20；`git diff --check` 通过。

## 本地开发收尾（2026-08-22）

> 本节数字为 2026-08-22 工作树基线；当前工作树的最新基线见下方 2026-08-28 审计，前面按日期记录的历史验收数字保留作当时证据，不作为当前测试总数。

- 当前工作树自动化结果：`mvn -DskipTests compile` 通过；定向 Java 回归 77/77；`node --test frontend/tests/*.test.mjs` 25/25；`mvn test` 共 702 个测试，无失败，其中 658 通过、44 个环境门控跳过。
- 门控复核：`mvn test -Ditest.mysql=true` 为 698 通过、4 跳过（Qdrant 3、live-model 1），40 个真实 MySQL 场景全部执行（事务 18、reviewed 计划/餐食 7、reviewed readers 15）；`mvn test -Ditest.mysql=true -Ditest.qdrant=true` 为 701 通过、1 跳过（live-model）。未配置外部模型凭证，因此没有执行成功的 live-model smoke。
- 健康聊天回归覆盖：统一中文槽位标签、后端确认摘要不泄漏 `mealTime/mood/cuisine/taste/convenience`，并识别尽快/便利店速食/胃口不好/素食/酸甜等口语及多槽位合并。
- 计划页真实 Chromium 验收入口：`http://127.0.0.1:18092`，桌面 `1710×983` 与移动 `390×844`；名称标题/编辑/保存/取消、空名称中文错误、保存失败保留输入、未保存离开与取消项目编辑确认、空日期单一“添加项目”入口和长名称省略均通过，两个视口页面宽度均等于 390/1710。
- 本地收尾未执行云端发布、DNS/TLS、公网验收、Issue 关闭或分支删除；#89 仍需云端交付闭环。

## 餐食 RAG 语料清单与嵌入对照（2026-08-27）

> 本节是 2026-08-27 工作树基线；当前工作树的最新基线见下方 2026-08-28 审计，按日期记录的历史验收数字不作为当前测试总数。

- 对应提交 e594a0d（两段式餐食召回路由 + MiniMax 嵌入适配器 + `current-corpus-v1` 语料 manifest 冻结）与 046b5f7（MCP 工具测试对 keep-alive 连接竞态重试）；RAG/MiniMax/语义挑战效果证据见上方 M2 表 2026-08-27 各行，后续任务入口为 `docs/research/rag-status-and-next-steps.md`。
- 当日工作树自动化结果：`mvn test` 共 715 个测试，无失败，其中 671 通过、44 个环境门控跳过；`mvn test -Ditest.mysql=true` 为 711 通过、4 个独立门控跳过（Qdrant 3、live-model 1），40 个真实 MySQL 场景全部执行（事务 18、reviewed 计划/餐食 7、reviewed readers 15）；`mvn test -Ditest.mysql=true -Ditest.qdrant=true` 为 714 通过、1 个跳过（live-model）。`node --test frontend/tests/*.test.mjs` 25/25 通过。未配置外部模型凭证，因此没有执行成功的 live-model smoke。

## 第一阶段面试 MVP 主流程审计（2026-08-28）

| 范围 | 证据 |
|---|---|
| 已验证核心 | 动作与正式餐食显式槽位执行同字段 OR、跨字段 AND；追加值经目录验证并跨轮持久化；训练 Guard 覆盖全部指定日期；独立餐食/训练启用计划可提供当天软上下文；详情抽屉忽略过期异步响应。 |
| 自动化门槛 | `mvn test` 729 项 0 failures（725 通过、4 环境门控跳过）；`mvn test -Ditest.mysql=true` 725 通过、4 个独立门控跳过，40 个真实 MySQL 场景全部执行；`node --test frontend/tests/*.test.mjs` 31/31。 |
| 浏览器审计 | ego-browser task space 8，`http://localhost:8092` / 后端 `8082`：严格无候选追加后保留原槽位，动作详情返回步骤/肌群/器材/难度/来源，档案保存 v3→v4 并出现“回到聊天”；餐食 295 条、动作 1324 条正式浏览可用。桌面依次完成训练 3 项、餐食 21 项、综合 24 项的简报确认、生成、草稿确认和启用，最终仅综合 24 项保持启用；训练候选源表复核为胸/哑铃/进阶/增肌。`390×844` 下已启用综合计划无横向溢出（页面宽与 `scrollWidth` 均为 390），导航、状态、操作和七日切换未重叠。生成来源提示已复现并修正为“规则降级 / 餐食规则组合 / 综合规则合并”。 |
| 负向与未完成项 | 浏览页和计划选择器仍为单选，餐食简报仍未保存“中餐和西餐”；“减脂+全身+哑铃+进阶”五日示例因无严格候选正确拒绝，此前“五日成功”记录不作为当前证据。训练 `bodyParts` 当前包含辅助肌群，“练胸”可能召回主部位为背、胸仅为辅助肌群的动作，需先明确产品口径再改召回合同。五日严格候选复验、移动端完整交互、浏览/计划选择器多选和餐食简报多值偏好仍见 `docs/mvp-phases.md` 第二轮优先收口；#102 已补齐详情异步竞态契约测试及 picker 详情/反馈修复。GitHub #101 已关闭，但本地历史审计票仍以 `claimed` 保留未完成项记录，不代表 #102 的实现状态。 |
