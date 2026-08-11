# 健康 agent 重构 — 地图

> Wayfinder 地图。本文件是索引，不是仓库：每个决策只存在于其 ticket 中。

## Destination

把当前"饮食 agent"扩展重构为**健康 agent** 并形成可直接交付实现的完整中文规格：统一对话入口覆盖饮食/健身/作息三品类，为一般成年用户生成可解释、可追溯、可持久化的**每周个人计划**（作息+餐食+推荐摄入区间+训练）；新增健身动作浏览页、餐食浏览页和我的计划页，形成类型明确的偏好闭环；规格包含与真实缺口对应的后端工程、质量保障和部署章节。终点 = 一份实现会话可直接执行、面向"Java 后端 + agent 开发"双定位简历的规格文档。

## Notes

- 全部规格/评论/代码注释用中文（AGENTS.md 约定）。
- 原始时间预算为 1-2 周；复核后拆成两级门槛：31-32 先形成可面试演示的 Agent 垂直闭环，31-36 全部通过才是完整 MVP。目标仍是公开部署，但不能用资源数量和页面数量替代主流程完成度。
- 现有架构尽量**延伸而非重写**：intent→slot→clarify→recommend/plan→risk→persist 状态机、`diet_slot_option` 字典、`meal_item` 标签 JSON、单页 vanilla 前端。
- 已确认事实（charting 阶段调研）：健身数据集 = hasaneyldrm/exercises-dataset，1,324 动作，MIT 协议（媒体需保留 Gym visual 署名），含中文步骤说明与 GIF/缩略图，10 个部位、12 类器材，25% 为徒手动作。
- 工作 tickets 需调用技能：/grilling、/domain-modeling、/prototype、/research。
- **前序会话交接**（2026-08-09，`handoff-health-agent-frontend-split-rag-2026-08-09.md`）：前端方案 B 已拍板（见 07）；后端技术与 RAG 方向已有代码级核实证据（docs/research/frontend-backend-split.md、comparable-projects.md）。其 Flyway→方案B/CI→RAG 顺序仅作初稿，本次审计新增的健康档案、计划模型与安全校验必须先进入关键路径。
- **独立 GitHub 仓库已确认**：https://github.com/Leewwp/health_agent（含 pom/src/main/AGENTS.md）→ GitHub Actions CI 可行、部署拉取来源。父级备份仓库的 git 约束仅适用于本地工作区；独立仓库内无此约束。
- **已定原则（2026-08-09）**：自动化测试覆盖多品类编排、计划规则/风险校验与混合检索，其他接口和页面走冒烟；前端方案 B 实施时删除 static/ 双副本。
- **产品边界（2026-08-09）**：第一版只面向 18 岁以上的一般成年人；未成年人、孕期、进食障碍、急性伤病和需要医疗干预的慢性病诉求由风险守卫拦截，不生成具体计划。
- **数值原则（2026-08-09）**：推荐摄入量来自最小健康档案和明确公式，系统展示计算依据与估算性质；LLM 只解释和组合，不自行编造数值。
- **检索术语（2026-08-09）**：餐食混合语义召回称 RAG；作息采用结构化事实检索与来源引用；历史反馈采用偏好记忆与重排，三者不混称 RAG。
- **部署原则（2026-08-09）**：只部署一个后端实例；会话状态使用本地串行守卫，重复提交通过 requestId 幂等控制，不使用 Redis 分布式锁。匿名演示身份已经确定，具体托管配置、跨域安全和运维细节由 27 号票收敛。
- **前端原则（2026-08-09）**：不引入 Vue/React/Vite；保留 vanilla hash 路由，使用原生 ES Modules 按页面、领域和共享 UI 拆分。
- **计划交互下限（2026-08-10）**：周计划支持草稿、激活、历史版本；首版可在草稿中移动已有项目的日期/时间并保存备注，ACTIVE 编辑先复制为新 DRAFT；资源替换和自由表格编辑延期。
- **Vercel 事实（2026-08-09）**：适合独立静态前端，不适合将当前 Spring Boot/MySQL/Redis 整栈作为生产默认方案；后端仍需传统容器、PaaS 或 VM。只迁前端不会明显释放 4C4G 服务器资源。
- **训练能力边界（2026-08-10）**：首版用经过审核的小型动作子集完成浏览、推荐和计划闭环，后续按同一规则扩量；自动计划只使用 `plan_ready` 动作。LLM 不决定训练剂量与安全结论。
- **面试 MVP 安全边界（2026-08-10）**：安全能力只实现少量版本化规则、固定文案、三阶段 Guard 和确定性降级，用于展示 Agent 边界；不建设真实生产级医疗安全、规则后台、专业审核或长期安全运营。
- **[资源规模与媒体边界（ADR-0009）](../../docs/adr/0009-reviewed-resource-subset-and-explicit-media-state.md)**：完整 MVP 只要求可重建的小型审核子集；无明确媒体许可时清除外链并使用稳定无图状态，不让媒体或全量扩充阻塞 Agent 主流程。
- git 注意：本仓库位于父级备份仓库 `/Users/pp/Desktop/file` 内，**只允许 stage `code/project/health-agent/...` 路径**。因此研究子代理不建 `research/<name>` 分支，改为直接写入 `.scratch/health-agent/research/<topic>.md`（对 wayfinder 技能中"throwaway branch"的本地化偏差）。
- **面试导向复核（2026-08-10）**：只保留能由代码、测试、Trace 或部署证据证明的技术亮点；Agent 数量、RAG 效果和生产能力均不得超出实际证据。

