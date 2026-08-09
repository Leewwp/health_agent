# 09 meal_item 表结构改造与数据导入设计

- Type: grilling
- Status: open
- Blocked by: —

## Question

`meal_item` 表如何改造并导入新数据（由 02 号调研结果毕业的 fog）？已确认事实（见 `.scratch/health-agent/research/meal-dataset.md`）：**组合方案** = Food.com Recipes & Reviews（CC0，52 万食谱带热量/蛋白/脂肪/碳水 + 312 分类，裁剪导入 1000~3000 条）+ USDA FoodData Central（公有领域，营养校准）+ 自建中文菜名映射表 + 图片列为迭代项（外链 URL 易失效需兜底）。

待决策：

1. **表结构**：营养字段除 kcal/蛋白/脂肪/碳水外，必须保存 `serving_count / serving_size / serving_unit / nutrition_basis / estimated`，否则周计划无法累计；配料/过敏原、来源记录和媒体许可也必须有明确位置。比较直接列与 `meal_nutrition` 的必要性。
2. **导入策略**：先做 300~500 条经过清洗和抽检的 MVP 子集，而不是把 1000~3000 条和 300~1000 条中文人工映射同时塞进两周。CLI/离线 ETL 负责去重、单位校验、份量归一、标签映射和导入报告；运行时应用不下载原始数据。
3. **营养可信度**：Food.com 作者估算值标记为“估算”；USDA 原料级数据不能在没有配料质量与单位换算的情况下声称完成菜谱校准。USDA 校准进入后续迭代，MVP 不做伪精确。
4. **中文映射**：优先覆盖实际导入子集，保存原名、中文展示名和别名；LLM 辅助翻译必须经过抽检，不直接生成营养值和过敏原。
5. **图片兜底**：无图时浏览页/详情层的降级展示；不缓存或再分发没有明确媒体授权的 Food.com 图片。

约束：改动与 04（意图/模式）、05（健身 slot）的设计保持一致的 slot 消费方式。

技能：/grilling、/domain-modeling。产出：表结构 DDL 草案 + 导入策略 + 中文映射表设计。
