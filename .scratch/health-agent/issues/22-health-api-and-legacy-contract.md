# 22 健康 Agent API 与旧饮食兼容契约

- Type: grilling
- Status: resolved
- Blocked by: 12, 15, 16

## Question

在不破坏现有饮食 API 可用性的前提下，冻结健康 Agent 的对外契约：统一聊天入口、饮食/健身/作息/综合周计划的请求与响应 DTO、错误结构、分页和资源标识应如何定义？

需要明确 `domain/task/riskFlags/phase` 在响应中的边界、`SourceMode` 只属于餐食域后的兼容映射、旧 session/slots 的读取策略、匿名身份归属、`requestId` 幂等字段、计划与资源 API 的命名，以及旧客户端继续使用 `/api/v1/diet/**` 时的行为和废弃周期。产出应能直接约束 Controller、DTO、OpenAPI 和兼容回归测试，而不是只列模块名称。

## Answer

2026-08-10 已按“满足项目需求的最小实现”确认：

- 新增统一健康聊天入口 `/api/v1/health/chat`。响应至少包含 `sessionId`、`traceId`、`responseType`、`domain`、`task`、`riskFlags`、`phase`、`speechText`、`displayBlocks` 和 `nextAction`；澄清时额外返回 `clarifyQuestion` 和 `missingSlots`。
- `domain/task/riskFlags/phase` 是编排状态和可解释结果；`displayBlocks` 只放后端已确认的资源、事实或计算结果，响应 Agent 不能自行扩展资源 ID 或安全结论。`sourceMode` 只保留在餐食兼容边界，不进入通用健康意图模型。
- 现有 `/api/v1/diet/chat` 保留为旧客户端适配入口，继续返回餐食兼容 DTO；旧请求中的 `sourceMode` 映射为 `domain=MEAL`。旧 session 和七个饮食 slot 通过兼容适配层读取，不把旧 `SlotBundle` 扩展成健康万能对象。
- 新健康资源接口按领域提供：`GET /api/v1/health/meals`、`GET /api/v1/health/exercises`、`GET /api/v1/health/plans`。资源列表使用 `page` 和 `size` 分页，`size` 首版上限为 50；不引入 cursor、复杂查询 DSL 或 GraphQL。旧 `/api/v1/diet/meals/**` 保持原行为。
- 新反馈接口为 `POST /api/v1/health/feedback`，请求使用 `resourceType`、`resourceId`、`action`、可选 `sessionId`、`planId` 和 `planItemId`，响应返回当前反馈状态。旧 `/api/v1/diet/feedback` 继续接受裸 `itemId`，适配为 `resourceType=MEAL`。
- 新健康接口不接受客户端 `X-User-Id` 作为数据归属依据，使用 HttpOnly Cookie 匿名身份。开发环境可以通过身份适配器提供本地调试身份；生产环境忽略或拒绝该 Header。新聊天请求必须携带 `requestId`，同一匿名身份、会话和请求 ID 重复提交时返回已保存结果。
- 新健康接口统一返回错误对象：`code`、`message`、`requestId` 和 `traceId`。首版区分参数错误、身份无效、资源不存在、风险拒绝、版本/幂等冲突和服务异常；旧接口继续保留原始响应格式。

### 统一资源响应约束

```json
{
  "resourceType": "EXERCISE",
  "resourceId": 123,
  "name": "俯卧撑",
  "source": {"type": "DATASET", "name": "Gym visual"},
  "eligibility": {"planReady": true}
}
```

`resourceType + resourceId` 是跨页面、反馈、计划引用和聊天卡片的唯一资源身份。餐食、动作、作息事实和计划项目不得使用无法区分类型的裸 `itemId`。

### 兼容与废弃约束

1. 旧 `/api/v1/diet/**` 在首版继续可用，不立即删除或改变字段语义；新功能只进入 `/api/v1/health/**`。
2. 旧接口的兼容适配不得把健身、作息或计划资源压扁成餐食 DTO；遇到无法表达的能力返回明确的兼容错误或引导使用新入口。
3. 旧接口的废弃周期暂不以日期强制关闭，待新前端完成迁移并通过兼容回归验收后再发布公告和移除计划。
4. Controller、OpenAPI 和兼容测试以本票为 API 入口约束；最终字段细节由 23、26、27 号票据补齐。