## Decisions so far

<!-- 索引：每行一条已关闭 ticket：名称（链接）+ 一句话要点。 -->

- [01 作息信息源调研](issues/01-routine-sources-research.md) — 15 个权威来源核验：睡眠时长（成人 7-9h、65+ 7-8h）、咖啡因睡前 6h 截止、午睡 20-30min 等可落库；**训练时段无普遍最优**（按昼夜节律匹配，禁止写死）；cssm.org.cn 为冒充域名需排雷。产出 research/routine-sources.md。
- [02 餐食数据集调研](issues/02-meal-dataset-research.md) — 无单一数据集满足全部标准（中文数据集均限研究用途）；**组合方案**：Food.com（CC0，营养+分类）作为离线候选池 + USDA FDC（公有领域校准）+ 自建中文菜名映射表；正式库按字段、来源和安全门槛筛选，媒体不合格时使用无图状态。已下载并核验 Food.com v2，详见 [真实性核验记录](../../docs/research/kaggle-foodcom-dataset-verification.md)，无需重复下载。
- [07 前端页面形态与项目定位](issues/07-frontend-form-decision.md) — **方案 B 已拍板**：前后端分离（static/ → `frontend/`）+ Nginx 同源反代 `/api/`，vanilla hash 路由零改写，Docker Compose 部署。论证见 docs/research/frontend-backend-split.md。
- [06 后端技术栈展示方案](issues/06-backend-tech-showcase.md) — 只保留对应真实缺口的 Flyway、Redis 缓存、Compose、CI、OpenAPI、Actuator 与核心测试；会话锁和 requestId 幂等均为单实例本地实现；排除 MQ、Redis 分布式锁及 trace 异步化。
- [11 Vercel 部署适配调研](issues/11-vercel-deployment-research.md) — Vercel 只推荐托管静态前端；Spring Boot/MySQL/Redis 使用外部常驻运行环境，不为 Vercel 重写后端，也不以 Beta Java 容器作为生产方案。
- [14 训练计划能力边界与依据调研](issues/14-training-plan-evidence.md) — 动作数据集不含处方字段；自动计划按 `plan_ready` 条件生成一般成年人入门活动计划，LLM 仅解释校验后的结果。
- [03 健身动作展示原型](issues/03-exercise-display-prototype.md) — A/B/C 三种页面形态已完成原型和真实浏览器验收；采用动作卡片、统一详情抽屉、收藏动作池和可展开训练套组。
- [17 类型化资源与偏好闭环](issues/17-typed-preference-loop.md) — 采用类型化资源身份和统一反馈事件；现有意图 Agent 提取明示偏好，确定性过滤/重排消费反馈，首版不做复杂画像。
- [18 前端模块边界与页面信息架构](issues/18-frontend-module-information-architecture.md) — 保留 vanilla hash 路由，拆分原生 ES Modules；餐食、动作、对话和计划复用详情抽屉与类型化反馈控件，admin 路由隔离。
- [22 健康 Agent API 与旧饮食兼容契约](issues/22-health-api-and-legacy-contract.md) — 新增 `/api/v1/health/**` 统一健康接口，保留 `/api/v1/diet/**` 兼容入口；类型化资源、Cookie 身份、requestId 幂等和统一错误结构已冻结。
- [23 健康数据 Schema 与迁移契约](issues/23-health-data-schema-and-migration.md) — 采用旧库 Flyway 基线加增量迁移；领域表、档案/计划版本、类型化反馈和 Trace 幂等落点已冻结。
- [24 量化目标与周计划不变量契约](issues/24-quantified-target-and-plan-invariants.md) — 冻结 Mifflin-St Jeor 能量区间、活动系数、周计划日历不变量、校验结果分类和档案版本过期语义。
- [25 健康风险规则矩阵与用户文案](issues/25-risk-rule-matrix-and-copy.md) — 采用少量版本化规则、三档结果、三阶段 Guard 和固定降级文案；明确不建设生产级安全体系。
- [26 Agent、Embedding 与确定性降级契约](issues/26-agent-contracts-and-degradation.md) — 使用 Agent 运行接口隔离 AgentScope，保留受约束的多角色职责，固定夹具和真实模型双适配；失败立即确定性降级，Embedding 仅用于餐食召回。
- [27 匿名身份与部署安全验收契约](issues/27-identity-and-deployment-security-contract.md) — 采用 HMAC Cookie、同源 Nginx、环境变量配置、token 隔离 admin 和单实例健康检查，不建设真实账号与生产安全平台。
- [30 餐食与动作资源就绪及来源审计](issues/30-resource-readiness-and-provenance-audit.md) — 核验离线餐食候选和动作来源；正式资源尚未就绪，仍需 ETL、中文映射、媒体/许可和 `plan_ready` 审计。
- [28 发布证据与验收门槛](issues/28-release-evidence-and-acceptance-gate.md) — 采用少量核心自动化、接口/浏览器冒烟和部署检查；31-32 是面试可演示门槛，31-36 是完整 MVP 门槛，硬约束命中率要求 100%。
- [04 跨品类意图与模式体系设计](issues/04-intent-mode-design.md) — 采用 domain/task/riskFlags/phase 正交模型，综合计划使用 COMPOSITE + PLAN。
- [05 健身 slot 字典设计](issues/05-fitness-slot-dictionary.md) — 训练部位、器材、训练目标独立建模，训练目标与饮食 healthGoal 分离。
- [09 餐食表结构与数据导入设计](issues/09-meal-item-schema-import.md) — 从 1000 条离线候选中按字段、来源和安全门槛筛选审核子集，媒体不合格时清除外链并使用无图状态。
- [12 用户身份与数据隔离方案](issues/12-identity-data-isolation.md) — 第一版采用匿名演示身份，服务端 Cookie 取代可编辑的 X-User-Id。
- [13 健康档案与量化目标设计](issues/13-health-profile-targets.md) — 使用最小健康档案和确定性估算公式，计划保留生成时档案快照。
- [08 作息 slot 字典与事实表设计](issues/08-routine-slot-facts-design.md) — 时间和时长使用结构化类型，作息事实带来源查询，不使用向量 RAG。
- [10 RAG 技术选型与落点设计](issues/10-rag-design.md) — 首版只用餐食混合召回 RAG；训练/作息指南检索延期，Embedding 失败时降级。
- [15 多品类编排模块边界](issues/15-multidomain-orchestration-boundaries.md) — 复用餐食链路的状态机模式，但将餐食、健身、作息封装为独立领域模块，由周计划组合器整合。
- [16 每周个人计划模型与生命周期](issues/16-weekly-plan-lifecycle.md) — 计划采用草稿、激活、归档和版本快照，首版支持草稿内移动日期/时间，不做资源替换。
- [19 健康风险策略与计划校验](issues/19-health-risk-plan-validation.md) — 采用三档风险策略，候选、计划组合和 LLM 输出后分别校验。
- [21 部署资源预算与交付流水线](issues/21-deployment-resource-delivery.md) — 静态前端独立托管，单实例后端与隔离数据层，CI 构建和可回滚发布。
- [20 检索、计划与降级质量验收基线](issues/20-quality-acceptance-baseline.md) — 核心逻辑自动化测试，接口和页面冒烟；首版验收餐食混合 RAG 和结构化作息事实，指南证据引用验收延期。

