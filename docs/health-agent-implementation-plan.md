# 健康 Agent 实施计划

> 当前交付补充（2026-08-19）：面试演示核心流程 #85-#87 已在本地完成。训练计划使用独立 `planBrief`、审核 `plan_ready` 候选、确定性 Guard/fallback 和 V13/V14 迁移；Trace 工作台复用现有 `diet_request_trace` 并展示可脱敏诊断信息。fixture/fallback、双门控、真实 `qwen-turbo` smoke 和本地浏览器 Agent 成功链路均已通过；公网部署仍由 #89 验收。

## 1. 目标与范围

将现有饮食推荐服务扩展为覆盖饮食、健身、作息的健康推荐助手，同时保留现有饮食 API 的兼容行为。首版用户侧交付以下闭环：

- 饮食、健身、作息单次推荐；
- 最小健康档案与确定性量化目标；
- 三领域综合周计划；
- 周计划 `DRAFT -> UNENABLED -> ENABLED -> HISTORY`、版本快照和历史版本；
- 在可编辑计划中批量新增、替换、删除、移动餐食和动作项目，并保存备注与确定性训练处方；
- 独立资源收藏，以及喜欢、不喜欢和采纳等类型化反馈；
- 餐食候选的混合 RAG；
- 风险提示、拒绝具体计划和结构化降级。

首版不做：

- 训练/作息指南检索；
- 自由编辑营养值和作息规则；
- 拖拽资源后自动调用 LLM 重生成整周计划；
- 复杂偏好衰减和真实账号体系。

## 2. 总体边界

外层使用 `HealthOrchestrator`，负责会话生命周期、身份、幂等、意图识别、澄清、领域路由、风险汇总和持久化。领域模块只暴露领域结果：

- `MealModule`：封装现有餐食槽位、结构化过滤、混合召回、排序和餐食响应；
- `ExerciseModule`：动作浏览、动作筛选、难度提示、训练片段和动作说明；
- `RoutineModule`：时间区间、作息事实和作息片段；
- `WeeklyPlanComposer`：组合七天计划，校验时间冲突、营养区间、过敏、恢复要求和风险状态；
- `SessionExecutionGuard`：封装单实例会话串行与 `requestId` 幂等，避免锁和幂等实现泄漏到业务模块。

`SlotBundle`、`MealPlanService`、`MealRankService` 和饮食 Prompt 保留在 `MealModule` 内部，不扩展成健康万能对象。

### Agent 模块与外部 seam

新增内部 `AgentContractModule`，统一封装 Prompt/契约版本、角色 DTO、超时、JSON 解析、Schema 和候选 ID 校验、Trace 及确定性降级。外部模型依赖只通过小型 `AgentInvoker` 接口进入：

- `AgentScopeInvoker` 调用真实 AgentScope/DashScope；
- `FixtureAgentInvoker` 为自动化测试和离线演示提供版本化固定输出。

业务模块和测试不直接获取 `ReActAgent`。这两个适配器代表真实变化点：一个证明外部模型集成，一个保证无 API key 时仍可复现状态机、校验和降级。

四类在线角色沿用现有职责，但 `ClarifyAgent` 只改善措辞，模板追问就是完整降级；`EvaluationJudgeAgent` 仅作为离线补充观察。面试和 README 统一称为“确定性编排的多角色 Agent 工作流”，不宣称自治规划或自主工具调用。

## 3. 发布拓扑

### 开发测

- 使用明确的 `dev` profile、本地数据库和独立数据；
- 允许本地调试身份适配器，保留 Trace、评估、导入和调试接口；
- API key 只从本地环境变量或未提交的环境文件注入；
- 可使用真实 embedding/LLM，也可使用固定测试 adapter 验证无外部依赖链路。

### 用户侧

- 使用明确的 `prod` profile、独立数据库、独立密钥和 HttpOnly Cookie 匿名身份；
- 不接受客户端 `X-User-Id` 作为数据归属依据；
- 仅开放聊天、浏览、健康档案、计划和反馈等用户体验接口；
- Trace、评估、数据导入和调试接口从用户入口隔离，并由服务端鉴权或禁用；
- 缺少生产密钥、session secret 或数据库凭证时启动失败；
- 开发测不能连接用户侧数据库，用户侧不能运行开发身份模式。

