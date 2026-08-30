# 会话审查报告：演示主流程修复（2a24e41）

- 审查日期：2026-08-30
- 审查对象：会话 `sess_9ebb25f0-0a9b-413b-a9b0-1eddcb1d64bf` 的工作成果（提交 `2a24e41 feat: 演示主流程修复——全八槽位结构化召回、同域换主题、候选稀缺可见（方案 A）`）
- 审查方式：逐条读取会话 transcript（`~/.zcode/cli/rollout/model-io-sess_9ebb25f0-*.jsonl`，239 条模型交互）→ 对照 `docs/review-2026-08-30-full-flow.md` 与 `.scratch/health-agent-demo-flow-repair/spec.md` 的 8 项完成定义与 22 条 User Story → 关键代码走读 → 全量回归复跑 → 真实浏览器端到端复测。
- 结论：**交付声明全部属实，无虚假实现、无缺失任务；发现 1 项收尾遗漏（progress.txt 未记录本轮任务），已补齐**。规格 DoD 8/8 项完成。

## 一、会话任务回顾

会话接受 `ready-for-agent` 状态的修复规格（面试演示主流程修复），核心目标是消除"推荐清淡的晚餐"确定性空结果（P0）、同域换餐次被旧偏好卡住（P1）、训练唯一候选复用说明用户不可见（P1）、中文标签漂移（P2）。会话自主决策采用方案 A（全八槽位 SQL 硬召回 + 稳定排序），实施并提交 `2a24e41`，推送 main。

## 二、规格完成定义逐项核验（DoD 8/8）

| # | 完成定义 | 会话证据 | 本审查复验 | 结论 |
|---|---|---|---|---|
| 1 | "推荐清淡的晚餐"进入预检、确认后 ≥1 张硬约束卡、顺序稳定 | API + 浏览器 T1（2425/2392/2584） | API 冒烟同序；浏览器 B1 3 卡 | ✅ |
| 2 | 健康链与旧链同一输入均可解释非空 | API 验证旧链 3 卡 | 浏览器 B8：旧链 `message=推荐清淡的晚餐, sourceMode=PUBLIC` 返回清蒸鱼/柠檬腌梅蒸鲳鱼/香菇酱油鸡 | ✅ |
| 3 | 结构化/Hybrid/向量降级/严格过滤回归全绿，不违反 Reader 边界 | 全量 870 + MySQL 门控 21/21 + Hybrid 18 | 复跑全量 870（52 环境门控跳过，0 失败）与门控 12 个 integration 类全绿；`HealthResourceReadBoundaryArchitectureTest` 通过（检索器只经 `ReviewedMealReader` 接口，不接触 Mapper） | ✅ |
| 4 | 训练唯一候选复用说明在计划 API 与计划页可见 | `TrainingPlanGenerationServiceTest` 断言 metadata + 浏览器 T3 | 浏览器 B3：计划页真实渲染"候选不足：符合全部条件的候选动作只有 1 个……复用候选。" | ✅ |
| 5 | 同域换主题替换/reset；澄清继承、跨域、综合计划不回归 | Policy 单测 10 + 编排器 42 | 浏览器 B2：同会话"中午吃什么"预检仅含午餐（清淡被替换）；API 预检挂起短答继承（既有测试覆盖） | ✅ |
| 6 | 能量 WARNING、旧 metadata 兼容、旧 diet 链、移动端无溢出、中文标签 | 全量测试 + T4 | 浏览器 B7：390×844 chat/plans 无横向溢出；缩略词"器械/训练日"在训练简报与补充 chip 统一 | ✅ |
| 7 | 真实浏览器证据更新，旧 2026-08-29 记录不再作数 | `frontend-browser-acceptance.md` 追加章节 + 过期声明 | 文档在库（提交内），证据截图在 `.local-run/acceptance/review-2026-08-30-repair/` | ✅ |
| 8 | meal-facet-hardening 回归保持通过，未改 seed/迁移 | 全量套件 | 提交 diff 零 `.sql`/`facets.json`/manifest 改动；`git show 2a24e41 --name-only` 核验；canonical/fresh-schema/旧链套件复跑通过 | ✅ |

## 三、关键实现真实性走读（无虚假实现）

