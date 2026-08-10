# 34 健康档案、周计划与风险校验

- Type: task
- Status: resolved
- Triage: ready-for-agent
- Depends on: 32, 33

## Scope

实现规格第 8、9 节：最小健康档案、确定性能量区间、周计划聚合与版本、三阶段风险校验和计划 API。

## Must do

- 建立健康档案及版本表，使用 Mifflin-St Jeor 和三档活动系数；
- 只计算每日能量区间，四舍五入到 50 kcal 并显示估算标记；
- 建立 `weekly_plan`、版本和项目表；
- 实现 DRAFT/ACTIVE/ARCHIVED、档案快照、ACTIVE 编辑复制 DRAFT 和历史版本；
- 实现周一至周日、本地时区、时间冲突、非连续训练部位和能量区间校验；
- 实现 `NORMAL/ADVISORY/BLOCK_PLAN` 和固定中文文案；
- 实现候选前、组合时、LLM 输出后三层 Guard，并复用 32 号 Agent 契约和失败分类；
- 实现档案、计划创建/查看/激活/编辑接口；
- 未校验计划不得持久化或激活，Agent 只解释已校验结果。

## Done when

核心自动化测试覆盖公式、缺失性别、计划版本、硬错误/警告、风险拒绝和降级；接口可完成生成 DRAFT、移动项目、保存备注和激活。浏览器验收属于 35 号。

## Answer

2026-08-10 完成验收（提交 34 号实现）：

- **档案与能量（规格 8.1）**：`V4__health_profile_and_plan.sql` 新增 `health_profile` / `health_profile_version` / `weekly_plan` / `weekly_plan_version` / `weekly_plan_item`。`EnergyCalculator` 实现 Mifflin-St Jeor（男 `+5`、女 `-161`）、三档活动系数（1.2/1.375/1.55）、目标调整（维持 ±5%、减脂 -5%~-15%、增重 +5%~+10%）、四舍五入到 50 kcal；生理性别缺失取男/女并集宽区间，`calcBasis` 固定文案标注估算。必填年龄/身高/体重/活动水平/主要目标，越界固定文案拒绝；每次保存递增版本并落 `health_profile_version` 快照。
- **计划生命周期（规格 6.3/8.2）**：DRAFT 生成→校验→持久化；HARD_ERROR 不落库、WARNING 可保存不可激活；激活前重新校验（OK 才允许），归档旧 ACTIVE 并快照项目到新版本；ACTIVE 编辑复制为新 DRAFT；PATCH 只允许日期/时间/备注且硬错误拒绝变更；时区缺省 `Asia/Shanghai`，周起始缺省本地下周一。
- **组合时校验（`PlanValidationService`）**：规则版本 `2026-08-10-plan-v1`，`UNDERAGE`/`SENIOR_TRAINING`/`SCHEDULE_OVERLAP`（跨午夜作息区间拆分比较）/`BODY_PART_CONSECUTIVE`/`RESOURCE_NOT_FOUND`/`RESOURCE_NOT_PLAN_READY`/`ENERGY_OUT_OF_RANGE`；BLOCK_PLAN=HARD_ERROR、ADVISORY=WARNING，多规则取最高，固定中文文案。
- **三层 Guard（规格 9）**：候选前 = `HealthRiskRuleService.assessProfile`（未满 18 岁、65 岁以上训练计划）；组合时 = `PlanValidationService`；LLM 输出后 = `PlanOutputGuard`（kcal 声明/医疗结论词/绝对化用语）+ 契约模块候选 ID 白名单。`HealthPlanResponseAgentService` 复用 32 号 `AgentContractModule` 与失败分类（TIMEOUT/UPSTREAM/INVALID_JSON/SCHEMA_VIOLATION/CANDIDATE_VIOLATION/MISSING_CONFIG），失败立即模板降级，解释附估算免责声明。
- **接口（规格 6.3/6.5）**：`GET/PUT /api/v1/health/profile`；`GET /plans`、`POST /plans/drafts`、`GET /plans/{id}`、`POST /plans/{id}/activate|edit`、`PATCH /plans/{id}/items/{itemId}`。`HealthApiException` 统一错误：BAD_REQUEST/NOT_FOUND/RISK_BLOCKED/CONFLICT/IDENTITY_INVALID/SERVICE_ERROR，RISK_BLOCKED 返回固定文案。计划解释 Trace 落 `diet_request_trace`（AGENT_CALL 携带 contractVersion/promptVersion/parseStatus/fallbackReason）。
- **组合器**：`WeeklyPlanComposerService` 周一至周日落位作息（R1 睡眠 23:00-07:00）、三餐（`MealPlanPicker` 按预算 30/40/30 就近选审核餐食）与训练（周一/三/五 19:30-21:00，主训练部位轮转不连续，训练焦点可选）；训练剂量 3 组×12 次由 Java 决定。

### 自动化覆盖（34 号）

| 场景 | 测试 |
|---|---|
| Mifflin-St Jeor 男/女/缺性别/活动系数/目标调整/舍入 | EnergyCalculatorTest（7 例） |
| 档案必填/越界/版本递增/快照/估算标记 | HealthProfileServiceTest（8 例） |
| 档案维度风险（未成年/高龄训练） | HealthRiskRuleServiceTest 扩展（4 例） |
| 组合时硬错误/警告/多规则取最高 | PlanValidationServiceTest（11 例） |
| 组合确定性（七天落位/部位轮转/焦点/空库降级） | WeeklyPlanComposerServiceTest（8 例） |
| 三餐预算挑选确定性/空库 | MealPlanPickerTest（3 例） |
| 生命周期（生成/警告/激活归档/编辑复制/PATCH 拒绝/CONFLICT/NOT_FOUND/排序） | WeeklyPlanServiceTest（14 例） |
| LLM 输出后 Guard（非法 JSON/kcal/医疗词/绝对化/越界/模板） | HealthPlanResponseAgentServiceTest（8 例） |

- 全量 192 个测试通过；本地 fixture 模式真实启动冒烟：V4 迁移成功、档案 GET/PUT（含缺性别宽区间）、DRAFT 生成（真实 DB 餐食）、PATCH 移动项目与备注、冲突修改返回 RISK_BLOCKED 固定文案、激活→旧计划归档、PATCH ACTIVE 返回 CONFLICT、ACTIVE 编辑复制新草稿、70 岁档案生成计划被 SENIOR 文案拦截、计划解释 Trace 落库且 AGENT_CALL 契约字段完整。浏览器验收属于 35 号。

### Code review 修正（提交前）

- 周起始缺省改为 `next(MONDAY)`（当天周一取下周，修复 nextOrSame 当日返回的偏差）；
- 激活/编辑复制时刷新计划档案依据（profileVersionNo/能量区间）与版本快照一致，并按规格 8.2 增加 `profileStale` 标记（当前档案版本新于计划生成依据时为 true，不静默重算）；
- PATCH 备注空字符串可清空；非周一 weekStart 直接 400 参数错误；
- 新增 `MEAL_TIME_OUT_OF_WINDOW` 规则（规格 8.2 餐食时间窗口校验，WARNING 可保存不可激活）；
- 文案去重（UNDERAGE/SENIOR_TRAINING 复用风险服务常量）、档案快照构建收敛到 `HealthProfileService.profileSnapshot`、移除未使用的 `findLatestVersion`、内联组合器中间委托。
