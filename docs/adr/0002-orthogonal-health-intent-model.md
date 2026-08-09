# 使用正交的品类与任务模型

健康 agent 使用 `domain`、`task`、`riskFlags` 和会话 `phase` 表达意图与流程，不为饮食、健身、作息的每个组合增加独立枚举。综合周计划使用 `COMPOSITE + PLAN`，局部替换使用 `ADJUST`，以避免意图枚举、模式和状态机阶段重复表达同一概念。
