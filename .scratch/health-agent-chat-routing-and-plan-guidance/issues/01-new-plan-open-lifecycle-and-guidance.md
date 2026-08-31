# 01: 新建计划消费的生命周期原子转移与用户引导（RC-1）

**What to build:** 用户在“修改当前计划还是新建”澄清中选择“新建”（点击选项或文字回答）后，系统在同一轮原子建立“对应侧简报 OPEN + 空简报 + 会话意图=(对应域, PLAN)”的持久状态，并以“接下来开始新建一份训练计划/餐食计划”开场、紧接着点名第一个缺失必要条件（如训练目标/用餐时段）；此后“周一到周三”“下午六点到七点”等字段短答稳定留在 `PLAN` 收集流程，不再因会话残留的 GENERATED/PAUSED 生命周期落入通用意图链。餐食与训练两侧同规。同时把生命周期“task==PLAN 推导 OPEN”的双份实现收敛为单一事实源。

**Blocked by:** None (can start immediately)

**Status:** resolved（2026-08-31 实施完成：`consumePlanClarifyReply` 两侧 redo 分支同轮原子写 OPEN+空简报+(侧,PLAN)，`PlanClarifyConsumption` 携带生命周期转移；开场文案“接下来开始新建一份训练/餐食计划”+点名首个缺失条件；生命周期推导收敛为 `HealthBriefRouter.resolveLifecycle` 单一事实源；回归种子 GENERATED 前置（`ChatRoutingAndPlanGuidanceOrchestratorTest` 两侧链路）；浏览器验收：真实库有启用训练计划 → 新建 → “周一到周三”“下午六点到七点”全程 EXERCISE+PLAN 且字段入简报）

- [ ] 种子该侧 `GENERATED` 生命周期的会话中，选择“新建”后落库状态为 OPEN+空简报+(侧, PLAN)；下一轮“周一到周三”保持 `EXERCISE + PLAN`、训练日写入简报、无任何资源块（P0 哨兵场景）
- [ ] 餐食侧等价链路：种子 `GENERATED/PAUSED` → 新建 → 裸“早餐”类短答保持 `MEAL + PLAN` 且字段入简报；可见文案用“开始新建一份餐食计划”，不出现“简报”“当前字段”内部术语
- [ ] 新建开场回复点名第一个缺失必要条件（训练目标/用餐时段），不再出现“请直接补充当前字段”
- [ ] 消费轮清空旧推荐槽位与生命周期转移同轮原子生效；并发生成回写 GENERATED 与澄清消费转移 OPEN 的 `mergeLifecycle` 交互不互相覆盖出错误状态（轻量并发回归）
- [ ] 生命周期“task==PLAN 推导 OPEN”只存在单一实现，路由判定与会话回读共用
- [ ] 回归前置明确种子非 OPEN 生命周期——从全新会话起步的用例须注释说明会被推导掩盖（历史假绿机制）
- [ ] 已有 GENERATED 不捕获裸字段值的既有断言保持通过（已完结流程语义不回退）
- [ ] Trace 记录“新建”消费触发的生命周期转移与下一轮简报激活原因，可诊断

**规范来源:** spec.md「Implementation Decisions · 计划引导与生命周期」澄清消费转移点/单一事实源两条、Testing Decisions 种子前置回归、Further Notes 假绿机制。
