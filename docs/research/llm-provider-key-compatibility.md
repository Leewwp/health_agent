# LLM 供应商密钥与 AgentScope/Qdrant 兼容性研究

研究日期：2026-08-10  
研究范围：本项目 `agentscope-spring-boot-starter:1.0.11`、聊天模型切换、餐食 Embedding 生成和 Qdrant 接入。

## 结论摘要

用户手里的 DeepSeek API key 或 MiniMax Coding Plan 凭证不能直接替换当前配置中的
`DASHSCOPE_API_KEY`，也不能仅靠修改 `DASHSCOPE_BASE_URL` 就让现有
`DashScopeChatModel`/`DashScopeTextEmbedding` 变成通用供应商客户端。

最快的可行路线是：

1. 聊天若使用 DeepSeek 或 MiniMax，改用 AgentScope 已提供的
   `OpenAIChatModel`（OpenAI-compatible wire format），为每个供应商配置独立的
   `baseUrl`、模型名和 key。
2. Embedding 仍使用 DashScope 的原生 Embedding API；只有在拿到 MiniMax 官方明确的
   Embedding endpoint 后，才考虑另写 MiniMax 专用适配器。
   DeepSeek key 本身不能完成餐食向量化，因为 DeepSeek 官方公开 API 文档只定义聊天
   与相关生成能力，没有可供本项目使用的 Embeddings API。
3. Qdrant 与模型供应商无关，但一个 collection 的向量维度/距离必须固定；切换模型时
   应按 `provider + model + dimension` 建新 collection 并重建索引，不能混写旧向量。

## 本项目与 AgentScope 的可验证事实

- `pom.xml` 固定使用 AgentScope Java `1.0.11`。
- `DietAgentScopeConfig` 通过 `DashScopeChatModel.builder()` 注入 `apiKey`、
  `modelName` 和 `baseUrl`；`DashScopeEmbeddingClient` 通过
  `DashScopeTextEmbedding.builder()` 注入相同的 key/base URL，并把维度写入
  `meal_item_embedding`。
- AgentScope 1.0.11 的 `DashScopeHttpClient` 选择的是 DashScope 原生路径
  `/api/v1/services/aigc/text-generation/generation`（文本）或
  `/api/v1/services/aigc/multimodal-generation/generation`，并使用 DashScope 专用
  request/response formatter。因此它不是一个只改 URL 就能复用的 OpenAI-compatible
  客户端。
- 同一版本另外提供 `OpenAIChatModel`，默认调用 `/v1/chat/completions`，并提供
  `baseUrl`/`endpointPath` builder；还提供 `OpenAITextEmbedding`，按标准 OpenAI
  Embeddings 请求发送 `input`，并读取 `data[].embedding`。这些类型是更合理的
  DeepSeek/MiniMax 聊天适配起点。

