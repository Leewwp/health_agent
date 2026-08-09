# 健康 agent 重构 — 地图

> Wayfinder 地图。本文件是索引，不是仓库：每个决策只存在于其 ticket 中。

## Destination

把当前"饮食 agent"扩展重构为**健康 agent** 并形成可直接交付实现的完整中文规格：统一对话入口覆盖饮食/健身/作息三品类，为一般成年用户生成可解释、可追溯、可持久化的**每周个人计划**（作息+餐食+推荐摄入区间+训练）；新增健身动作浏览页、餐食浏览页和我的计划页，形成类型明确的偏好闭环；规格包含与真实缺口对应的后端工程、质量保障和部署章节。终点 = 一份实现会话可直接执行、面向"Java 后端 + agent 开发"双定位简历的规格文档。

## Notes

- 全部规格/评论/代码注释用中文（AGENTS.md 约定）。
- **时间预算 1-2 周**；目标是公开部署。用户已有腾讯云 4C4G，但还需承载另一个更大项目；Vercel 仅作为静态前端候选，Java 后端与数据层的最终去向由 12、21 号票决定。
- 现有架构尽量**延伸而非重写**：intent→slot→clarify→recommend/plan→risk→persist 状态机、`diet_slot_option` 字典、`meal_item` 标签 JSON、单页 vanilla 前端。
- 已确认事实（charting 阶段调研）：健身数据集 = hasaneyldrm/exercises-dataset，1,324 动作，MIT 协议（媒体需保留 Gym visual 署名），含中文步骤说明与 GIF/缩略图，10 个部位、12 类器材，25% 为徒手动作。
- 工作 tickets 需调用技能：/grilling、/domain-modeling、/prototype、/research。
- **前序会话交接**（2026-08-09，`handoff-health-agent-frontend-split-rag-2026-08-09.md`）：前端方案 B 已拍板（见 07）；后端技术与 RAG 方向已有代码级核实证据（docs/research/frontend-backend-split.md、comparable-projects.md）。其 Flyway→方案B/CI→RAG 顺序仅作初稿，本次审计新增的健康档案、计划模型与安全校验必须先进入关键路径。
- **独立 GitHub 仓库已确认**：https://github.com/Leewwp/health_agent（含 pom/src/main/AGENTS.md）→ GitHub Actions CI 可行、部署拉取来源。父级备份仓库的 git 约束仅适用于本地工作区；独立仓库内无此约束。
- **已定原则（2026-08-09）**：自动化测试覆盖多品类编排、计划规则/风险校验与混合检索，其他接口和页面走冒烟；前端方案 B 实施时删除 static/ 双副本。
- **产品边界（2026-08-09）**：第一版只面向 18 岁以上的一般成年人；未成年人、孕期、进食障碍、急性伤病和需要医疗干预的慢性病诉求由风险守卫拦截，不生成具体计划。
- **数值原则（2026-08-09）**：推荐摄入量来自最小健康档案和明确公式，系统展示计算依据与估算性质；LLM 只解释和组合，不自行编造数值。
- **检索术语（2026-08-09）**：餐食混合语义召回称 RAG；作息采用结构化事实检索与来源引用；历史反馈采用偏好记忆与重排，三者不混称 RAG。
- **部署原则（2026-08-09）**：只部署一个后端实例；会话状态使用本地串行守卫，重复提交通过 requestId 幂等控制，不使用 Redis 分布式锁。具体托管平台与身份形态仍待决定。
- **前端原则（2026-08-09）**：不引入 Vue/React/Vite；保留 vanilla hash 路由，使用原生 ES Modules 按页面、领域和共享 UI 拆分。
- **计划交互下限（2026-08-09）**：周计划支持草稿、激活、历史版本和单餐/单动作替换；第一版不做拖拽排程与任意表格编辑。
- **Vercel 事实（2026-08-09）**：适合独立静态前端，不适合将当前 Spring Boot/MySQL/Redis 整栈作为生产默认方案；后端仍需传统容器、PaaS 或 VM。只迁前端不会明显释放 4C4G 服务器资源。
- **训练能力边界（2026-08-09）**：全量动作仅供浏览；计划只使用 20-40 个经过人工审核和补充元数据的入门动作，按版本化指南规则生成。LLM 不决定训练剂量与安全结论。
- git 注意：本仓库位于父级备份仓库 `/Users/pp/Desktop/file` 内，**只允许 stage `code/project/health-agent/...` 路径**。因此研究子代理不建 `research/<name>` 分支，改为直接写入 `.scratch/health-agent/research/<topic>.md`（对 wayfinder 技能中"throwaway branch"的本地化偏差）。
- 面试导向（用户 Q11）：规格要显式引入后端技术亮点，哪怕"华而不实"，只要理论上可行、讲得圆。

