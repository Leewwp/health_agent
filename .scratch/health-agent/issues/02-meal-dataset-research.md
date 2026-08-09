# 02 餐食数据集调研

- Type: research
- Status: resolved
- Blocked by: —

## Question

寻找一个与 exercises-dataset 质量相当的开源**餐食/菜品数据集**，用于扩充 `meal_item`（当前仅几十条种子数据、无营养数据、无图片）。筛选标准按优先级（Q9 决策）：

1. **营养数据**（热量/蛋白质/脂肪/碳水——支撑周计划"推荐摄入量"计算，也是后端面试可讲的点）；
2. **分类标签**（菜系/品类/菜式等，便于映射现有 cuisine/taste 等 slot）；
3. **中文支持**（中文菜名或说明，最低要求英文+可翻译）；
4. **图片**（便于浏览页与浮窗展示）。

调研范围：Kaggle、GitHub、高校/机构公开数据集、USDA FoodData Central 及其中文菜品衍生集、食品识别数据集等。

产出（写入 `.scratch/health-agent/research/meal-dataset.md`）：①2-3 个候选数据集对比表（规模、字段、许可协议、中文支持、图片、数据质量）；②推荐结论（首选 + 备选 + 组合方案）；③**许可协议核查**（能否商用、是否需署名、有无媒体例外条款——参照健身数据集的 Gym visual 模式）；④若没有完全满足的候选，给出退而求其次的组合方案（如"营养表 + 中文菜名表 + 图片"三方拼装）。

## Answer

由 research 子代理于 2026-08-09 完成，产出 `.scratch/health-agent/research/meal-dataset.md`。

核心结论：
- 无单一数据集同时满足"营养+分类+中文+图片"四标准（中文菜品数据集全部仅限研究用途，不可商用）。
- **推荐组合**：Food.com Recipes & Reviews（Kaggle，CC0，52 万食谱自带热量/蛋白/脂肪/碳水 + 312 分类，裁剪导入 1000~3000 条）为主库 + USDA FoodData Central（公有领域）做营养校准 + **自建中文菜名映射表**（映射现有 cuisine/taste/mealTime slot）。
- 许可：Food.com 为 CC0（可商用免署名，Images 外链不可再分发，与健身数据集 Gym visual 例外同构）；USDA 公有领域。
- 图片列为后续迭代项（无合规中文菜品图源）；图片 URL 易失效需设计兜底。