身份抽象必须先于健康档案和计划接口落地。它是生产发布门槛，但不阻塞领域模块在开发测并行开发。

## 4. 数据与 RAG

### 餐食数据

当前 `data/meal/processed/healthy_recipes_1000.csv` 只作为离线候选池，不直接导入业务表。正式入库采用质量门槛，不强制导入固定数量：

- 唯一来源 ID、中文展示名和别名；
- 食材、步骤、份量和营养值；
- 餐次、菜系、口味、健康目标等标签；
- 过敏原审核状态、来源记录和媒体许可状态；
- 明确的媒体状态；有许可时使用图片，没有许可时使用稳定无图状态。

图片不是推荐和 RAG 成立的前置条件。Food.com 外部 URL 不能仅凭存在 URL 就视为合规；无法确认许可或可访问性的资源仍可作为文本餐食入库，但必须清除外链并标记为无图，不能显示或再分发该媒体。

### 个人资源收藏

收藏状态独立存储在 `health_resource_favorite`，身份键为 `(user_id, resource_type, resource_id)`；餐食、动作浏览页、推荐卡片和计划资源选择器共用该集合。`recommend_feedback` 继续保存喜欢、不喜欢、采纳等反馈和历史审计，不作为新收藏写入口，收藏也不自动改变推荐排序。

离线 ETL 负责去重、字段完整性、份量口径、中文映射、标签和来源报告。营养值保留估算标记，不能宣称完成菜谱级 USDA 校准。

### 餐食混合 RAG

- 建立独立的 `meal_item_embedding` 资源表，保存餐食 ID、模型版本、向量和生成时间；
- 使用 `EmbeddingClient` 与 `MealRetriever` 隔离 DashScope；
- 先做餐次、过敏、来源和硬限制过滤，再做 embedding 语义召回；
- 合并结构化分和语义分后进入既有排序/解释链路；
- 不引入向量数据库，MVP 使用进程内归一化向量和 Java 余弦计算；
- embedding/API 失败时降级为结构化检索；
- 用固定标注查询集比较 structured-only 与 hybrid 的 Recall@K、硬约束命中率和降级正确率。

训练/作息指南检索从首版范围移除。作息仍使用结构化事实表，不使用向量 RAG。

## 5. 动作资源分层

动作数据使用“审核子集先闭环、后续按相同规则扩量”的条件分层：

- 首版建议导入 20-40 个经过审核的入门动作；数量不是验收标准；
- 满足基础字段、来源和媒体状态要求的动作：可浏览、收藏和单次推荐；
- Agent 推荐高难度动作时展示难度、适用人群和注意事项；
- 具备 `plan_ready` 所需元数据的动作：可进入自动生成周计划；
- 缺少计划元数据的动作：首版只能浏览或单次推荐，不能进入周计划；
- 当前伤病、活动相关疼痛、孕产或需要医疗干预的情况仍按风险规则拒绝生成具体训练计划。

动作标准化先对审核子集完成自动处理和人工抽查，再按相同报告扩量。`plan_ready` 至少要求来源、步骤、部位、器材、难度、动作模式、风险提示、替代关系和可匹配的确定性入门模板完整。它是计划生成资格，不是用户使用权限；完整目录的导入身份是 `(source_name, source_id)`，源字段与派生资格字段分列保存。

## 6. 周计划交互

计划作为聚合根，保存健康档案版本、来源会话、事实来源和候选资源快照。当前生命周期：

```text
DRAFT -> UNENABLED -> ENABLED
                         |
                         +-- disable --> UNENABLED

UNENABLED -- archive --> HISTORY
```

- 同一匿名身份只保留一份当前 `ENABLED` 计划；启用新计划时旧计划回到 `UNENABLED`；
- 编辑 `ENABLED` 计划时先复制为新的 `DRAFT`，保存和启用分离；
- 用户可批量新增、替换、删除、移动已有项目并保存备注；动作 duration/sets/reps 可编辑，餐食资源事实只读；
- 保存后生成新版本，重新执行时间冲突、营养、过敏、训练恢复和风险校验；
- 不允许自由编辑餐食营养值和作息规则；资源名称、营养、部位和资格由审核 Reader 在服务端重读；
- 同一批请求中替换动作并提交合法处方时保留该处方，否则使用新资源确定性默认值。