- **P0 八槽位透传**（`StructuredMealRetriever.java:80-93`）：`hardRecallSlots` 固定八键（mealTime/mood/scene/healthGoal/cuisine/foodType/taste/convenience），空值以空列表传参；`DbReviewedMealReader.recallStructured` 全量转 JSON 交给 `mealMapper.search`；SQL 谓词逐槽位 `JSON_OVERLAPS` + 空数组不过滤 + "三餐"兼容（`MealMapper.xml:190-199`），排序 `updated_at DESC, id DESC`（:201）。未发现"注释改了、逻辑没改"的半实现。
- **P0 测试替换而非删除**（spec 硬性要求）：`StructuredMealRetrieverTest` 原"只传餐次"断言被替换为八槽位透传 + 严格过滤仍生效断言（8 键逐一断言 + `assertEquals(8, passed.size())`）；真库新增"窗口外餐次+清淡不截断"用例（`MysqlReviewedReadersIntegrationTest:215` 构造 55 行仅晚餐占窗 + 2 行更旧清淡行）。
- **P1 同域换主题**（`RecommendationTopicPolicy.java`）：单一判定源，`applies` 限定同域 RECOMMEND 轮；澄清链/预检挂起继承（`HealthPhase.CLARIFY || recommendationPreflightPending`）；清除词/只看餐次/新推荐带餐次三种替换语义；"换成/改为"交既有逐槽位覆盖（与简报层语义一致，spec 允许）。编排器在 `SLOTS_MERGED` 后接入并记录 `SLOT_TOPIC_APPLIED` Trace（`HealthOrchestratorService.java:362-372`）。跨域投影与简报暂停/恢复路径未被触碰。
- **P1 候选稀缺可见**：`GenerationNotes` 新增 `candidateScarcity` 且保留两参兼容构造（旧 metadata 反序列化安全）；`TrainingPlanGenerationService.generationMetadata` 写入（`:454-457`）；`WeeklyPlanService.parsePlanGenerationNotes/parseVersionGenerationNotes` 从 metadata 与版本快照读取（列表/详情/版本单一来源）；前端 `plans.js renderGenerationNotes` 第三分区"候选不足"渲染且过 `escapeHtml`。浏览器实测渲染真实出现。
- **P2 术语统一**：后端 `HealthSlotLabels.java`（equipment→器械、trainingDays→训练日）与前端 `health-slot-labels.js` 同步；`health-slot-labels.test.mjs` 新增统一词断言；训练简报摘要"器械：徒手"在浏览器实测可见。
- **求和口径**：`MealRankService.slotScore` 明确 foodType 为硬过滤字段不参与 7 维打分，注释与代码一致，未静默改变分数含义。

## 四、回归复跑（本审查独立执行）

| 套件 | 会话声明 | 本审查复跑 | 一致 |
|---|---|---|---|
| `mvn test` | 922 项：870 通过 / 0 失败 / 52 环境门控跳过 | 870 通过 / 0 失败 / 52 跳过，BUILD SUCCESS | ✅ |
| `mvn test -Ditest.mysql=true` | 12 个真实 MySQL integration 类全绿（仅 Qdrant/live-model 4 项跳过） | 870 通过 / 0 失败 / 4 跳过；MysqlReviewedReadersIntegrationTest 21/21、LegacyDietChain 2/2、MealFacetFreshSchema 3/3、ReviewedDbPlanMeal 7/7 | ✅ |
| `node --test frontend/tests/*.test.mjs` | 42/42 | 42/42 | ✅ |
| 提交内容 | 未触碰 seed/迁移/manifest | `git show --name-only` 确认零 `.sql`/facet 数据改动 | ✅ |

## 五、真实浏览器端到端复测（本审查独立执行）

环境：`scripts/stop-local.sh` + `start-local.sh` 全量重启（避开旧进程陷阱），后端 8082（Flyway v24，REVIEWED_DB），Nginx 8092；ZCode IAB 真实浏览器。

