# 健康 Agent 面试演示核心流程规格

Status: ready-for-agent

GitHub: [#83](https://github.com/Leewwp/health_agent/issues/83)

## Problem Statement

本项目用于 Java 后端与 Agent 开发岗位的面试展示，不以生产级健康平台为目标。面试演示只需要稳定证明四项核心能力：餐食推荐、健身动作推荐、Agent 生成训练计划和 Trace 追踪；同时需要具备正常的页面、加载反馈、失败恢复和可接受延迟。

当前代码已经有审核资源、健康聊天、周计划、风险校验、版本快照、餐食 Hybrid RAG、Qdrant 适配器和 Trace 基础，但核心体验仍不成立。现有每周个人计划中的训练安排由固定 Java 规则组合，Agent 只解释结果，因此不能准确宣称“Agent 生成训练计划”。健康聊天还存在 Clarify 文本 Prompt 与 JSON 解析契约冲突、多个模型调用串行等待、模型异常后才固定降级等问题；用户等待时缺少明确阶段反馈，计划和 Trace 页面也有明显的布局和信息可读性问题。

原方案同时包含作息多轮收集、完整餐食计划联动、MOVE/REPLACE 对话调整、完整 Trace 工作台、大量响应式和运维增强，预计工作量过大，且对四项核心演示能力的边际收益较低。需要把项目收敛为一条真实、可解释、可降级的 Agent 训练计划闭环，并保留能够用代码、测试和实跑证据说明的 RAG、Qdrant、风险 Guard、事务版本和 Trace 技术亮点。

## Solution

保留每周个人计划能力，但将训练部分改为“受约束的 Agent 生成”。用户通过健康聊天提供最小训练偏好，包括训练目标、部位、器械、难度、可训练日期和时间；系统在当前会话中形成简短的计划需求简报并展示确认操作。用户确认后，Plan Agent 只能从正式审核边界提供的 `plan_ready` 动作候选中选择动作，并决定训练日期、开始时间和单次时长。模型不得创造动作、健康事实、训练剂量或数据库标识。

健康档案继续作为年龄、身体风险和能量区间的唯一事实来源，不复制进计划简报。新匿名用户缺少档案时，系统必须保留当前会话和已收集简报，明确引导其完成现有最小健康档案后返回确认/生成，不允许用静默默认档案绕过风险 Guard。普通餐食或动作推荐产生的会话槽位不得自动写入计划简报；只有明确的计划上下文才更新简报。

Agent 返回结构化训练安排后，确定性业务服务执行候选 ID 白名单、动作计划资格、健康风险、日期范围、时间冲突、连续训练部位和计划版本规则校验。组数、次数等训练剂量继续由受控 Java 规则根据动作难度生成。模型超时、不可用、JSON 非法或输出未通过校验时，不重试模型，立即使用遵守同一份已确认简报的确定性组合器生成可用草稿；Trace 明确记录 `AGENT` 或 `FALLBACK`、实际模型、耗时、解析状态和降级原因。

模型生成与初步校验在数据库事务外完成，只有通过 Guard 的最终草稿进入短事务写入，继续复用现有每周个人计划的版本快照、激活和唯一 ACTIVE 不变量。fallback 必须消费同一份已确认简报和同一批过滤后的候选，遵守用户的日期、时间、器械、难度、部位和目标约束；不能退回当前固定周一/三/五 19:30、只消费 `trainingFocus` 的旧行为。完整周计划聚合可以继续保留确定性餐食和作息项目以兼容现有数据与页面，但本规格只把训练安排称为 Agent 生成；餐食推荐仍通过独立推荐流程展示，作息不再是核心演示功能。

餐食和动作的明确请求使用确定性意图快路径，歧义或复合表达最多执行一次结构化理解调用；Clarify 使用确定性缺失字段模板。前端为聊天和计划生成显示阶段进度、防重复提交、截止时间和失败恢复。由于本批不引入 SSE/轮询，阶段文案是明确标注的等待反馈而非服务端实时进度；真实阶段与耗时以最终 Trace 为准。Trace 页面只实现面试诊断所需的最小工作台：总耗时、Agent 耗时、实际模型、解析状态、降级原因和按顺序排列的事件时间线，原始 JSON 保留为次级视图。

餐食 Hybrid RAG 和 Qdrant 继续作为真实技术能力保留。Qdrant 是可选运行模式，不阻塞四项核心流程；发布证据必须包含一次真实 Qdrant 索引和 Hybrid 查询冒烟，并如实记录 Recall、MRR、NDCG、P95 和降级情况，不宣称当前没有证据支持的显著提升。

## User Stories

1. As a 面试官, I want to open one cloud URL and immediately看到可用首页, so that 我不需要安装或配置环境。
2. As a 面试官, I want to ask for a meal recommendation and receive reviewed meal cards, so that 我能看到真实可用的餐食推荐能力。
3. As a 面试官, I want to ask for an exercise recommendation and receive reviewed exercise cards, so that 我能看到健身动作推荐能力。
4. As a 面试官, I want to describe training preferences and generate a weekly training schedule, so that 我能确认 Agent 实际参与了计划决策。
5. As a 面试官, I want to inspect the corresponding Trace, so that 我能看到模型、耗时、Guard 和降级路径。
6. As a 候选人, I want every résumé claim to have code, tests or runtime evidence, so that 我可以准确回答技术追问。
7. As a 健康聊天用户, I want explicit meal requests to use a fast path, so that 简单推荐不会等待不必要的意图模型。
8. As a 健康聊天用户, I want explicit exercise requests to use a fast path, so that 动作推荐能在可接受时间内返回。
9. As a 健康聊天用户, I want ambiguous language to retain one bounded Agent understanding call, so that 自然语言能力不会被完全删除。
10. As a 健康聊天用户, I want clarification questions to be deterministic, so that 缺失字段不会触发慢且不稳定的模型调用。
11. As a 健康聊天用户, I want meal cards to show available nutrition and source information, so that 推荐结果不是只有名称的占位内容。
12. As a 健康聊天用户, I want meal and exercise cards to open their detail drawer from the card body, so that 主要交互目标清晰。
13. As a keyboard user, I want card details to be reachable by keyboard, so that 页面具备基本可访问性。
14. As a 健康聊天用户, I want favorite and feedback controls not to open the detail drawer, so that 独立操作不会互相干扰。
15. As a 健康聊天用户, I want loading feedback while a request is running, so that 页面不会像卡死。
16. As a 健康聊天用户, I want duplicate submissions blocked during a request, so that 高延迟不会造成重复消息或写入。
17. As a 健康聊天用户, I want controls restored after timeout or failure, so that 我可以直接重试。
18. As a 健康聊天用户, I want a concise fallback response instead of JSON or stack information, so that 模型降级仍然适合演示。
19. As a 训练计划用户, I want to state preferred body parts, so that 训练安排覆盖我想练习的区域。
20. As a 训练计划用户, I want to state available equipment, so that 计划不会选择无法完成的动作。
21. As a 训练计划用户, I want to state difficulty, so that 动作符合我的入门程度。
22. As a 训练计划用户, I want to state preferred training days and time range, so that 计划适合我的日程。
23. As a 训练计划用户, I want a controlled training-goal vocabulary, so that 目标匹配有真实数据依据而不是 Prompt 宣称。
24. As a 训练计划用户, I want the current session to retain collected preferences, so that 我不必在相邻对话中重复输入。
25. As a 训练计划用户, I want a new session to isolate the old plan brief, so that 上一次需求不会泄漏到新计划。
26. As a 训练计划用户, I want to review a concise preference summary before generation, so that 我能发现明显理解错误。
27. As a 训练计划用户, I want one explicit “生成训练计划” action, so that 模型调用不会在每轮聊天中意外发生。
28. As a 训练计划用户, I want repeated generation clicks blocked or idempotent, so that 一次操作不会生成多个草稿。
29. As a 训练计划用户, I want Agent-selected exercises to come only from reviewed `plan_ready` resources, so that 模型不能虚构动作。
30. As a 训练计划用户, I want the Agent to decide exercise selection and schedule, so that 该功能真实属于 Agent 生成。
31. As a 训练计划用户, I want sets and repetitions bounded by deterministic rules, so that 模型不会自由编造训练剂量。
32. As a 训练计划用户, I want profile risks checked before and after generation, so that 个性化不能绕过风险规则。
33. As a 训练计划用户, I want schedule conflicts and weekly date bounds validated, so that 草稿在时间上可执行。
34. As a 训练计划用户, I want a usable draft when the model fails, so that 核心演示流程不会中断。
35. As a 训练计划用户, I want to be navigated to the plan after generation, so that 我可以立刻查看安排。
36. As a 训练计划用户, I want to activate a valid draft, so that 草稿到 ACTIVE 的生命周期可以演示。
37. As a 训练计划用户, I want invalid plans to leave no partial rows, so that 失败不会破坏计划状态。
38. As a 训练计划用户, I want plan detail readable on desktop and mobile, so that 七日内容不会被列表挤压。
39. As an 管理员, I want each request to show total and Agent latency, so that 我可以定位延迟是否来自模型。
40. As an 管理员, I want each Agent event to show effective model and parse status, so that 配置问题和格式错误可以区分。
41. As an 管理员, I want successful fallback requests marked internally degraded, so that 顶层 SUCCESS 不会掩盖模型失败。
42. As an 管理员, I want events ordered as a phase timeline, so that 路由、检索、Agent、Guard 和持久化过程可解释。
43. As an 管理员, I want nested payloads contained and readable, so that 长 JSON 不会撑破页面。
44. As an 管理员, I want raw redacted JSON as a secondary view, so that 可读诊断不丢失底层证据。
45. As a developer, I want Qdrant failure to degrade to structured retrieval, so that 向量存储不是单点依赖。
46. As a developer, I want one real Qdrant-enabled indexing and query proof, so that Qdrant 不是未使用依赖。
47. As a developer, I want RAG evidence to report Recall、MRR、NDCG、P95 and degradation honestly, so that 简历不夸大收益。
48. As a developer, I want one highest-level fixture scenario for conversation-to-plan, so that 内部重构不会制造脆弱测试。
49. As a developer, I want a bounded live-model smoke outside ordinary CI, so that 真实集成被验证而 CI 仍确定。
50. As a deployer, I want aligned application and Nginx timeouts, so that 代理不会提前 504 而后端继续写入。
51. As a deployer, I want fresh database startup and reviewed seed import idempotent, so that 云端环境可以重复部署。
52. As a deployer, I want the user flow to survive model or Qdrant unavailability, so that 面试演示有确定退路。
53. As a 候选人, I want a repeatable interview script and evidence checklist, so that 我能在有限时间稳定展示核心能力。
54. As a 首次访问者, I want a missing health profile to preserve my collected plan brief and give me a clear return path, so that 我不需要重输偏好，也不会被静默默认档案绕过风险校验。

## Implementation Decisions

### 产品与领域边界

- 唯一核心演示能力是餐食推荐、健身动作推荐、受约束的 Agent 训练计划和 Trace 追踪。健康档案、风险规则、审核资源、计划版本和部署是支撑能力，不扩展为独立产品线。
- 每周个人计划继续复用既有草稿、激活、版本和页面能力，但本规格只承诺训练项目由 Agent 选择和排期。餐食和作息项目可继续由确定性服务生成，不能表述为 Agent 生成。
- 作息退出核心对话脚本，不作为计划需求简报必填字段，不新增 Agent 个性化作息。保留现有作息事实、模块和数据结构作为兼容内容与确定性辅助建议。
- 第一版只服务一般成年用户，不扩展医疗诊断、康复处方、竞技训练或效果保证。

### 受约束的 Agent 训练计划

- 在现有健康会话 JSON 中以独立 `planBrief` 命名空间维护最小计划需求简报，不与普通推荐的 `slots` 混用。字段只有训练目标、部位、器械、难度、可训练日期、时间范围和可执行硬约束；不新建独立简报表、历史或管理页面。
- 健康档案仍是年龄、风险条件和能量目标的唯一来源。简报缺字段走确定性追问；档案缺失走类型化“完善健康档案”操作，并在保存后返回同一会话的确认流程。
- 硬约束第一版只接受可确定性执行的排除部位、排除动作/器械和时段限制；无法映射到候选过滤或 Guard 的自由文本必须澄清或明确提示不支持，不能只放进 Prompt 假装已执行。
- 简报只有在明确 PLAN 上下文中更新；普通餐食/动作推荐不污染简报。任何已确认简报的字段变更都会使确认失效，生成操作必须在服务端重读同一会话中最新、已确认的简报。
- 为当前 `plan_ready` 审核动作补齐小型、受控、可测试的训练目标标签。标签必须通过前向 Flyway 数据迁移（或等价可审计迁移）覆盖已有数据库，并同步 fresh seed 与资源版本；不能只修改 `INSERT IGNORE` seed。候选数据不能支持的目标不得仅靠 Prompt 宣称匹配。
- 可训练日期使用“目标周 `weekStart` + 星期集合”的受控表示，由服务端映射到本地日期；时间范围表示每次训练必须落入的可用窗口，不接受由模型自行解释的模糊绝对日期。
- 新增一个只暴露单一高层生成操作的训练计划生成模块，内部封装 Prompt、结构化契约、候选白名单、解析、Guard、fallback 和 Trace 元数据，避免多个浅接口。
- Agent 输入只包含必要健康档案摘要、已确认训练偏好和正式审核 Provider 返回的 `plan_ready` 候选，不向 Prompt 注入全量动作目录。
- Agent 只能引用候选资源 ID，并决定本周训练日期、开始时间和单次时长。组数、次数、重量、强度、医疗事实和资源内容不由模型生成。
- 候选白名单、计划资格、偏好过滤、训练日/时间窗口包含关系、风险、年龄、周日期、跨午夜冲突、连续训练部位、项目数量和时长边界由确定性 Java Guard 执行，LLM 不能覆盖。
- Agent 失败不重试；超时、上游错误、非法 JSON、候选越界或校验失败进入确定性组合器。Agent 与 fallback 必须共享已确认简报、过滤后候选和 Guard；fallback 仍须满足日期、时间、器械、难度、部位、目标与硬约束。结果记录来源 `AGENT` 或 `FALLBACK` 及稳定原因。
- Plan Agent 只在用户点击确认生成时调用一次。PLAN 意图和缺失字段澄清不串行调用第二个模型，结果不等待非必要的模型润色。
- 模型生成与初步 Guard 在事务外完成；最终计划进入现有短事务写入。激活时继续基于最新档案和风险规则重检。
- 计划版本快照保留训练偏好、候选来源版本、生成来源、实际模型、Prompt/契约版本、Guard 版本和 fallback 原因。
- 高层生成响应返回 `planId`、`traceId`、`generationSource` 和用户可读状态。计划页在不暴露原始模型内部字段的前提下优先展示训练安排，并把 `AGENT/FALLBACK` 映射为“Agent 生成/规则降级”等中文来源标签；确定性餐食/作息项目保持兼容但视觉上降为辅助内容，不能让面试官误以为整份周计划都由模型生成。

### 延迟与用户体验

- 明确的餐食、动作、作息事实和计划短语使用确定性意图快路径；歧义或复合语言最多执行一次结构化理解调用。
- Clarify 使用缺失字段的确定性中文模板，删除纯文本 Prompt 与 JSON 解析契约冲突。
- 为整轮请求设置统一截止时间；模型只能消费剩余预算。超时层级必须满足“模型预算 < 应用截止时间 < Nginx 超时 < 前端超时”，应用在持久化前再次检查截止时间，避免前端已失败而后端继续不可见写入。
- 聊天和生成按钮提供可见的 spinner/skeleton 或等价等待动画、阶段文案、禁用重复提交、超时提示和重试。计划生成展示“分析偏好、生成安排、校验并保存”，但不把无实时事件支撑的阶段文案描述成精确服务端状态，不做 token 流式输出。
- 发布延迟门槛以云端真实 Chromium 的热请求为准：等待反馈在 100ms 内可见；明确餐食/动作推荐 P95 ≤ 3 秒且单次 ≤ 5 秒；需要一次 Agent 理解的歧义请求单次 ≤ 15 秒；训练计划生成单次 ≤ 20 秒，超时必须在该上限内返回遵守同一简报的 fallback。冷启动/预热请求单独记录，不混入热请求 P95。
- 普通用户页面隐藏 session、内部任务名、原始枚举和版本字段。面试演示配置允许展示低干扰的“查看本次 Trace”入口，但只能跳转到仍受 `ADMIN_TOKEN` 保护的管理员页；非演示生产配置不展示该入口。
- 餐食卡一次返回已有营养字段；餐食和动作卡主体可打开详情抽屉，反馈和收藏保持独立。
- 计划页只修复明显可用性问题：桌面为窄选择区加宽详情区，中小屏堆叠或按天阅读，不重写全站视觉系统。

### Trace 最小诊断闭环

- Agent 调用结果和 Trace 事件记录实际模型、完成或超时耗时、可用 token 用量、解析状态、fallback 原因和生成来源。
- 顶层请求状态与 Agent 诊断结果分离。fallback 成功时请求可为 `SUCCESS`，诊断结果必须为 `DEGRADED`。
- Trace 页面默认展示请求摘要、总耗时、Agent 耗时、降级次数、顺序时间线和 Agent 调用卡；原始 JSON 是折叠的次级视图。
- 嵌套 JSON 只在可安全解析时格式化；长 ID 和 payload 不得决定页面宽度，继续遵守现有脱敏边界。
- 本批不要求拆分 Trace 列表/详情 API；只有实测证明响应体阻塞演示时才进入核心修复。

### 餐食 RAG 与 Qdrant

- 继续遵守餐食 RAG 边界：MySQL Structured 与 Qdrant Vector 组成可降级 Hybrid Retrieval，训练动作和作息不扩展向量检索。
- Qdrant 是可选运行模式，未配置、不可用或超时时回退 Structured Retrieval，不阻塞聊天、计划、浏览或应用健康检查。
- 面试云端部署默认设置 `DIET_VECTORSTORE_MODE=qdrant` 并使用预先完成索引的当前 collection；一次性生成/索引完成后不在每次应用启动时重建。这里的“可选”表示 Qdrant 故障不会阻断核心流程，而不是云端主演示默认不使用 Qdrant。
- 保留现有 VectorStore seam、索引 runner、融合权重和评估框架，不进行向量存储重写或 embedding 迁移。
- 发布前执行一次真实 Qdrant 索引和 Hybrid 查询，记录 Structured/Vector/Fused 候选、最终结果、降级状态和延迟。
- 报告展示 Recall@3、MRR、NDCG@3、Precision@3、P95 与降级分布；收益很小时如实记录，不使用“显著提升”。

### 交付顺序与估算

- Ticket 01：推荐快路径、Clarify 契约、请求预算和推荐 UI，1.5–2.5 人日。
- Ticket 02：最小训练计划需求简报、训练目标标签和确认交互，1–1.5 人日。
- Ticket 03：受约束 Agent 训练计划、事务外生成、Guard、fallback、草稿/激活和生成 UI，4–6 人日。
- Ticket 04：Trace 最小诊断工作台，1–2 人日。
- Ticket 05：Qdrant-enabled Hybrid RAG 实跑和证据，0.5–1 人日。
- Ticket 06：云端发布、真实浏览器验收和面试脚本，1–1.5 人日。
- 六张票逐项估算的算术合计为 9–14.5 人日；其中 Qdrant 证据可与主线并行，但并行只缩短日历时间，不减少人日。若仍要承诺 7–11 人日，必须在实施前重新压缩 Ticket 03/04/06 的验收范围或投入并行开发者；不含云厂商网络、域名/证书或凭证等待。

## Testing Decisions

- 最高测试接缝是现有健康聊天和周计划 API 的完整场景：匿名身份进入聊天，收集训练偏好，缺档案时保存简报并引导完成档案，返回同一会话确认简报，调用可控 Agent 生成草稿，查看七日计划，激活计划，并通过演示入口检查受保护 Trace。优先复用现有编排器、Controller、周计划服务、fixture Agent 和 MySQL 集成测试，不创建第二套端到端框架。
- 好测试断言外部契约和领域不变量：响应类型、简报完整度、候选 ID、计划日期/时间、草稿与 ACTIVE、版本来源、HTTP 错误、Trace 结果和数据库无半成品；不固定 Prompt 排版、私有方法或非安全文案。
- 训练计划生成测试覆盖合法 Agent 输出、未知候选 ID、非 `plan_ready`、重复或过量项目、时间冲突、风险阻断、非法 JSON、超时和 fallback；Agent 与 fallback 两条路径都断言遵守同一份已确认简报和候选过滤结果。
- 使用可控慢 Agent 证明请求截止时间、无模型重试、控件恢复、requestId 不重复写入，以及事务不在模型等待期间保持打开。
- 复用现有计划验证和真实 MySQL 事务测试，验证激活唯一性、版本快照、风险重检、失败回滚和并发不变量。
- 计划需求简报测试覆盖独立命名空间、普通推荐不污染、会话合并、纠正后确认失效、新会话隔离、缺档案往返、默认值和旧 session JSON 向后兼容。
- 推荐测试覆盖明确请求快路径、歧义回退、营养合同、详情抽屉和反馈事件隔离。
- Trace 测试覆盖 `SUCCESS + DEGRADED`、实际模型、完成失败耗时、null token、嵌套 JSON、长 ID 和脱敏 payload。
- 普通 CI 使用 fixture Agent；发布前单独运行一次有界真实模型 smoke，证明至少一次训练计划结构化生成成功，并证明错误时可 fallback。
- Qdrant 继续复用 VectorStore 单元和集成门控；本批必须有一次真实 Qdrant-enabled 证据，但 Qdrant 不可用不得阻塞普通 CI。
- UI 改动必须在运行中的同源应用使用真实 Chromium 验收：餐食推荐、动作推荐、缺档案引导与返回、偏好确认、单次生成、七日查看、激活、受保护 Trace 跳转、超时恢复、等待动画、桌面和移动布局。
- 发布门槛包括普通全量测试、真实 MySQL 门控、Qdrant 门控或明确跳过原因、Compose 静态校验、干净数据库启动、健康检查和云端浏览器脚本。

## Out of Scope

以下内容从本批移除并归档到未来任务，不属于当前 `ready-for-agent` tickets：

- MOVE、REPLACE、ADD、REMOVE、整日移动、整周重生成和任意自然语言计划编辑。
- Agent 自由生成组数、次数、重量、强度、恢复周期或医疗/康复处方。
- 作息多轮收集、Agent 个性化作息、睡眠计划生成和训练/作息指南向量检索。
- 餐食偏好与七日餐食计划的完整联动；本批餐食能力以单次推荐和现有确定性计划兼容为边界。
- 独立计划需求简报、历史、跨会话复用、多个并行简报和管理页面。
- 完整 Trace 主从工作台、列表/详情 API 拆分、JSON 下载、复杂标签工作台和六宽度穷举验证。
- Qdrant 强制云端依赖、向量数据库重设计、embedding 迁移、大规模调优和训练/作息 RAG。
- 数据集大规模扩充、全量动作计划资格审核、复杂训练知识图谱或训练效果评估。
- SSE、WebSocket、token 流式输出、后台队列、Redis、多实例、分布式锁、Kubernetes 和自动扩缩容。
- 生产级账号、角色、租户、医疗合规平台、专业审核后台和长期匿名数据清理。
- 前端框架迁移、Node/npm 构建链、全站视觉重设计和原生移动端。

未来任务的原因、重新启动条件和原规格映射记录在同目录 `future.md`。

## Further Notes

- 核心面试脚本：打开聊天 → 餐食推荐 → 动作推荐 → 输入训练偏好 → 首次访问时完成最小健康档案并返回 → 确认简报 → 生成训练计划 → 查看并激活 → 经演示入口打开受保护的对应 Trace。
- 推荐面试表述：“Agent 在审核动作候选集中负责高组合度的动作选择与排期，确定性 Guard 负责健康风险、资源资格、时间和事务约束；模型失败时规则降级，保证主流程可用。”
- RAG 推荐表述：“使用 MySQL 结构化召回与 Qdrant 向量检索实现可降级 Hybrid RAG，通过 Recall、MRR、NDCG 和 P95 评估；结构化检索同时承担硬约束和故障降级路径。”
- 发布证据必须附一张“简历主张 → 核心/支撑路径 → 代码位置 → 运行效果 → 测试/Trace/实跑证据 → 未采用方案及原因”矩阵。Spring Boot、AgentScope/DashScope、MyBatis/MySQL、Flyway、Qdrant、Nginx/Compose、JUnit 等只在矩阵有证据时写入简历；MCP、Skills 等非核心既有能力不得混入四步主演示脚本。
- 不把作息确定性处理解释为能力不足。训练选择与排期是高熵组合问题，适合受约束 Agent；基础作息事实和冲突是低变化硬约束，规则更易验证。
- 完成标准不是功能数量，而是四项核心能力能在新匿名会话中重复执行，用户无明显等待和布局缺陷，模型或 Qdrant 故障时仍有可用结果，且每项技术都有代码、测试、Trace 或实跑证据。
