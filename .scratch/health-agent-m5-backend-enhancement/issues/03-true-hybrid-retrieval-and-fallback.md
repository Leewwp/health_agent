# P0 结构化召回与 Qdrant 向量召回融合及降级

- Type: task
- Status: resolved
- Triage: ready-for-agent
- Priority: P0
- Estimate: 1 天
- Blocked by: 02
- GitHub: https://github.com/Leewwp/health_agent/issues/52

## Question

如何把现有“结构化候选池内语义重排”升级为结构化召回与独立向量召回的候选融合，同时维持硬约束 100% 和稳定降级？

## Scope

- 分别执行结构化召回与 Qdrant 向量召回，再按确定性规则合并、去重和排序候选；
- 在 Qdrant payload filter 中应用可表达的审核状态、来源、过敏原和排除 ID 条件；
- 按 ID 回查 MySQL 后再次执行全部硬约束，Qdrant 结果不得绕过领域规则；
- Embedding/Qdrant 超时、不可用、维度不匹配或空结果时立即退回结构化检索；
- 用固定查询集记录 structured/hybrid Recall@3、硬约束命中率和降级结果，不要求或夸大效果提升；
- 为候选合并、顺序、二次校验和降级补充核心测试。

## Done when

真实 DashScope 查询向量能驱动一次 Qdrant 候选融合；硬约束命中率为 100%；Qdrant 不可用时接口仍返回结构化结果，现有推荐行为无破坏性回归。

## Answer

2026-08-12 完成（提交 0b4a077）：

- `HybridMealRetriever` 升级为结构化 + Qdrant 两条独立召回路径的确定性融合：向量召回 payload 过滤审核状态 APPROVED/来源 PUBLIC/过敏原/排除 ID，结构化召回独立执行。
- 融合后按 ID 回查 MySQL（`MealMapper.findApprovedPublicByIds`）二次执行全部硬约束，过期索引命中丢弃；融合分 = 0.5 × 归一结构分 + 0.5 × 语义余弦。
- `VectorFilter` 扩展 reviewStatus/sourceType must 过滤并统一 payload 键常量；Embedding/Qdrant 不可用、超时、维度不匹配或空结果时立即降级结构化并标记原因。
- 固定查询集记录 structured/hybrid Recall@3（真实验收 hybrid 0.387 vs structured 0.380）、硬约束命中率 1.0 和降级次数 0，如实记录提升幅度。
- 候选合并、顺序、二次校验和降级均有核心测试；全量 367 测试绿（含真实 Qdrant 集成）。

