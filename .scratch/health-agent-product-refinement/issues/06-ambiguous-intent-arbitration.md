# 歧义意图仲裁

Type: task
Status: ready-for-agent
Blocked by: none
Priority: P1
Estimated complexity: 中
External dependency: 仅歧义场景需要模型

## Goal

在保留规则确定性的同时，正确处理多从句、否定和当前安排引用。

## Scope

- 领域限定归一“今晚/晚上”为晚餐等常用表达。
- 规则先抽取全部候选槽位。
- 仅在冲突值、否定、转折、多时间从句、指代、低置信度或会话冲突时调用 Agent。
- Agent 只仲裁焦点和槽位归属，结果仍经合法字典与 Java 校验。
- 增加“今晚清淡”“昨天没吃晚餐、今天丰盛早餐”“不想吃当前计划资源”等回归样例。

## Semantic Contract

仲裁结果不能只返回普通槽位。至少区分当前槽位、历史事实、请求级临时约束。临时约束包含 `excludedResourceRefs`、`excludedCategories`、`negatedSlots`、`effectiveDate` 和 `scope=REQUEST_ONLY`；历史事实只进入解释/仲裁上下文，不写入长期偏好或当前安排。

规则抽取已能确定的槽位必须在 Agent 失败时保留。Agent 只决定焦点、从句归属和否定范围，输出仍经过合法字典和 Java 校验。

## Acceptance

- “今晚想吃得清淡一点”一轮得到晚餐和清淡。
- 多从句和否定不把历史事实误当当前需求。
- 无歧义多槽位不产生不必要模型调用。
- 仲裁失败有确定性降级，不丢失可确认槽位。
- 无歧义路径不调用模型；模型超时或非法 JSON 时使用规则槽位 + 简短澄清，不阻塞推荐主流程。
