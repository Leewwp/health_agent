# 餐食简报可选偏好字段、未支持偏好与词汇

Type: task
Status: resolved
Blocked by: 01
Priority: P0

## Goal

让餐食简报承载菜系、口味/营养偏好、便利性，并用确定性的受限菜系解析器表示库中无标签的偏好（如“中餐”）；保留原值、登记未支持集合、展示但不参与过滤。

## Acceptance

- `MealPlanBrief` 稳定 JSON 形状为 `{weekStart, mealTimes, healthGoal, cuisine: string|null, tastePreferences: string[], convenience: string|null, unsupportedPreferences: string[]}`；列表永远为数组，未填单值为 null，未支持键使用 `field:value` 并去重。该对象是计划简报唯一来源，旧 `slots` 只在旧计划字段为空时一次性导入。
- 确定性菜系解析器不依赖模型、不参与路由。输入仅接受显式标签（“菜系：X/菜系是 X/X 菜系”）或封闭超类词表（“中餐/中式”等，可在自然句中出现）；先剥除否定范围，再按中文标点和“和/或/以及”拆分，过归一器别名表。范围外不解析，返回 INVALID 枚举指引。
- 解析器必须在 `isUnrelated`/`looksLikeMealInput` 之前运行；可选偏好命中本身足以进入餐食简报处理器。模型 rawSlots 中未收录的未知值必须丢弃，只有该解析器能产生未支持原值。
- 字段归属：减脂、增肌、维持健康、均衡写 `healthGoal`；清淡、低油、低盐、高蛋白等写 `tastePreferences`；菜系、便利性直落。热量目标永不被口味值覆盖。
- 菜系、便利性单选：已有值且无“换成/改为”时保留并提示只能选一个；空字段恰有一个受支持值时采用它、其余未支持值登记；多个受支持值不猜测，要求重选。口味/营养偏好多值追加去重，“换成/改为”清除重建。
- 未支持偏好不参与检索/过滤，但必须出现在简报摘要、回复、generation metadata 和计划说明中；回复附当前可用菜系列表。便利性别名补充“烹饪时间短、做饭时间有限、快手菜、没时间做饭 -> 快速”，不把“中餐”加入别名表。
- INVALID 时简报不变，使用可补充项枚举指引；最低条件仍可生成，可选偏好永不阻断。
- 测试覆盖受支持/未支持/范围外/否定四态、原始失败句在 unrelated 门槛前登记“中餐”和“快速”、模型未知 rawSlots 不越权、单选冲突、摘要/枚举、JSON 往返及重启保留。

## Comments

- 2026-08-29 resolved：`MealPlanBrief` 固定为 `{weekStart, mealTimes, healthGoal, cuisine, tastePreferences, convenience, unsupportedPreferences}`（列表恒为数组、未填单值 null、未支持键 `field:value` 去重）；旧 3 参构造与会话 JSON 兼容读取，往返不丢数据（`HealthSessionServiceTest`）。
- 确定性受限菜系解析器 `MealCuisineIntentParser`（不依赖模型、不参与路由）：显式标签“菜系：X/菜系是 X/X 菜系”+ 封闭超类词表（中餐/中式）+ 受支持别名命中即形态内；先剥否定范围，按中文标点与“和/或/以及”拆分；“中餐、川菜”采用川菜并登记 cuisine:中餐。归一器补充便利性别名“烹饪时间短/做饭时间有限/快手菜/没时间做饭→快速”，“中餐”未加入别名表；`healthGoal` 白名单（减脂/增肌/维持健康/均衡）永不被口味值覆盖；单选冲突对齐难度单选（已有值无“换成”保留并提示）。
- 测试：`MealCuisineIntentParserTest` 7 例（受支持/未支持/范围外/否定四态）、`MealPlanBriefServiceTest` 16 例（原始失败句在 unrelated 之前登记、单选冲突、摘要/枚举、未支持保留）。
