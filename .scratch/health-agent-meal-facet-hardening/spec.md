# 餐食标签数据与回归网加固

Status: ready-for-agent

前序规格：`../health-agent-meal-facet-repair/spec.md`（resolved）。本规格处理其交付后第三轮审查发现、并经独立核实的数据缺陷与回归网缺失。

## Problem Statement

上一轮修复把菜系/餐食类型的契约闭环了，但三个维度的遗漏会让"817 个绿测试"掩盖真实缺陷：

1. **种子数据本身是坏的**：ETL 生成三条历史人工补充餐食的标签时，把单个词当作字符序列展开，种子里落成了 `["粤","菜"]` 这类单字碎片。这三行标签非空，启动兜底永不修正；本地旧库因迁移期轮换显示正常、全新库（CI/部署）会带着碎片入库，菜系筛选永远命中不到它们。
2. **受版本控制的种子不承载真实标签**：295 行里菜系空 276 行、餐食类型空 246 行，真正进库的标签由启动逻辑按自增主键轮换伪造。后果：全新库与旧库对同一道菜给出不同菜系（前序规格"逐行收敛"的承诺实际不可达）；manifest 哈希对应的是空 facet 种子，无法证明库内容；只导种子不启动应用的场景（备份恢复、离线分析）没有任何标签。
3. **新增 SQL 没有回归网**：本轮新增的三条带餐食类型的查询语句在测试代码中零执行——正是"动态 SQL 引用未声明参数导致运行时 500"这同一类缺陷，现在没有测试能挡住它复发；种子校验测试只平移了列下标，没有任何 facet 断言；规格承诺的 fresh-schema 验证（release blocker）只有一次浏览器手工观察。
4. **证据与契约同步回退**：取消传播测试被放宽到"取消失败也能通过"，失去证明力；路由判断仍读旧单值菜系字段，"我想吃素"会被判为餐食侧无内容；餐食类型没有未支持值诚实通道；残留单选注释与死代码；AGENTS.md/README 仍写 V1..V20 与旧测试基线；验收证据缺移动端视口与构建标识。

## Solution

一次以"数据即契约"为核心的加固：**ETL 成为标签的唯一事实源**——每一条种子餐食都携带最终、非空、词表内的菜系与餐食类型，数据可推导的用数据，推导不出的用稳定来源键轮换（绝不依赖自增 id）；修复碎片化缺陷并让静态校验能挡住它；新增全新库"迁移→导种→投影比对"的集成测试，逐行对比新旧两库的 facet 结果；三条新语句经由现有 Reader/Controller 真实 MySQL 接缝全部执行；合并重复语句族消除写法分叉；取消测试恢复可证伪；路由、注释、死代码、项目契约文档与验收证据同步收口。

## User Stories

