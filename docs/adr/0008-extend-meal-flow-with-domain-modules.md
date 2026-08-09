# 复用餐食状态机模式并按领域封装

健康 agent 复用现有餐食推荐的意图识别、槽位/档案合并、澄清、召回/排序、Agent 解释、风险和持久化模式，但不把餐食类型扩展成全局万能模型。现有餐食链路封装为 `MealModule`，健身和作息分别实现自己的领域模块，`WeeklyPlanComposer` 只负责跨领域组合与校验；这样可以保留餐食能力、降低新增领域的开发成本，同时避免 `DietOrchestratorService` 和 `SlotBundle` 继续膨胀。
