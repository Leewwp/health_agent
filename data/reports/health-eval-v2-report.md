# 健康评估报告 health-eval-v2

- schemaVersion: `health-eval-v2`
- 运行模式: `DETERMINISTIC_FIXTURE`
- 运行时间: 2026-08-13T22:02:15.434005
- Git commit: `6374494fd0a7bc50c088fc9f3f996170557d30a7`
- 数据集: `health-eval-v2-benchmark@1.0.0`（样本 36 条）
- 资源: FIXTURE_SEED / 版本 2026-08-10-v1
- 规则版本: 风险 2026-08-10-v1 / 档案 2026-08-12-profile-v1 / 计划 2026-08-12-plan-v2
- 模型: 主 deepseek-v4-flash-0731 / 轻 deepseek-v4-flash-0731
- Embedding/向量索引: 未使用（fixture 运行无 RAG 依赖）

## 状态

- 总样本: 36
- REVIEWED 进入评估: 36
- 排除（AMBIGUOUS_INPUT）: 0
- 无 gold: 0
- 缺 Trace 结构化事实: 0

## 核心指标

| 指标 | 值 | 有效分母 |
|---|---|---|
| domainAccuracy | 1.0000 | 32 |
| taskAccuracy | 1.0000 | 32 |
| domainTaskExactMatch | 1.0000 | 32 |
| slotExactMatch | 0.9375 | 32 |
| slotMicro.Precision | 0.9744 | 39 |
| slotMicro.Recall | 0.9744 | 39 |
| slotMicro.F1 | 0.9744 | 40 |
| riskAccuracy | 1.0000 | 32 |
| BLOCK_PLAN Recall | 1.0000 | 3 |
| risk.NORMAL.F1 | 1.0000 | 28 |
| risk.ADVISORY.F1 | 1.0000 | 1 |
| risk.BLOCK_PLAN.F1 | 1.0000 | 3 |
| clarifyDecisionAccuracy | 1.0000 | 29 |
| missingSlotF1 | 1.0000 | 4 |
| candidateCitationCompliance | 1.0000 | 23 |
| planValidationPassRate | 0.2500 | 4 |

## 分组（分品类）

- MEAL: domain 1.0000 / task 1.0000 / slotExact 0.9286 / slotF1 0.9767
- EXERCISE: domain 1.0000 / task 1.0000 / slotExact 1.0000 / slotF1 1.0000
- ROUTINE: domain 1.0000 / task 1.0000 / slotExact 0.8333 / slotF1 0.9091
- COMPOSITE: domain 1.0000 / task 1.0000 / slotExact 1.0000 / slotF1 null

## fallback 与延迟

fallback 分布（互斥主分类，共 32 条聊天样本）:

- NONE: 32

延迟（ms）: 正常 P50 9.0 / P95 21.0 / max 83.0（n=32）

## 用户反馈（#74 精确归因）

- adoptionRate: null
- positiveRate: null
- exactAttributionCount（EXACT_TRACE）: 0
- legacyFallbackCount（LEGACY_SESSION_FALLBACK）: 0
- FAVORITE/UNFAVORITE 不进满意度；旧 session 回退不进比例分母

## 说明

- 运行模式：DETERMINISTIC_FIXTURE——固定 Agent/resource fixture 与规则，无 API key/MySQL/Qdrant 也可运行，作普通回归
- 所有指标给有效分母；缺 gold 或结构化事实为 null 不算 0
- 计划样本（caseType=PLAN_VALIDATION）直接调用 PlanValidationService，不复制规则
- 用户反馈仅统计 #74 精确 trace 归因；FAVORITE/UNFAVORITE 不进满意度；本档位无真实用户反馈
- BENCHMARK 为单标注者两遍复核（labeledAt/reviewedAt/reviewStatus=REVIEWED），不声称多人标注
- 候选引用合规 = 最终展示 ID 全部属于本轮候选集，明细记违规 ID
- 风险阻断、正常澄清和无候选不算 fallback；REQUEST_FAILED 为最严重 fallback 主类

## 明细（部分）

| caseId | 状态 | 命中 |
|---|---|---|
| MEAL-01#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| MEAL-02#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| MEAL-03#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| MEAL-04#1 | EVALUATED | domain=✓ task=✓ slot=✗ risk=✓ |
| MEAL-05#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| MEAL-06#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| MEAL-07#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| MEAL-08#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| MEAL-09#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| MEAL-10#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| MEAL-C1#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| MEAL-C1#2 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| EX-01#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| EX-02#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| EX-03#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| EX-04#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| EX-05#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| EX-06#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| EX-07#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| EX-08#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| RT-01#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| RT-02#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| RT-03#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| RT-04#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| RT-05#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| RT-06#1 | EVALUATED | domain=✓ task=✓ slot=✗ risk=✓ |
| CP-01#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| CP-02#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| RISK-01#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| RISK-02#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| RISK-03#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| RISK-04#1 | EVALUATED | domain=✓ task=✓ slot=✓ risk=✓ |
| PLAN-01#1 | EVALUATED |  plan=✓ |
| PLAN-02#1 | EVALUATED |  plan=✓ |
| PLAN-03#1 | EVALUATED |  plan=✓ |
| PLAN-04#1 | EVALUATED |  plan=✓ |