本节的 SDK 行为来自本地 Maven artifact `io.agentscope:agentscope:1.0.11` 的公开类和
字节码反编译；官方文档的模型对照见 [AgentScope Java 模型文档](https://java.agentscope.io/v1/en/docs/task/model.html)，源码入口见 [AgentScope Java 仓库](https://github.com/agentscope-ai/agentscope-java)。

## 供应商逐项判断

| 凭证/供应商 | 当前 `DashScopeChatModel` | AgentScope `OpenAIChatModel` | Embedding | 判断 |
|---|---|---|---|---|
| DashScope API key | 可以，但 base URL 必须匹配 DashScope 原生 SDK/端点 | 可以使用 DashScope compatible-mode，但需确认模型和 JSON/tool 能力 | `DashScopeTextEmbedding` 原生支持 | 一周内最省改动；聊天和 embedding 可保持同一供应商 |
| DeepSeek API key | 不可以仅换 key；请求路径和 payload 不匹配 | 可以，使用 DeepSeek 官方 OpenAI-compatible 聊天端点和 `deepseek-chat`/`deepseek-reasoner` | 官方公开 API 文档没有 Embeddings API | 只能作为聊天供应商；Qdrant 向量仍需另一家 embedding 服务或本地模型 |
| MiniMax Open Platform API key | 不可以仅换 key；不是 DashScope wire format | 聊天可用 OpenAI-compatible 端点，需按区域选择 `api.minimaxi.com`/`api.minimax.io` 并实测模型能力 | 官方 API 目录当前未列可用 Embeddings API | 向量仍应使用 DashScope/第三方；不要凭聊天 key 假设有 embedding |
| MiniMax Coding Plan 凭证 | 不应视为普通 DashScope/Open Platform key | 只有当该凭证对应的官方 endpoint 和认证头是 OpenAI-compatible 时才可复用；Coding Plan 常见配置面向 Coding Agent/Anthropic-compatible endpoint | 不构成 embedding 能力承诺 | 先确认套餐文档给出的 endpoint、header、限额；不能拿订阅凭证盲替 `DASHSCOPE_API_KEY` |

### DeepSeek

DeepSeek 官方 API 以 OpenAI 兼容的 Chat Completions 为主，官方示例使用
`Authorization: Bearer <DEEPSEEK_API_KEY>`、`https://api.deepseek.com`（或带 `/v1` 的
兼容 base URL）和 `deepseek-chat`/`deepseek-reasoner`。因此 Java 侧应改为
`OpenAIChatModel`，而不是继续实例化 `DashScopeChatModel`。

官方资料：

- [DeepSeek API 文档首页](https://api-docs.deepseek.com/)
- [Create Chat Completion](https://api-docs.deepseek.com/api/create-chat-completion)
- [DeepSeek Function Calling 指南](https://api-docs.deepseek.com/guides/function_calling)
- [DeepSeek 模型列表 API](https://api-docs.deepseek.com/api/list-models/)
- [DeepSeek 定价/模型能力](https://api-docs.deepseek.com/quick_start/pricing/)

截至本研究，在官方 API 文档导航和 API reference 中没有找到可供本项目调用的 Embeddings
创建接口。结论是“当前 DeepSeek key 不能单独完成 Qdrant ingestion”，而不是断言
DeepSeek 永远不会提供 embedding；拿到 key 后仍应以 `/models` 和官方 API reference 做一次
实际探测。

### MiniMax 与 Coding Plan

MiniMax Open Platform 文档同时提供原生 API 与 OpenAI/Anthropic 兼容入口；兼容入口仍要求
使用平台认可的 API key、正确区域 base URL 和模型名。MiniMax 的 Coding Plan 是套餐/产品
授权，不能从“我有 Coding Plan”推导出“任意 OpenAI SDK 都能使用”。实际接入前必须从
套餐文档确认：

- key 的签发位置和认证 header（`Authorization` 还是供应商专用 header）；
- endpoint 是 OpenAI `/v1/chat/completions` 还是 Anthropic `/anthropic/...`；
- 套餐额度是否覆盖 API 调用，以及是否允许 embedding 调用。

官方资料入口：

- [MiniMax API 文档](https://platform.minimaxi.com/docs/api-reference)
- [MiniMax 文本生成/API 参考](https://platform.minimaxi.com/docs/api-reference/text-generation)
- [MiniMax OpenAI 兼容文本 API](https://platform.minimaxi.com/docs/api-reference/text-openai-api)
- [MiniMax Embeddings/API 参考](https://platform.minimaxi.com/docs/api-reference/embeddings)
- [MiniMax Coding Plan 文档](https://platform.minimax.io/docs/coding-plan/overview)
- [MiniMax Token Plan 的其他工具接入](https://platform.minimaxi.com/docs/token-plan/other-tools)

MiniMax Token/Coding Plan 文档把订阅 key（常见 `sk-cp-` 前缀）和普通开放平台按量 key
区分；前者可以有 OpenAI-compatible endpoint，但额度、速率和生产适用性由套餐决定。

不同区域和产品线的 MiniMax 文档域名/路径可能不同；若上面的入口发生跳转，应以控制台
当前显示的 endpoint、认证方式和模型列表为准。

MiniMax 官方 API 目录当前没有 Embeddings 条目，因此不能把聊天 API 的兼容性外推到向量
API。若后续 MiniMax 文档明确提供专用 Embeddings endpoint，现有 AgentScope
`OpenAITextEmbedding` 只理解 OpenAI 标准 `input` + `data[].embedding`，届时必须根据
官方 payload/response 新建 `MiniMaxEmbeddingClient`，不能只替换类名或 URL。

## 对 Qdrant 一周实现的影响

Qdrant adapter 应保持以下边界：

```text
EmbeddingClient -> 向量与 provider/model/dimension 元数据
                 -> Qdrant upsert(point id=mealId, vector, payload=meal filters)
MealRetriever    -> query embedding -> Qdrant search + payload hard-filter
```

- DashScope：复用当前 `EmbeddingClient`，修正原生 Embedding 的 base URL 配置；生成
  1024 维（当前配置）向量后写入新 collection。若保留 MySQL JSON 向量作回退，Qdrant
  只是可重建的召回索引。
- DeepSeek：聊天可走 `OpenAIChatModel`，embedding 必须继续使用 DashScope 或第三方
  embedding provider；collection 的模型元数据必须记录实际 embedding provider，不能写
  成 DeepSeek。
- MiniMax：聊天可走 `OpenAIChatModel`（前提是拿到 OpenAI-compatible API key/endpoint）；
  官方当前未提供可确认的 embedding API，因此向量仍用 DashScope/第三方。只有实际拿到
  官方 embedding endpoint 后，才创建专用 HTTP adapter，并以实际向量长度创建 collection。

切换 embedding 模型时的最低迁移动作：停止混合写入、按新维度创建 collection、批量重算
全部审核餐食、记录 `provider/model/version/dimension`，再切换检索路由。旧向量保留一段
时间用于回退或对比评测。

Qdrant 官方说明：[Collections](https://qdrant.tech/documentation/manage-data/collections/)
要求 collection 的向量 size 和 distance 固定；[Points](https://qdrant.tech/documentation/concepts/points/)
支持按点 ID 批量 upsert，同 ID 会覆盖，因此可安全做幂等的离线回填。

DashScope embedding 的模型、维度和预计算建议见 [阿里云 Model Studio Embedding](https://help.aliyun.com/zh/model-studio/embedding)。

## 推荐决策

面试作品的一周冲刺优先使用 **DashScope key 做 embedding + Qdrant**，如需展示多供应商，
再把聊天模型抽象到 `OpenAIChatModel` 并用 DeepSeek 或 MiniMax 做 smoke test。不要把
MiniMax Coding Plan 凭证当成已验证的普通 API key；它应作为单独的鉴权/endpoint 适配决策，
待拿到官方套餐凭证和成功的 `/models`、chat、embedding 实测结果后再写入开发计划。