## Not yet specified

当前没有阻塞实现交接的未锐化决策。29 号 Java 21 基线已由提交 3547bc7 接纳并完成验收（POM `maven.compiler.release=21` + clean build + javap major 65 + `mvn test` 通过）；正式资源导入、重建报告和发布后的持续维护属于实施票据及后续运营事实，不再作为本地图上的决策雾。

## Implementation plan

完整实施顺序、开发测/用户侧边界、数据门槛、RAG、动作计划资格和验收标准见 [docs/health-agent-implementation-plan.md](../../docs/health-agent-implementation-plan.md)。

## Implementation handoff

- 正式规格：[健康 Agent MVP 正式规格](spec.md)，是实现阶段唯一真源。
- 构建前置：[29 Java 21 构建基线核验](issues/29-java21-build-baseline.md)，已完成（提交 3547bc7 接纳 POM 修正并通过 clean build，见票内验收记录）。
- 面试适用性复核：[Agent MVP 面试适用性复核](../../docs/agent-mvp-suitability-review.md)。
- 垂直实施入口：[31 MVP 基础设施与旧饮食兼容](issues/31-mvp-foundation-and-legacy-compat.md)、[32 Agent 运行接口与健康聊天垂直闭环](issues/32-agent-runtime-and-health-chat-vertical-slice.md)、[33 审核资源、浏览 API 与餐食 RAG](issues/33-reviewed-resources-browser-api-and-meal-rag.md)、[34 健康档案、周计划与风险校验](issues/34-health-profile-plan-and-risk.md)、[35 前端模块与用户页面](issues/35-frontend-modules-and-user-pages.md)、[36 核心验收、部署与运行手册](issues/36-core-acceptance-and-delivery.md)。
- 当前状态：规格和文档完成；29/31/32 已通过验收（Java 21 基线、Flyway 双路径、基础设施冒烟、65 个自动化测试与 fixture 模式三品类闭环），达到"面试可演示"门槛；33 号已通过验收（ETL 可重跑、292 餐食/30 动作/15 事实审核子集、浏览 API、hybrid RAG 与降级、Recall@3 评估记录）；34 号已通过验收（健康档案与能量区间、周计划生命周期与版本快照、三阶段 Guard、计划 API，全量 192 个自动化测试 + fixture 模式真实启动冒烟，见 34 号票验收记录）。
- **交接审计修复批次（2026-08-10）**：34 号完成后对 HEAD 67cd909 做了完整 code review，修复方案已发布为 GitHub issue 39-45（Leewwp/health_agent），本地只维护地图索引，票内容以 GitHub 为准。实施顺序：#39 事务化与激活元数据持久化 → #40 统一正式资源 Provider → #41 类型化反馈迁移与健康反馈 API → #42 版本生成依据与 ACTIVE 不变量 → #43 聊天稳定性（默认会话/资源历史/作息查询/分页溢出）→ #44 风险规则目录与文档验收证据；#37（35 号前端）在 #40/#41 契约稳定后验收，#38（36 号总验收）作为最终门槛。
- **审计批次进展（2026-08-11）**：#39-#44 主代码与自动化证据已全部落地：事务化 + 行锁 + 激活专用更新、双模式资源 Provider、V5 类型化反馈 + 健康反馈 API、V6 版本生成依据 + ACTIVE 生成列唯一约束、默认会话/ROUTINE 查询/分页 400、统一 RiskRuleCatalog；全量 269 个自动化测试通过（`mvn test`），V1-V6 迁移已在真实 MySQL 8.4 上验证（含重复 ACTIVE 清理、反馈/版本回填、唯一约束）。剩余：#44 中真实 DashScope 受约束冒烟与 39 号事务回滚的真实 MySQL 集成验证、#37 前端验收、#38 最终总验收（干净环境 + 浏览器流程 + 部署健康检查）。
- **真实 DashScope 冒烟 + 浏览器验收（2026-08-11）**：#38 验收发现并修复 4 处真实缺口（见下方提交）：① agentscope 1.0.11 聚合 jar 未传递 dashscope-sdk-java 依赖，真实模式运行 ClassNotFoundException → pom 显式补 2.22.9；② 专属 MaaS 空间只开放 OpenAI 兼容聊天端点，agentscope 原生路径 404 → 新增 OpenAiCompatibleChatModel（diet.llm.openai-compatible=true 切换，JDK HttpClient + Jackson，无新依赖），embedding 走原生端点（diet.embedding.base-url 独立配置）；③ 审核子集缺高热量主菜（原最大 995 kcal，日上限 2364 < 2400 导致计划激活死锁）→ MealPlanPicker 改全局三槽组合搜索 + 补充 3 道 1117-1119 kcal 午餐/晚餐主菜（种子 295 道）；④ createDraft 缺省 sessionId 时 trace 写入失败 → 复用 HealthSessionService 匿名 HMAC 默认会话。另修复前端 buildDays/nextMonday 的 toISOString 时区错位（+08:00 下星期标签偏一天）。**真实证据**：qwen3.8-max/qwen3.7-flash/qwen3.7-text-embedding 专属空间模型实跑；聊天推荐/澄清/反馈、档案、周计划草稿 OK→激活（ACTIVE 唯一约束归档旧计划）、浏览 API、分页 400 全部通过；292+3 餐食向量真实生成；RAG 评估 hybrid Recall@3=0.385 vs structured=0.380（硬约束 100%、零降级，提升幅度小如实记录）；浏览器（ego-browser）全流程跑通：聊天真实推荐 + 收藏反馈落库、档案保存展示能量区间、计划七天视图（日期标签修复后 8/17-8/23 正确）+ 激活"已激活 v2"、餐食 295/动作 30 浏览筛选。全量 276 个自动化测试通过。剩余：#37 前端剩余开发与验收（frontend/ 目录工作树中未提交）、干净环境 + 部署健康检查、39 号事务回滚真实 MySQL 集成验证。