## Decisions so far

<!-- 索引：每行一条已关闭 ticket：名称（链接）+ 一句话要点。 -->

- [01 作息信息源调研](issues/01-routine-sources-research.md) — 15 个权威来源核验：睡眠时长（成人 7-9h、65+ 7-8h）、咖啡因睡前 6h 截止、午睡 20-30min 等可落库；**训练时段无普遍最优**（按昼夜节律匹配，禁止写死）；cssm.org.cn 为冒充域名需排雷。产出 research/routine-sources.md。
- [02 餐食数据集调研](issues/02-meal-dataset-research.md) — 无单一数据集满足全部标准（中文数据集均限研究用途）；**组合方案**：Food.com（CC0，营养+分类，导入 1000~3000 条）+ USDA FDC（公有领域校准）+ 自建中文菜名映射表；图片为迭代项。产出 research/meal-dataset.md。
- [07 前端页面形态与项目定位](issues/07-frontend-form-decision.md) — **方案 B 已拍板**：前后端分离（static/ → `frontend/`）+ Nginx 同源反代 `/api/`，vanilla hash 路由零改写，Docker Compose 部署。论证见 docs/research/frontend-backend-split.md。
- [06 后端技术栈展示方案](issues/06-backend-tech-showcase.md) — 只保留对应真实缺口的 Flyway、Redis 缓存、Compose、CI、OpenAPI、Actuator 与核心测试；会话锁和 requestId 幂等均为单实例本地实现；排除 MQ、Redis 分布式锁及 trace 异步化。
- [11 Vercel 部署适配调研](issues/11-vercel-deployment-research.md) — Vercel 只推荐托管静态前端；Spring Boot/MySQL/Redis 使用外部常驻运行环境，不为 Vercel 重写后端，也不以 Beta Java 容器作为生产方案。
- [14 训练计划能力边界与依据调研](issues/14-training-plan-evidence.md) — 动作数据集不含处方字段；MVP 只从 20-40 个审核动作按循证规则生成一般成年人入门活动计划，LLM 仅解释校验后的结果。

## Not yet specified

本次审计已将原雾区毕业为 11-21 号票。后续票据解决后若暴露新的范围，再按 frontier 逐步补充，不预先切实施任务。

## Out of scope

- **睡眠作为独立第三品类**（并入"作息"，Q2 决策）。
- **瑜伽作为独立品类**（徒手/柔韧类动作可覆盖部分场景；健身数据集无瑜伽专门分类）。
- **医疗诊断类内容**（延续现有 HEALTH_RISK 仅做风险提示，不做治疗建议）。
- **真实生产切换与凭证操作**（规格包含部署拓扑、资源预算、CI/CD 与回滚清单；服务器登录、DNS 切换和真实密钥配置留到实施阶段）。
- **移动端 App / 小程序**（仅 Web 页面）。
- **前端构建工具链与框架迁移**（不引入 Node/npm/Vite/Vue/React）。
- **第二个后端实例、Redis 分布式锁与消息队列**（单实例部署不具备真实需求）。
- **打卡、提醒、可穿戴设备同步与长期行为追踪**（第一版只生成、激活、查看和局部调整周计划）。

## 未决标记

- 12 用户身份与数据隔离——待用户在“匿名演示身份（推荐）”与“真实账号体系”之间确认；Vercel 只托管前端的事实已由 11 号调研锁定。