## 7. 实施里程碑

31-32 号构成“面试可演示门槛”：必须形成三品类 Agent 路由、严格契约、确定性降级和可查看 Trace，但不代表完整产品。31-36 号构成“完整 MVP 门槛”。每个里程碑在本阶段补齐测试或冒烟，M4 只汇总和复现证据。

### M0：配置与安全基础

- 引入 dev/prod profile 和 `IdentityProvider`；
- 生产环境移除明文 key、轮换已暴露凭证；
- Cookie 匿名身份、管理接口隔离、数据库分离；
- 为 `ChatRequest` 增加 `requestId`，通过 `diet_request_trace` 的唯一约束保存成功响应并实现单实例幂等；首版不新增自动清理任务，Trace 保留/清理规则写入运行手册。

完成标准：先审查工作区现有的 31 号未提交草稿；Java 21 Maven 编译、数据库迁移、旧饮食接口、Cookie/admin/requestId 冒烟全部通过后才能接纳并标记完成。开发测可保留调试能力，用户侧不能通过 Header 读取其他身份数据。

### M1：Agent 运行接口和健康聊天垂直闭环

- 建立 `AgentInvoker` 及 AgentScope/固定夹具两个适配器；
- 建立角色 DTO、契约/Prompt 版本、超时、输出校验和失败分类；
- 将现有餐食链路封装进 `MealModule`；
- 使用版本化种子资源实现 `HealthOrchestrator`、`ExerciseModule` 和 `RoutineModule`；
- 移除全局餐食库前置拦截，将 `sourceMode` 限定在餐食域；
- 固定三品类、澄清继续、风险、非法 JSON、候选越界、超时和无 key 测试。

完成标准：固定夹具模式无需外部模型即可跑通测试和接口；真实 DashScope 完成至少一条冒烟；饮食、健身、作息请求分别完成识别、澄清、领域查询、响应校验和 Trace，失败不返回未处理的 500。

### M2：审核资源、浏览 API 和餐食 RAG

- 建立最终资源 schema、来源/媒体状态和可重跑导入报告；
- 导入审核餐食基线 295 条、完整动作目录 1324 条（其中按确定性资格门槛筛选 `plan_ready` 项）和 15 条作息事实；
- 实现餐食/动作分页浏览 API 和稳定无图状态；
- 实现 embedding 生成、进程内索引、结构化降级和 Recall@3 对比；
- 将正式资源适配到 M1 已通过的领域模块，不重写 Agent 编排。

完成标准：资源子集和重建报告可复现；结构化检索与 hybrid 都可运行；硬约束命中率 100%；图片和来源状态可追溯。hybrid 没有可复现提升时只报告实验结果，不宣称效果提升。

### M3：周计划和风险校验

- 建立计划聚合、版本、DRAFT/UNENABLED/ENABLED/HISTORY 表和接口；
- 实现 `WeeklyPlanComposer`；
- 实现三阶段风险校验：候选前、组合时、LLM 输出后；
- 实现健康档案快照、计划激活、历史版本和受限日期/时间编辑。

完成标准：不持久化未经校验的计划；ENABLED 编辑生成新 DRAFT；旧版本可追溯；保存与启用分离。

### M4：用户侧前端、交付与验收

- 按 hash 路由拆分对话、餐食、动作、健康档案和我的计划；
- 动作卡片/详情统一媒体状态、难度提示、独立收藏和来源展示；
- 计划页面实现计划库侧栏、七日工作区、移动端单日视图、资源弹窗、详情抽屉、批量保存和状态操作；
- Trace、评估、导入页面与用户入口隔离；
- 使用真实浏览器完成移动端和桌面端验收。
- 汇总各阶段自动化、接口、浏览器和部署证据，从干净环境按 README 复现。

完成标准：用户侧只看到用户体验功能，开发测完整功能可用，媒体失败和空数据不会破坏布局。

### M5：面试工程化后端增强（31-36 全部完成后启动）

M5 不属于首版 MVP 门槛。它在现有健康 Agent 地图完成后使用独立 Wayfinder 地图推进，按
`5 个开发日 + 1 个验收日 + 1 个缓冲日` 时间盒实施；目标是完成本地可运行的最小后端增强，
不改变现有确定性编排、风险规则和旧饮食 API 兼容行为。