1. As a deployer, I want every seed meal row to carry final, non-empty, in-vocabulary cuisine and food-type labels, so that a freshly migrated database matches the repaired legacy database row for row.
2. As a deployer, I want facet labels derived from stable source keys rather than auto-increment ids, so that re-import order or id drift cannot change a meal's classification.
3. As a CI maintainer, I want the three food-type query statements executed by real-MySQL tests, so that an undeclared-parameter regression fails in CI instead of in production.
4. As a CI maintainer, I want seed validation to reject empty facets, out-of-vocabulary values, and single-character fragments, so that a generator bug cannot ship inside a green build.
5. As a CI maintainer, I want a fresh-schema integration test that imports the seed and compares the facet projection against the legacy cohort, so that "convergence" is a tested invariant rather than a claim.
6. As a data maintainer, I want ETL reruns to produce a byte-identical seed for the same input, so that regeneration never drifts from the published baseline.
7. As a data maintainer, I want the manually supplemented meals to be explicit curated input rather than read back from the published seed, so that the generator is self-contained and cannot silently lose them on a baseline switch.
8. As a meal-library user, I want cuisine and food-type filters to match every reviewed meal according to its recorded facet contract, including the manually supplemented ones, so that no visible filter is a dead option.
9. As a meal-library user, I want counts and rows to come from one consolidated parameter-complete query family, so that pagination totals can never disagree with results.
10. As a plan user, I want my stated food-type preference (supported or not) to survive brief updates and restarts, so that honesty survives the whole lifecycle.
11. As a plan user, I want an explicit unsupported food type to be registered and disclosed exactly like an unsupported cuisine, so that the system never silently drops what I said.
12. As a plan user, I want the routing layer to recognize food-type-only briefs as meal-side content, so that escape hatches and side routing do not misroute "我想吃素".
13. As an agent, I want the vocabulary defined in exactly one place and mirrored everywhere by a drift-guard test, so that prompts, slot options, normalizer, seed, and frontend can never diverge again.
14. As a reviewer, I want the cancellation test to fail loudly if HTTP cancellation breaks, so that "outer timeout works" remains a proven property, not a tautology.
15. As a reviewer, I want frontend summary assertions to test rendered output for arrays and unsupported values, so that 40 green frontend tests mean what they say.
16. As a reviewer, I want acceptance evidence to include the mobile viewport measurement and a build/commit identifier, so that historical and current evidence are distinguishable.
17. As a new contributor or agent session, I want AGENTS.md and README to describe V1–V24, the food-type field, and the current test baseline, so that project contracts match reality.
18. As a maintainer, I want stale single-select comments and dead parsing helpers removed, so that the code no longer contradicts the confirmed multi-select contract.
19. As a demo presenter, I want "烧烤" to remain a reachable food-type value in the vocabulary and data rules, so that the documented type set is fully exercisable.
20. As a maintainer, I want the label rotate rule to exist in exactly one canonical definition referenced by ETL and the startup backstop, so that adding a cuisine does not require hunting three copies.

## Implementation Decisions

