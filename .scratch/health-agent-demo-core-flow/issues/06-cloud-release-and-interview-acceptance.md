# 06 — 云端发布与面试验收

Status: ready-for-agent

GitHub: [#89](https://github.com/Leewwp/health_agent/issues/89)

**What to build:** 将已经完成的核心能力发布到单实例云端环境，并用可重复脚本证明面试官能够完成餐食推荐、动作推荐、Agent 训练计划和 Trace 全流程。

**Blocked by:** 01 — 推荐主流程延迟与交互闭环；02 — 最小训练计划需求简报与确认；03 — 受约束 Agent 训练计划草稿闭环；04 — Trace 最小诊断工作台；05 — Qdrant 可选 Hybrid RAG 真实证据。

- [ ] Compose 保持 Nginx、Spring Boot、MySQL 单实例拓扑，不引入 Redis、队列或多实例；面试云端默认 `DIET_VECTORSTORE_MODE=qdrant` 并使用预先完成索引的当前 collection，但 Qdrant 不可用仍自动降级，不成为健康检查或核心流程硬依赖。
- [ ] 应用、代理和前端截止时间一致，模型或 Qdrant 故障时无代理 504、不可见写入或永久禁用控件。
- [ ] 干净数据库能够自动迁移、导入审核种子并达到健康 `UP`，管理员 API 在无 token 时被拒绝。
- [ ] 公网演示 URL 使用 HTTPS（可由云平台托管 TLS），`Secure` 匿名 Cookie 能在反代后往返且 `X-Forwarded-Proto` 正确；不以 HTTP 下临时关闭 Cookie 安全属性冒充 prod 验收。
- [ ] 普通全量测试、真实 MySQL 门控、Qdrant 门控或明确跳过理由、Compose 静态校验全部记录。
- [ ] 在部署 URL 使用真实 Chromium 从新匿名 Cookie 执行：餐食推荐 → 动作推荐 → 训练偏好 → 缺档案引导/保存/返回 → 确认 → Agent 生成 → 查看/激活 → 经演示入口鉴权后打开对应 Trace。
- [ ] 额外验证 Plan Agent 失败 fallback 和 Qdrant 不可用 structured fallback，用户仍得到可用结果。
- [ ] 记录客户端时长、服务端 Trace 时长、实际模型、生成来源、降级状态和主要页面截图，移除密钥与敏感内容。
- [ ] 发布后用独立匿名身份执行一次有界预热并区分冷/热请求证据；预热失败不影响健康检查或确定性主流程，也不污染正式面试会话。
- [ ] 一次性完成当前 embedding/collection 的生成与索引后关闭每次启动重建；部署重启验证 collection 可复用，主演示餐食 Trace 至少一次为真实 Hybrid，停用 Qdrant 后同请求为 Structured fallback。
- [ ] 完成一页式面试脚本、失败时演示退路，并更新 README/发布证据中已过期的“Agent 只解释计划”、模型默认值、API 路径和测试数字。
- [ ] 交付“简历主张 → 核心/支撑路径 → 代码位置 → 运行效果 → 测试/Trace/实跑证据 → 未采用方案及原因”矩阵；核心四项与既有 MCP/Skills 等非核心能力分层，不把存在但未进入演示路径的依赖写成核心效果。