## Out of scope

- **睡眠作为独立第三品类**（并入"作息"，Q2 决策）。
- **瑜伽作为独立品类**（徒手/柔韧类动作可覆盖部分场景；健身数据集无瑜伽专门分类）。
- **医疗诊断类内容**（延续现有 HEALTH_RISK 仅做风险提示，不做治疗建议）。
- **真实生产切换与凭证操作**（规格包含部署拓扑、资源预算、CI/CD 与回滚清单；服务器登录、DNS 切换和真实密钥配置留到实施阶段）。
- **移动端 App / 小程序**（仅 Web 页面）。
- **前端构建工具链与框架迁移**（不引入 Node/npm/Vite/Vue/React）。
- **第二个后端实例、Redis 分布式锁与消息队列**（单实例部署不具备真实需求）。
- **打卡、提醒、可穿戴设备同步与长期行为追踪**（第一版只生成、激活、查看和局部调整周计划）。

## 审计结论

- 当前地图已完成决策与规格补齐，进入实现交接，不再把路线图当作唯一实现依据。
- 03 已完成原型和真实浏览器验收并进入 Decisions so far；17、18、22-28 和 30 的决策、审计和验收门槛已完成，29 号保持开放等待代码接纳和 clean build。
- `.scratch/health-agent/spec.md` 已发布为正式规格；31-36 已拆分为按依赖推进的垂直实施票据，32 号优先建立可测试的 Agent 主流程，`docs/health-agent-implementation-plan.md` 继续作为里程碑辅助说明。
