# P1 健康 Agent Trace 最小脱敏增强

- Type: task
- Status: resolved
- Triage: ready-for-agent
- Priority: P1
- Estimate: 0.5 天
- Blocked by: 01
- GitHub: https://github.com/Leewwp/health_agent/issues/53

## Question

如何用最小改动让健康链路 Trace 足以展示 Qdrant/MCP 增强和失败降级，同时避免记录密钥、Bearer token 或不必要的敏感输入？

## Scope

- 只强化健康链路现有 `AgentContractModule` 和相关 Trace DTO/持久化边界；
- 记录检索模式、向量供应商/模型版本、降级原因和必要失败分类，不记录完整凭证或授权头；
- 对 API key、MCP token、Cookie、授权头和可能出现的敏感配置统一脱敏；
- 保持现有 Trace 表 `diet_request_trace`，不创建 `agent_traces` 表；
- 不迁移旧饮食链路的会话级 Agent 调用方式，不建设完整可观测性平台；
- 为敏感字段不落库和失败摘要补充针对性测试。

## Done when

演示 Trace 能说明使用了结构化/向量召回或发生了降级，且自动化测试证明常见凭证和授权信息不会进入响应或持久化 Trace。

## Answer

2026-08-12 完成（提交 fc194fb，含 fafe01f 回归修复）：

- `TraceRedactor` 在 `AgentTraceService` 持久化边界统一脱敏：API key、Bearer、授权头、Cookie、环境变量凭证和 JSON 敏感键，先 Bearer 后 sk- 消除重复占位符。
- `MEAL_RETRIEVED` 事件补记向量供应商/模型/版本/collection，演示 Trace 可说明结构化/向量召回或降级原因。
- 只强化健康链路现有 AgentContractModule 与 Trace 持久化边界，保持 `diet_request_trace` 表，不迁移旧饮食链路、不建设可观测性平台。
- 回归测试证明凭证不进入 traceJson/responseJson/errorMessage，非敏感内容原样保留。

