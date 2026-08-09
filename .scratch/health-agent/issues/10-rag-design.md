# 10 RAG 技术选型与落点设计

- Type: grilling
- Status: open
- Blocked by: 09

## Question

**RAG 如何引入**（用户 2026-08-09 补充要求："根据用户需求找到可靠的信源"，技术选型待探讨）？前序会话已给出两个落点方向（见 `docs/research/frontend-backend-split.md` 与交接文档 §4.2）：

1. **候选召回层**：`meal_item` 文本向量化（菜名+描述+标签 → DashScope text-embedding），用户 query 嵌入后**语义召回 + 结构化过滤（hybrid）**，再进现有 LLM 重排（MealRankService 已存在）。只插一层，orchestrator 零改动。
2. **范围边界**：本票只设计餐食候选的混合检索。用户历史反馈由 17 号“类型化资源与偏好闭环”设计，属于偏好记忆与重排，不再混称为 RAG。

技术选型待决：
- **推荐基线**：导入阶段离线生成并持久化餐食 embedding，启动/刷新时加载归一化向量到进程内索引；MVP 规模使用 Java 暴力余弦即可。MySQL 是持久化源，不在每次请求中扫描 JSON/BLOB 计算向量。
- **技术 seam**：定义小型 `EmbeddingClient` 与 `MealRetriever` 接口，生产 adapter 调 DashScope embedding，测试 adapter 使用固定向量。项目已经使用 AgentScope，不为一个 embedding 调用额外引入整套 Spring AI；只有确认其兼容性和收益后才重开该选择。
- 嵌入模型：DashScope text-embedding 的型号选择、离线/降级策略（api-key 未配置时系统现状为降级可用——RAG 层同样需兜底）。
- **已确认**：作息事实表（08）走结构化条件检索并返回来源引用，不向量化；餐食混合检索是第一版唯一的 RAG 落点。
- hybrid 策略：过敏、来源、餐次等硬约束先过滤；结构化标签分与语义分归一合并，再进现有重排。embedding/API 失败时必须只走结构化检索，结果仍可用。
- 质量要求：离线标注查询集比较 structured-only 与 hybrid 的 Recall@K；如果没有可复现提升，不把 RAG 写成已完成卖点。

技能：/grilling。解决会话如需补查（Spring AI MySQL VectorStore 现状、DashScope embedding 接口）可先派 /research 子代理。

阻塞说明：向量化哪些 meal_item 字段、营养和份量约束如何过滤，取决于 09 号（meal_item 表结构改造）的最终 schema。