- **标签唯一事实源是 ETL**：每条生成的餐食行都写出最终标签。数据可推导的 facet 用数据；推导不出的只能按本规格定义的稳定来源键规则生成演示分类，且不得使用自增主键。稳定键为规范化后的 `source_name + "\\0" + source_id`：纯十进制 `source_id` 用任意精度十进制取模，其他值用 UTF-8 CRC32 的无符号值取模；Python、Java、MySQL 必须逐字节对齐。`source_id` 为空的旧行不得猜测分类：保留合法既有标签，若标签为空或非法则迁移失败并报告，不得回退到自增 id。
- **演示分类不得冒充事实**：稳定键轮换只允许用于本已批准的演示语料的缺失标签，报告和 manifest 必须记录 `facetSource=STABLE_KEY_DEMO`；API、页面、Agent 文案不得把该标签描述为经过人工考证的地域事实。若将语料用于真实地域筛选，必须先提供人工标签并移除演示分类。
- **修复标量入词表的序列化缺陷**：单个词必须成为单元素 JSON 数组；为该函数加最小单元防护（字符串入参 → 包裹为数组）。
- **三条历史人工补充餐食成为显式策展输入**（进入 `scripts/meal_curation/manual_meals.json`，与公开输入池并列）。文件必须包含可重建 INSERT 的完整字段、来源版本和最终 facet；ETL 不得读取自己发布的 seed，且该文件的 hash 必须进入 ETL 报告和 manifest。
- **启动导入的兜底补齐降级为不变量执行者**：只填空；遇到非空但非法的 facet 必须失败并报警，不能默默覆盖；其稳定键实现必须与 ETL 共用同一份规范算法。兜底不得为缺少 `source_id` 的旧行按自增 id 生成标签。
- **合并语句族**：检索、浏览、计数各保留一条参数完整的语句，餐食类型参数恒定声明、空列表即不过滤；谓词顺序一致并统一三餐兼容表达式的括号写法；删除所有成对的 WithFoodType 变体。
- **词表单一化**：canonical 文件为版本化的 `data/meal/facets.json`，包含有序 cuisine/foodType 列表及版本/hash。ETL、Java 归一器、启动兜底和前端资源由该文件生成或读取；提示词中的列表由生成脚本写入。漂移测试比较文件 hash、顺序和所有生成物，不能只比较集合。
- **餐食类型的诚实通道**：只有显式类型形态（`餐食类型：X`、`餐食类型是 X`、`想吃 X`、`X 类型`）才允许登记词表外原值；支持中文标点、和/或/以及、多值去重、否定范围和“换成/改为”替换。词表外值保留在 `foodTypes` 并登记一次 `foodType:<value>`，不参与筛选或生成；正向/否定/替换语义与 cuisine 对称，具体边界写入 ADR-0017。
- **路由消费列表字段**：餐食侧内容判定改为检查 `cuisines` 与 `foodTypes`（及既有字段），不再经由旧单值兼容访问器。
- **取消测试恢复可证伪**：服务端延迟提高到远超断言阈值的量级，断言收紧回近取消路径的耗时；保留请求延后创建的语义。
- **前端摘要测试行为化**：断言渲染结果包含多个菜系、多个类型与未支持项及空态，不再对源码做正则匹配。
- **文档与注释收口**：AGENTS.md 与 README 更新为 V1–V24、foodType 字段、facet provenance 与新测试基线；清除类注释中全部"单选"残留；删除无调用点的单值别名解析辅助函数；ADR-0017 增补稳定键算法、演示分类边界与类型未支持通道三条决策。
- **manifest 与索引同步**：seed facet 变化视为新语料版本，必须生成新的 corpus/resource version（不得覆盖冻结的 `current-corpus-v1`），重新生成 ETL 报告、source/content hash，并重新生成包含 `food_type` payload 的审核餐食向量索引；相关 RAG/评估报告要么重跑并绑定新版本，要么明确标记为历史证据，不得继续宣称当前语料结果。
- **验收证据标准**：每次浏览器验收必须记录桌面与移动（1440×900 / 390×844）的横向溢出测量、构建标识（commit sha 或构建时间戳）、请求输入与关键字段。

## Testing Decisions

接缝沿用前序规格并经用户确认的五处，并新增 fresh-schema 与旧饮食链路两处回归接缝：

- **静态种子校验**（既有接缝，扩展）：新增 facet 断言——295 行全部非空数组、取值全部落在 13/11 词表、无单字碎片、烧烤可达、三条人工记录标签与 canonical 稳定键规则一致；解析 INSERT 的列名而不是依赖易漂移的列下标；同时校验 `facetSource` 报告和 manifest 已更新。该接缝必须能独立挡住本规格的第 1 号缺陷。
- **全新库集成接缝**（MySQL 门控，新增，本规格的最高价值测试）：干净测试库执行 V1–V24 全部迁移（V24 为新增纠正迁移）→ 导入 seed → 断言 295 个 `(source_name, source_id)` 全部存在且无重复/静默跳过 → 校验行级 facet 不变量（非空、词表内、非碎片）→ 与旧库迁移前快照按复合 source key 逐行比对 facet 投影。旧 V1 基线中 `source_id` 为空的行单独验证为“保留合法标签或迁移失败”，不得混入 295 行 cohort。
- **审核餐食 Reader/Controller 接缝**（MySQL 门控，扩展）：插入行携带全部新非空字段后，经接口路径执行合并后的检索/浏览/计数语句——覆盖带与不带 foodType、cuisine+foodType 组合、全部餐次取值含三餐、名称搜索、收藏、分页 count 与行数一致。明确删除并断言不存在旧 `WithFoodType` 方法族；三条新语句由此获得真实执行。
- **旧饮食链路接缝**（MySQL 门控，新增回归）：通过 `/api/v1/diet/**` 创建、更新、无 foodType 的旧请求和搜索，确认共享 mapper 合并后仍写入合法 `[]`，不因 `food_type NOT NULL` 产生 500。
- **健康聊天 API 接缝**（既有）：补充类型显式支持/未支持/否定/替换边界、类型简报被判为餐食侧内容、重启恢复数组和 unsupported 集合完整性。
- **LLM 客户端接缝**（既有）：服务端暴露请求是否被中止的可观测信号；测试断言 future/连接确实取消，耗时阈值只作为辅助而非唯一证明。
- 全部断言只测外部行为；前端用 Node 行为测试断言渲染输出，禁止源码正则。基线更新后 AGENTS.md 的测试计数随之修订。

