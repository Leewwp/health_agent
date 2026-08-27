# MiniMax Embeddings API 核对

研究日期：2026-08-26
目的：为本项目评估 `embo-01` 向量接入提供可复核的接口边界。本文不包含任何 API key、group id 或真实凭证。

## 结论摘要

- 当前可探测到的 Embeddings 路径是 `POST https://api.minimaxi.com/v1/embeddings`。同一路径在 `api.minimax.io` 和旧域名 `api.minimax.chat` 也返回 MiniMax API 的 JSON 业务响应（仅验证端点可达和错误包格式，未使用凭证）；但当前官方 API Reference 的 canonical server URL 是 `https://api.minimaxi.com`，因此新配置应优先使用 `.com`。
- 请求使用 Bearer API key 和 JSON body：`model`、`type`、`texts`。`type` 对文档/库文本使用 `db`，对查询文本使用 `query`。
- 返回体不是 OpenAI 标准的 `data[].embedding`，而是 MiniMax 原生格式：`vectors`、`model`、`total_tokens`，并带 `base_resp.status_code/status_msg` 业务状态。HTTP 200 不能单独证明调用成功。
- `embo-01` 的向量长度在 Spring AI 的 MiniMax API 集成测试中验证为 1536；MiniMax 当前公开文档站的可抓取索引未给出维度表，因此接入时仍应以首次成功响应动态校验，并以 `provider/model/dimension` 生成独立 collection identity。
- `GroupId` 是旧版 MiniMax embedding 客户端广泛使用的**查询参数**，不是 JSON 字段。当前官方 OpenAI 兼容文档只明确 Bearer API key，未明确声明 Embeddings 是否仍强制要求 `GroupId`。适配器应支持可选 `GroupId`，但必须用实际账号 smoke test 决定是否必填。
- 当前官方速率限制页面只列语言、视频、语音、图片和音乐等产品的 RPM/TPM，没有 `embo-01` 的公开专属数值。实现应按可配置批量、指数退避和业务错误检查处理，不能自行声称 MiniMax embedding 的 RPM/TPM。

## 已核对的官方资料

### API 参考与模型入口

