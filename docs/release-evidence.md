# 发布证据与完成门槛清单（28 号票 / 36 号总验收）

> 本文档汇总 36 号（#38）最终总验收的证据位置，对应 28 号"发布证据与验收门槛"矩阵。
> 证据类型：自动化测试、接口冒烟、真实浏览器、部署检查。日期均为 2026-08-11。

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
| 审核子集（295 餐食/30 动作/15 事实） | `ReviewedResourceSeedValidatorTest`、`ReviewedResourceSeeder` 幂等导入；`DbReviewedResourceProviderTest` |
| 浏览 API 分页边界 | `health/browse` 测试（page 超上限返回 400、size≤50）；浏览器验收第 2/3 节 |
| hybrid RAG 与结构化降级 | `health/rag` 测试；固定查询集（60 条六层，querySetVersion 1.1.0）评估见 `docs/research/meal-rag-evaluation.md`，数字以 `data/reports/rag_evaluation.json` 为准 |
| 真实 key 下 RAG 对比 | 2026-08-11 的旧 10 条查询集 DashScope 冒烟（292+3 条向量、Recall@3=0.385 vs 0.380）仅作历史背景，不作为当前效果数字；当前唯一数字口径见 `data/reports/rag_evaluation.json`（环境身份：gitCommit/querySetVersion/embedding provider/model/dimension/collection/融合权重） |

## M3：健康档案、周计划、风险校验与反馈

| 条目 | 证据 |
|---|---|
| Mifflin-St Jeor 能量区间 | `EnergyCalculatorTest` |
| 周计划不变量（版本/激活/归档） | `WeeklyPlanServiceTest`（20 个）+ `PlanValidationServiceTest` + `WeeklyPlanComposerServiceTest` |
| 事务化 + 行锁 + ACTIVE 唯一约束 | 真实 MySQL 集成验证：`src/test/java/com/diet/integration/MysqlTransactionIntegrationTest.java`（18 个用例，`-Ditest.mysql=true` 门控）：V1-V9 干净库迁移、saveProfile/createDraft/activate 写入失败无半成品、并发激活唯一成功、激活后档案版本/能量区间与快照一致、档案版本号连续唯一、MySQL 不可用快速失败 |
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