foodType 未支持通道的最小行为矩阵必须包含：`餐食类型：生酮` 登记 `foodType:生酮` 且不参与筛选；`想吃素和生酮` 只把素食写入支持列表并登记生酮；`不想吃生酮` 不写入正向列表；`餐食类型换成生酮` 替换旧类型并保留其他字段；重复、空白和中文标点去重；模型 raw slot 中未被显式解析器认可的未知值仍丢弃。

完成门槛：编译通过；全量测试无非门控失败；MySQL 门控绿；前端行为测试绿；fresh-schema V1–V24 投影比对绿；seed source key 覆盖率 295/295 且无 `INSERT IGNORE` 静默缺行；同输入、同 canonical facet 文件、同 manual input 下 seed SQL 逐字节一致（报告中的生成时间字段不参与该断言）；manifest、向量索引和评估版本一致；浏览器验收含移动端与构建标识。

## Out of Scope

- 不新增词表词汇、不把"中餐"变为受支持值、不扩充审核数据集。
- 不修改已应用的 V1–V23 迁移历史；新增 V24 纠正迁移。旧库差异一律通过 V24 或启动兜底处理，且不得用自增主键补造缺少稳定来源键的事实。
- 不引入新的推荐模型或检索系统；为兑现现有 foodType 硬过滤契约，允许同步现有向量 payload、重建索引和更新版本元数据。风险规则、计划生命周期、反馈归因与安全边界不变。
- 不重开前序 resolved 规格的已闭环决策（多选语义、未支持菜系保留、三餐兼容等），只做本规格列出的加固。
- 三份未跟踪简历文件继续排除在外，不删除不修改。

## Further Notes

- 第三轮审查的 15 项发现经逐项核实全部成立（精确数字：cuisine 空 276/295、food_type 空 246/295、单字碎片 3 行）；唯一表述偏差是"参数 500 修复靠 mapper 显式声明"——实际机制是移除旧语句中未声明的引用，不影响结论。
- 本地旧库"看起来正常"是因为迁移期轮换已就地修复；版本化种子仍含碎片，且任何已存在的非空非法 facet 都必须由 V24 显式修复，不能依赖启动兜底。fresh-schema 与 legacy pre/post 快照测试因此都是发布阻塞项。
- V24 是本规格新增的唯一纠正迁移；fresh-schema、Flyway 断言、AGENTS、README 和发布证据必须统一写 V1–V24。V1–V23 文件保持不可变。
- 稳定键的输入必须是复合来源身份，不能简化为数据库自增 id 或裸 `source_id`。缺少稳定来源身份的旧行不在 295 行 seed cohort 内，必须保留合法事实或让迁移失败并给出行级报告。
- 稳定键轮换产生的是演示分类，不是事实标注。任何将其用于真实地域筛选或对外声称“菜系事实”的需求都必须先完成逐行人工策展；本规格不把轮换结果包装成真实来源证据。
- 取消测试放宽的直接动机是机器负载下的偶发失败；本规格以"拉开服务端延迟量级"的方式同时解决可证伪性与稳定性，而不是放宽断言。
- 两条时序敏感测试（取消传播、慢模型事务边界）在负载下可能假红；本规格收口取消测试后，若慢模型用例仍偶发，允许为其加入重试包裹，但阈值语义不得放宽。