- [MiniMax API Reference 总入口](https://platform.minimaxi.com/docs/api-reference)
- [MiniMax Embeddings（旧版官方文档入口）](https://www.minimaxi.com/document/guides/Embeddings)
- [MiniMax 当前 OpenAI Chat API 参考](https://platform.minimaxi.com/docs/api-reference/text-chat-openai)：该页面的 OpenAPI server 为 `https://api.minimaxi.com`，认证方案为 `Authorization: Bearer API_key`。它可作为当前域名和认证风格的官方佐证，但不是 Embeddings 的完整 schema。
- [MiniMax 模型列表（OpenAI 兼容）](https://platform.minimaxi.com/docs/api-reference/models/openai/list-models)：当前官方示例使用 `GET https://api.minimaxi.com/v1/models` 和 Bearer API key。
- [MiniMax 速率限制](https://platform.minimaxi.com/docs/guides/rate-limits)：官方说明 RPM/TPM 按模型、接口和账户类型决定；当前页面没有列出 `embo-01` 的专属行。

官方文档站在 2026-08-26 的 `llms.txt`/`llms-full.txt` 索引中没有 Embeddings 页面，旧路径
`https://platform.minimaxi.com/docs/api-reference/embeddings` 当前返回 404。因此下面的 Embeddings
字段与维度，除旧版官方入口外，还列出可审计的 SDK 实现作为交叉证据，并明确标注其证据等级。

## 请求契约

### HTTP

```http
POST https://api.minimaxi.com/v1/embeddings
Authorization: Bearer <MINIMAX_API_KEY>
Content-Type: application/json
```

MiniMax API 在无效认证时仍可能返回 HTTP 200，同时在 JSON 的 `base_resp` 中返回错误；例如对公开端点发送无凭证请求得到 `status_code: 1004` 和“请在 Authorization 中携带 API secret key”。因此客户端必须同时检查 HTTP 状态和 `base_resp.status_code`。

### JSON body

```json
{
  "model": "embo-01",
  "type": "db",
  "texts": ["一份低脂早餐"]
}
```

字段语义：

| 字段 | 必需 | 说明 |
| --- | --- | --- |
| `model` | 是 | 当前目标模型为 `embo-01`。 |
| `type` | 是 | `db` 用于库/文档向量；`query` 用于检索查询向量。两侧应保持这对类型约定。 |
| `texts` | 是 | 字符串数组；可在一次请求中提交多个文本。 |

这些字段和 `db/query` 约定由 [Spring AI `MiniMaxApi.EmbeddingRequest`](https://github.com/spring-projects/spring-ai/blob/v1.1.0-RC1/models/spring-ai-minimax/src/main/java/org/springframework/ai/minimax/api/MiniMaxApi.java#L957-L1030) 按 MiniMax Embeddings API 实现；该类的 Javadoc 链接到 MiniMax 的 [官方 Embeddings 指南](https://www.minimaxi.com/document/guides/Embeddings)。这是 SDK 交叉证据，不替代官方 schema。

### `GroupId`

旧版 MiniMax embedding 客户端把 group id 放在 URL 查询参数，而不是 body：

```text
POST https://api.minimaxi.com/v1/embeddings?GroupId=<GROUP_ID>
```

可审计实现：[LangChain Community `MiniMaxEmbeddings`](https://github.com/langchain-ai/langchain-community/blob/main/libs/community/langchain_community/embeddings/minimax.py) 和 [OpenViking MiniMax embedder](https://github.com/volcengine/OpenViking/blob/main/openviking/models/embedder/minimax_embedders.py)。两者都把 `GroupId` 作为可配置查询参数，并把 API key 放入 Bearer header。

当前官方 OpenAI 兼容文档明确的是 Bearer API key，未在可抓取的当前 Embeddings schema 中确认 `GroupId` 是否强制。因此本项目配置可以保留 `AGENT_EMBEDDING_GROUP_ID`，但适配器应：

1. 非空时发送 URL 参数 `GroupId`；
2. 不把 `group_id` 写入 JSON body；
3. 在没有 group id 的现代 key 上先做一次 smoke test；若服务返回缺少 group 的业务错误，再将其标记为必填。

## 响应契约

成功响应的 MiniMax 原生形状为：

```json
{
  "vectors": [[0.01, -0.02, 0.03]],
  "model": "embo-01",
  "total_tokens": 7,
  "base_resp": {
    "status_code": 0,
    "status_msg": ""
  }
}
```

字段语义：

- `vectors` 与输入 `texts` 一一对应；每个元素是浮点数组。
- `model` 是实际使用的模型标识。
- `total_tokens` 是该请求的 token 用量（若服务响应提供该字段）。
- `base_resp.status_code == 0` 才视为 MiniMax 业务成功；非零时读取 `status_msg` 并进入失败/降级路径。

响应字段由 [Spring AI `MiniMaxApi.EmbeddingList`](https://github.com/spring-projects/spring-ai/blob/v1.1.0-RC1/models/spring-ai-minimax/src/main/java/org/springframework/ai/minimax/api/MiniMaxApi.java#L1032-L1048) 交叉验证。该集成还明确使用 `POST /v1/embeddings`（同文件 [L200-L215](https://github.com/spring-projects/spring-ai/blob/v1.1.0-RC1/models/spring-ai-minimax/src/main/java/org/springframework/ai/minimax/api/MiniMaxApi.java#L200-L215)）。

## 模型维度、批量和错误边界

### 维度

Spring AI 的 MiniMax API 集成测试对 `embo-01` 成功响应断言每个向量长度为 **1536**：[MiniMaxApiIT.embeddings](https://github.com/spring-projects/spring-ai/blob/v1.1.0-RC1/models/spring-ai-minimax/src/test/java/org/springframework/ai/minimax/api/MiniMaxApiIT.java#L65-L74)。由于当前 MiniMax 文档索引没有公开维度表，本项目应在 ingestion 第一次成功后检查所有向量长度，并拒绝混入既有不同维度的 Qdrant collection。

### 批量限制

官方当前文档没有公开 `embo-01` 的最大 `texts` 条数、单条字符/token 上限或最大请求体大小。虽然协议支持批量数组，但不能据此推导具体上限。最小实现建议：

- 先用小批量（例如 16 或 32 条）回填；
- 对 413、请求体过大和业务长度错误减小批量并记录原因；
- 对 429、5xx 和可重试网络错误使用有限次数指数退避；
- 不在代码中写死“官方最大批量”数字，除非账号实测或官方页面恢复并明确给出。

### 错误

MiniMax API 至少存在两层错误：HTTP 层（4xx/5xx）和 JSON `base_resp` 业务层。公开端点的无凭证探测返回：

```json
{
  "base_resp": {
    "status_code": 1004,
    "status_msg": "login fail: Please carry the API secret key in the 'Authorization' field of the request header"
  }
}
```

该响应只用于确认错误包格式，不含任何秘密。适配器应对 JSON 缺失、`vectors` 数量与输入不一致、向量维度不一致、非零 `status_code` 全部失败处理。

## 限流

[官方 Rate Limits 页面](https://platform.minimaxi.com/docs/guides/rate-limits)说明平台以 RPM（每分钟请求数）和 TPM（每分钟 token 数）进行限制，且按模型、接口和账户类型分配；页面当前只展示语言、视频、语音、图片和音乐产品。没有可引用的 `embo-01` 专属 RPM/TPM 数值，因此：

- 不将语言模型（例如 MiniMax-M3）的限额套用于 Embeddings；
- 以 429/业务限流错误为触发信号，使用退避和批量降速；
- 将实际账号限额记录到评测报告，而不是写入未经验证的常量。

## 域名关系与本项目配置建议

| 域名 | 观察/证据 | 建议 |
| --- | --- | --- |
| `api.minimaxi.com` | 当前官方 OpenAI API 文档的 server URL；`/v1/embeddings` 可达。 | 新配置首选。 |
| `api.minimax.chat` | 旧版 MiniMax SDK（Spring AI、LangChain）默认的 Embeddings base URL；`/v1/embeddings` 可达。 | 作为兼容/回退地址，必须与实际 key smoke test。 |
| `api.minimax.io` | `/v1/embeddings` 也可达并返回相同风格的 MiniMax 业务错误包，但当前官方 API Reference 未将其列为 canonical server。 | 不作为默认值，除非账号/区域文档明确要求。 |

域名均不能仅凭 DNS 或一次错误响应断言“完全等价”；区域、套餐和密钥权限仍应以实际账号调用结果为准。用户提供的 `AGENT_EMBEDDING_API_BASE=https://api.minimax.chat` 可以作为兼容配置，但若从零接入，建议改成 `https://api.minimaxi.com` 并保留可配置覆盖。

## 对 health-agent 的最小接入决策

1. 不要把 MiniMax Embeddings 当作 OpenAI `input`/`data[].embedding` 协议；新建专用 adapter，发送 `texts + type`，解析 `vectors + base_resp`。
2. 文档 ingestion 使用 `type=db`，查询 embedding 使用 `type=query`；两种向量必须由同一 `embo-01` 模型生成。
3. provider/model/dimension/version 必须进入 Qdrant collection identity；`embo-01` 1536 维不能与现有 DashScope 维度混写。
4. `GroupId` 做成可选 query parameter；先用合法 key 做单文本 `db` 和单文本 `query` smoke test，再决定是否要求配置该字段。
5. 仓库已加入可切换的 `MiniMaxEmbeddingClient`（`diet.embedding.provider=minimax`），但在没有合法
   MiniMax embedding 凭证、成功响应和向量维度记录前，不生成 MiniMax 质量数字，也不替换当前
   Structured 默认路线。适配器默认读取 `MINIMAX_API_KEY`、`MINIMAX_EMBEDDING_BASE_URL`、
   `MINIMAX_EMBEDDING_MODEL` 和可选 `MINIMAX_GROUP_ID`，并对 HTTP/业务错误和维度不符返回空结果，
   由 Hybrid 自动降级。

> `TypedEmbeddingClient` 已将文档向量作为可选扩展接入：MiniMax 入库使用 `type=db`，在线查询使用
> `type=query`；DashScope 等未实现该扩展的客户端仍沿用原有 `embed()` 路径，保持兼容。
