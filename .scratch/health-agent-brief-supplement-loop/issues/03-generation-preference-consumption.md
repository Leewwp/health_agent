# 生成侧偏好消费、可见回退与 generationNotes 契约

Type: task
Status: ready-for-agent
Blocked by: 02
Priority: P1

## Goal

让餐食计划与综合计划的生成真正消费简报可选偏好：扩展计划餐食候选的受控标签接口（审核库读既有数据列，fixture 补确定性标签表），按明确的多值语义与整天回退粒度过滤，未满足偏好三处可见且字段形状固定。

## Acceptance

- 计划餐食候选记录扩展受控标签字段 `cuisineTags`、`tasteTags`、`nutritionPreferenceTags`、`convenienceTags`（均为列表）；审核库 Provider 从 `meal_item.cuisine/taste/health_goal/convenience` 填充，`nutritionPreferenceTags` 排除减脂/增肌/维持健康/均衡；**fixture 种子按规格的确定性标签表填充**（cuisine/taste/convenience 三列为新增 M1-M9 基准，nutritionPreferenceTags 新增 M1[]、M2[低油]、M3[]、M4[高蛋白]、M5[高蛋白]、M6[]、M7[低油,高蛋白]、M8[低油,高蛋白]、M9[低油]）；挑选器只消费候选类型。无偏好时挑选行为与现状完全一致，既有 fixture 组合测试不回退。
- 过滤语义：同一字段多值 OR、不同字段 AND；未支持偏好（如 `cuisine:中餐`）永不进入过滤条件，也不进入未满足记录。
- `tastePreferences` 的逐值映射是实现合同：口味规范值匹配 `tasteTags`，营养规范值匹配 `nutritionPreferenceTags`；不同字段之间 AND，同一字段内 OR。边界例 `{清淡, 高蛋白}` 必须同一候选同时命中两个字段，否则按 `字段:值` 记录未满足并触发整天回退。
- fixture 的 cuisine/taste/convenience 标签表是新增的 M1-M9 确定性基准，不代表当前种子已有标签。
- **整天回退边界**：先按所选餐次分桶，再应用偏好过滤；某日任一餐次的偏好池为空，或偏好约束无法形成完整的所选餐次组合时，该日整天绕过偏好过滤回退，但仍保留餐次、唯一性、热量和跨日多样性约束，不做单餐半回退的混合日。纯热量区间无解沿用既有热量回退且不记偏好未满足；其余日期继续偏好过滤。
- **generationNotes 字段形状（固定合同）**：生成 metadata 写入 `generationNotes = { unsupportedPreferences: ["cuisine:中餐"], fallbacks: [{date: "YYYY-MM-DD", mealTimes: ["早餐"], unmetPreferences: ["cuisine:川菜", …]}] }`（所有数组非 null，日期使用计划时区 ISO 格式）；`PlanView` 新增结构化 `GenerationNotes` 字段从 metadata 透传，版本快照保存于 `resource_snapshot_json.generation.generationNotes`。新增 `GET /api/v1/health/plans/{planId}/versions/{versionNo}` 返回对应版本同形状 PlanView；计划详情和版本详情在旧 metadata 缺失时返回空对象。计划页在头部说明区分区渲染，未支持项注明“暂不按它筛选”，回退项带日期与未满足偏好键。
- 无偏好时生成行为与现状一致；只生成所选餐次、按所选餐次归一化热量、跨日软多样性、写入幂等等既有合同不变；综合计划餐食侧行为与独立餐食计划一致，且偏好端到端进入综合生成。
- 测试覆盖：两种 Provider 模式的标签填充（fixture 按确定性标签表断言，含营养标签）、同字段 OR/跨字段 AND、过滤命中候选 ID、整天回退边界例（偏好 {家常+快速} 下某日早餐池为空 -> 该日整天回退但仍保留所选餐次、其余日照常、说明带日期）、偏好交集失败回退、纯热量回退不记偏好未满足、回退日多样性不放宽、generationNotes 在 metadata/版本快照/计划详情/版本详情/计划页均可见、无偏好回归不变、综合计划偏好端到端。

## Comments