实施跟踪以 [M5 本地 Wayfinder 地图](../.scratch/health-agent-m5-backend-enhancement/map.md) 和
[GitHub M5 Wayfinder 地图](https://github.com/Leewwp/health_agent/issues/46) 为入口；开放任务、认领状态和
原生阻塞关系以 GitHub 子票为准，本地票保存相同范围与验收口径。

- DashScope 同时提供现有 Agent 调用与 `text-embedding-v3` 向量生成；API key 只通过环境变量注入。供应商兼容性依据见 [LLM 供应商密钥与 AgentScope/Qdrant 兼容性研究](research/llm-provider-key-compatibility.md)；
- 复用 AgentScope 1.0.11 依赖线，在项目中显式锁定 MCP Java SDK `0.17.0` 与 Qdrant Java Client `1.17.0`，本地镜像固定为 `qdrant/qdrant:v1.17.0`；不为本阶段升级 Spring Boot 或引入 Spring AI。版本依据和兼容风险见 [MCP + Qdrant Java 最小落地评估](research/mcp-qdrant-java-integration-fit.md)；
- 定义小型 `VectorStore` seam，提供 Qdrant 生产适配器和内存测试适配器。MySQL 继续保存餐食事实和业务状态，Qdrant 只保存可按 `provider + model + dimension + version` 重建的向量索引；
- 将餐食 hybrid 检索从“结构化候选池内语义重排”改为结构化召回与 Qdrant 向量召回的候选合并。审核状态、来源、过敏原和排除 ID 仍是确定性硬约束；Qdrant 或 Embedding 不可用时降级到结构化检索；
- 在同一个 Spring Boot 应用中提供 MCP HTTP 入口，使用 `MCP_API_TOKEN` 做最小 Bearer 鉴权，只开放 `search_meals`、`get_meal_detail`、`get_routine_facts` 和 `calculate_targets` 四个公共只读或纯计算工具；
- 建立版本化 YAML Skills Registry，首批登记 `meal-recommendation`、`routine-guidance` 和 `health-target-calculation`。每个 manifest 固定 `name/version/input_schema/output_schema/allowed_tools/risk_level`，并作为 MCP resources 提供读取；
- Skills 只描述能力、Schema 和工具白名单，MCP 只复用既有领域模块；当前健康 Agent 不改成自主工具调用，也不通过 HTTP/MCP 回调自身；
- 仅强化健康链路现有 `AgentContractModule` 的 Trace 脱敏和必要失败信息，不在本阶段迁移旧饮食链路的会话级 Agent 调用方式。

实施顺序：第一天先完成依赖双冒烟，包括 Maven 编译与现有测试、MCP `initialize -> tools/list -> tools/call -> resources/read`、Qdrant collection 创建/upsert/过滤查询；通过后再用真实 DashScope key 生成向量并完成结构化/向量候选合并与降级，随后实现 MCP tools、Skills manifest/resources 和工具白名单，最后完成配置、核心测试和本地联调。任一阶段都必须保持 `mvn test` 可通过。

完成标准：本地启动 MySQL 与 Qdrant 后，可通过环境变量启动 Spring Boot；真实 DashScope 向量能够完成一次 Qdrant hybrid 餐食查询；Qdrant 不可用时返回结构化结果；无效 MCP token 被拒绝，合法客户端可读取 Skill 并调用其白名单内工具；现有测试、Qdrant 合并/降级测试和 MCP 鉴权/Schema/白名单测试通过。

## 8. 必须补齐的文档交付物

- 最终 DDL、迁移顺序和旧饮食表兼容策略；
- HealthIntent、档案、计划、动作和餐食响应 DTO；
- 风险规则编号、阈值、拒绝/提示文案和验收样例；
- Agent 输入/输出契约、Prompt/契约版本、失败分类、固定夹具和 Trace 字段；
- 餐食媒体来源、许可、可访问性或稳定无图状态的核验记录；
- 动作 `plan_ready` 字段和自动标准化规则；
- RAG 固定查询集和 Recall@K 结果；
- 部署、密钥轮换、备份、回滚和开发测/用户侧运行手册。
- M5 启动后补充 Qdrant collection 版本与重建方式、MCP tool/Skill manifest 清单、本地启动命令和最小冒烟记录。