| 用例 | 操作 | 结果 |
|---|---|---|
| B1 主流程 | 新会话"推荐清淡的晚餐"→ 预检（已确认：晚餐+清淡，开始推荐/补充）→ 点击开始推荐 | 3 张硬约束卡（清蒸鱼 146.6 kcal / 柠檬腌梅蒸鲳鱼 248.8 / 香菇酱油鸡 409.5，mealTime 含晚餐/三餐、healthGoal 含清淡），回复"为你推荐了……" + traceId |
| B2 同域换主题 | 同会话"中午吃什么"→ 预检 → 开始推荐 | 预检仅"用餐时间：午餐"（清淡被替换，回到可补充列表）；午餐 3 卡（帕玛森鸡排/低脂芝士通心粉/希腊藜麦牛油果沙拉） |
| B3 训练唯一候选 | 新会话"帮我安排一周健身计划"→ 减脂/全身/徒手/入门/周三周五/17-18 点/2026-08-31 → 开始生成 | 计划页头部真实渲染第三分区"候选不足：符合全部条件的候选动作只有 1 个，不足以为每个训练日安排不同动作，已按指定训练日复用候选。"；周三/周五各安排 high knee against wall（60 分钟 · 2 组 × 10 次）；摘要统一"器械：徒手" |
| B4 餐食浏览 | 295 条；名称搜索"清蒸"（表单提交）→ 2 条；收藏/取消收藏；仅看收藏过滤 | `q=%E6%B8%85%E8%92%B8` 请求携带；搜索 2 条、收藏按钮态切换、仅看收藏 TOTAL=1 → 清理后 0 |
| B5 动作浏览 | 1324 条；部位/器材/难度/动作模式筛选；"可入周计划"徽章 | 正常；器材词表"徒手/哑铃/杠铃/壶铃/弹力带/器械"统一 |
| B6 我的计划页 | 计划列表 → 详情（含生成说明） | 训练计划"综合 · 草稿 · 规则降级"呈现，候选不足说明同源可见 |
| B7 移动端 | 390×844 计划页与聊天页 | `scrollWidth == clientWidth == 390` 无横向溢出 |
| B8 旧链 | `POST /api/v1/diet/chat`（`message` + `sourceMode=PUBLIC`）"推荐清淡的晚餐" | 返回清蒸鱼/柠檬腌梅蒸鲳鱼/香菇酱油鸡（与健康链同批，符合"不要求逐 ID 相同、可解释非空"） |
| B9 健康档案 | 档案页 | 能量区间 2150-2400 kcal 估算 + 表单正常 |

测试后清理：本人测试产生的收藏已全部取消（favorites total 0）。

## 六、审查中发现的问题与处理

1. **遗漏：progress.txt 未记录本轮任务（已修复）**。会话交付了代码、测试、文档与提交，但进度文件仍停留在 2026-08-29 的 meal-facet-hardening 记录。已按既有格式追加 `[2026-08-30] - Task demo-flow-repair` 条目（What was done / Testing / Notes，含"增肌组合零候选"演示提示），随本报告一并提交。
2. **非缺陷（工具陷阱）**：IAB 合成回车不触发表单隐式提交，浏览页名称搜索"无响应"；手动派发 submit 后请求正常携带 `q` 且真库返回 2 条。前端行为测试 `browse-search.test.mjs` 已覆盖真实回车/提交事件路径，判定为验收工具限制而非应用缺陷。
3. **非缺陷（契约）**：旧链 `/api/v1/diet/chat` 需 `message` + `sourceMode` 字段，缺省抛 500（`DietException: sourceMode 不能为空`）；这是既有契约（`ChatRequest` 模型），非本轮回归。前端 `legacy.js` 调用自带 sourceMode。
4. **留给用户决策的未跟踪文件（未擅自提交）**：`docs/runtime-architecture.*`（2026-08-29 22:35 archify 架构图产物，8 个文件）与三个简历 md（`简历-AI全栈开发.md` 等，2026-08-29 13:34）。均非本会话产物，归属与是否入库待用户决定。
5. **演示注意项（规格外，诚实行为）**：训练"增肌+全身+徒手+入门"组合当前零候选（`_零候选不放宽_` 是既有合同，见 conversation-contracts #108）。面试脚本若需增肌示例，建议按协议改用"减脂"组合或调整约束；本会话 T3 与浏览器 B3 均以"减脂"组合通过。

## 七、收尾工作清单（本审查完成）

- [x] `progress.txt` 追加 2026-08-30 demo-flow-repair 任务记录（含测试计数与演示提示）
- [x] 审查截图证据入库存档（`audit-b1-*`、`audit-b3-*` 位于 `.local-run/acceptance/review-2026-08-30-repair/`）
- [x] 本审查报告（`docs/review-2026-08-30-repair-session.md`）
- [x] 复测产生的测试数据已清理（收藏 0 条）
- [ ] 留给用户：runtime-architecture 系列与简历文件的提交/归档决定

## 八、总体结论

会话 `2a24e41` 的交付声明经逐条对照实现、回归复跑与真实浏览器复测全部成立：P0 空结果根因（召回只传餐次 + 排序无次键）确已修复且修复方式不违反方案 B 读取边界与改动边界（零数据/迁移变更）；P1 换主题语义真实生效且未破坏澄清继承与跨域暂停；候选稀缺说明在计划 API 与计划页双端可见；术语统一前后端一致；DoD 8/8 达成。唯一收尾缺口（progress.txt）已补齐并按仓库惯例提交。未发现虚假实现、夸大声明或任务遗漏。遗留事项仅为用户个人文件的入库决策与面试脚本的"增肌零候选"规避提示。