# 餐食/菜品开源数据集调研（meal-dataset）

> 调研日期：2026-08-09 ｜ 对应决策：Q9 / ticket 02「餐食数据集调研」
> 参照物：`hasaneyldrm/exercises-dataset`（1,324 动作，MIT + 媒体©Gym visual 署名例外，10 语言含中文）
> 方法：全部结论基于一手来源核查（Kaggle 页面 JSON-LD、GitHub API/README、arXiv、Zenodo API、USDA/OFF/FooDB 官网、ETH 页面），非二手转述。

## 本轮增补结论：先按数据层拆解

本项目想找的其实不是单一数据集，而是四层数据的组合：

1. **菜谱事实**：菜名、食材、用量、步骤、份量、烹饪时间。
2. **营养事实**：热量、蛋白质、脂肪、碳水及其计量口径。
3. **推荐标签**：餐次、菜系、口味、健康目标、便利性等中文 slot。
4. **媒体资产**：图片或 GIF，以及可再分发的媒体许可。

`exercises-dataset` 之所以好用，是因为一条动作记录已经接近“浏览页/浮窗的完整展示单元”，且 GitHub 仓库、字段、媒体和许可证都在同一入口。餐饮数据通常把四层拆在不同来源中，因此不存在同时满足“中文菜品 + 克重 + 营养 + 步骤 + 图片 + 可商用 + 直接下载”的单一数据集。

按“获取难度、菜谱完整度、营养可信度、中文/标签、许可风险”综合判断：

| 来源 | 获取难度 | 菜谱完整度 | 营养价值 | 中文/标签 | 结论 |
|---|---:|---:|---:|---:|---|
| **Food.com Recipes & Reviews** | 中 | 高 | 中 | 低 | 最适合作为主菜谱库；Kaggle 页面标 CC0，但数据包较大，图片 URL 需要单独处理 |
| **USDA FoodData Central** | 低 | 低（原料/食品为主） | 高 | 低 | 最适合作为营养底座和校准源；官方 CSV/JSON 下载 + API |
| **Open Food Facts** | 低 | 低（包装商品为主） | 中 | 中 | 可补商品/包装食品；不适合作为家常菜谱主库，ODbL/图片 CC BY-SA 增加发布约束 |
| **RecipeDB** | 中 | 高 | 高 | 低 | 字段很全，但官方页面标 CC BY-NC-SA 3.0，限研究/非商业场景 |
| **RecipeNLG** | 中 | 高 | 无 | 低 | 规模约 223 万，但下载条款明确仅限非商业研究和教育，排除生产主库 |
| **B 站单一 UP 主视频** | 高 | 需自行抽取 | 低到中 | 高 | 只能做获授权后的人工精选补充，不能替代数据集 |

### 推荐落地路径

**第一阶段只做 `Food.com 300~500 条 + USDA 原料营养参考 + 自建中文映射表`。** 不建议直接导入 52 万条，也不建议先做视频批处理。

- 从 Food.com 选择有菜名、食材、步骤、份量、营养字段完整的记录，优先早餐/午餐/晚餐、鸡蛋/禽肉/鱼虾/豆制品/米面/蔬菜等常见食材。
- 保留 Food.com 的原始营养值，但在表中标记 `nutrition_basis=recipe_estimate`、`estimated=true`。这类值可用于推荐排序和展示估算，不能直接作为精确的周计划摄入结算。
- 用 USDA FoodData Central 为常见原料建立标准名、每 100g 营养值和单位换算表。只有在食材、数量和单位都能可靠解析时，才重新计算菜谱营养；无法解析的记录保留原估算并降低可信度。
- 中文名、别名、`mealTime/cuisine/taste/healthGoal/convenience` 标签只覆盖实际导入的 300~500 条，保存英文原名、中文展示名、映射来源和人工审核状态。不要把 LLM 翻译结果直接当作营养或过敏原事实。
- 图片列先允许为空。Food.com 页面展示的是外链 Image URLs；CC0 数据标识不等于你获得图片文件的再分发权，不应把图片批量下载后打包到仓库或镜像里。

这条路径与当前 `meal_item` 的差距是结构化字段不足，而不是缺数据量：需要新增原始菜谱、配料、份量、营养口径、来源和审核状态；现有七个中文 slot 继续作为推荐标签层，不能拿它们代替食材和营养事实。

## 官方来源补充核验

### USDA FoodData Central

官方“Download Datasets”页面明确提供 CSV 和 JSON 下载，并列出 Foundation Foods、SR Legacy、FNDDS、Branded Foods 及全量 CSV。2026-04 全量 CSV 的页面标注压缩包约 460MB、解压约 3.1GB；MVP 不需要下载全量，优先下载较小的数据类型或使用 API。

官方 FAQ 明确写出 FoodData Central API 可供开发者把营养数据集成到应用或网站，并把“开发商业营养分析软件和面向私营部门的应用”列为用途。实际使用时仍应保留来源、数据版本和更新时间；Branded Foods 来自品牌/私有标签数据，不要把它和 USDA 自有分析数据混为同一可信度。

来源：

- https://fdc.nal.usda.gov/download-datasets
- https://fdc.nal.usda.gov/faq
- https://fdc.nal.usda.gov/api-guide

### Food.com Recipes & Reviews

Kaggle 数据页当前显示：522,517 条食谱、312 个分类、1,401,982 条评论；食谱字段包括烹饪时间、份量、食材、营养、步骤和图片 URL，并提供 `recipes.parquet`、`recipes.csv` 等文件。页面将数据集标注为 `CC0: Public Domain`，但获取入口是 Kaggle，不是像健身数据集那样直接 `git clone`；页面还显示 `recipes.csv` 约 704MB、数据探索器总计约 1.55GB。

因此它是“字段最全的主库”，但不是“获取最轻的主库”：第一次下载应只取 parquet/csv 中的目标列和 300~500 条子集，不把评论表导入业务库。图片只保存来源 URL 和许可审查结果，不默认落地分发。

来源：https://www.kaggle.com/datasets/irkaal/foodcom-recipes-and-reviews

### RecipeDB 与 RecipeNLG 的排除理由

- RecipeDB 官方站点称其整合超过 118,000 条食谱、23,500 种食材、烹饪过程、FlavorDB、USDA 营养和健康关联，数据完整度很高；但页脚标明 `CC BY-NC-SA 3.0`，不适合本项目公开部署或商业化主库。来源：https://cosylab.iiitd.edu.in/recipedb/
- RecipeNLG 官方下载页称其数据集包含超过 200 万条食谱，字段适合生成式任务；但下载前必须同意“仅限非商业研究和教育用途”的条款，并通过验证后下载。它可以用于离线研究，不应混入可公开部署的数据。来源：https://recipenlg.cs.put.poznan.pl/dataset

## B 站“阿古的轻食日记”兜底评估

本轮查看了该 UP 主公开搜索页和一条样例视频：搜索页显示约 413 个视频、43.6 万粉丝；样例视频时长 46 秒，标题中包含“椒麻虾片”和“657 千卡”，页面正文包含菜名、简介和千卡，但没有结构化的食材克重/调料表。页面还明确显示“未经作者授权，禁止转载”。

所以它的价值是**中文菜名和真实中式轻食样例**，不是现成数据集。若没有创作者明确授权，不建议批量下载、OCR、转录并把结果作为公开数据库发布；即使只发布文字总结，也应把它视作视频衍生内容，不能默认规避原内容权利。

如果后续获得授权，最小可行的抽取流水线应是：

1. 先只选 30~50 条代表性视频，保存 BV 号、标题、发布日期和原视频链接，不先处理全部 413 条。
2. 抽取标题/简介作为候选元数据；对视频音频做 ASR，对画面中的食材卡片、克重和热量做 OCR/视觉识别。短视频不一定把关键信息放在语音或网页正文中，单纯 ASR 不够。
3. 输出结构化 JSON：菜名、餐次、食材、数量、单位、调料、步骤、热量、证据时间戳、抽取置信度、人工审核状态。
4. 用 USDA 原料数据重算可重算的热量和宏量营养；视频标注值只作为作者估算，保留 `estimated=true`，不要直接当作健康处方依据。
5. 每条记录人工复核后再进入 `meal_item`，并保存来源 URL 和授权凭证。没有授权时，最多将其作为人工灵感来源，手工重新设计并独立记录菜谱，不复制视频文字和媒体资产。

## 最终建议

对于当前项目，优先级应是：

1. **马上可做**：Food.com 子集导入，先获得完整的英文菜谱事实和基础营养字段。
2. **必须补齐**：USDA 原料营养表 + 300~500 条中文名/标签映射，形成可检索的中文推荐候选。
3. **暂缓**：图片资产和大规模中文菜谱；它们是许可与人工审核问题，不是下载问题。
4. **仅作兜底**：获得授权后，从阿古的轻食日记精选 30~50 条做中文中式轻食扩展；没有授权就不做批量抽取。

一句话结论：如果“像健身数据集一样一键拿到、数据全、可公开部署”是硬要求，目前没有合格的单一餐饮数据集；最稳的工程方案是 `Food.com 菜谱事实 + USDA 营养事实 + 自建中文标签`，而不是把 B 站视频当主数据源。

## 筛选标准（按优先级）

1. 营养数据（热量/蛋白质/脂肪/碳水）→ 支撑周计划"推荐摄入量"计算
2. 分类标签（菜系/品类/菜式）→ 映射现有 cuisine/taste/mealTime 等中文 slot
3. 中文支持（中文菜名或说明，最低英文+可翻译）
4. 图片（浏览页与浮窗展示）

---

## ① 候选数据集对比表

| 数据集 | 规模 | 关键字段 | 许可协议 | 中文 | 图片 | 数据质量 | 获取方式 |
|---|---|---|---|---|---|---|---|
| **USDA FoodData Central** | 5 类数据：SR Legacy 约 7.8k 条、FNDDS 2021-2023 约 7k 条调查食物、Foundation Foods 数百条、Branded 约 40 万商品 | 每 100g/每份的千卡、蛋白质、脂肪、碳水等 200+ 营养素；FNDDS 含 WWEIA 食物类别与份量 | 官方 FAQ 明确列出可用于商业营养软件；不同数据类型和第三方/品牌字段仍需按来源核查 | 无（仅英文，含少量"美式中餐"条目如 Fried rice/Chow mein） | 无 | 实验室分析/权威汇编，最高 | 全量 CSV/JSON 下载（全量 zip 460MB，2026-04 版），免费 API（需 key） |
| **Food.com Recipes & Reviews**（Kaggle `irkaal/foodcom-recipes-and-reviews` v2） | **522,517 条食谱** / 312 个分类 / 140 万条评论 | 每食谱 **Calories/Fat/Carb/Protein** 等营养（另含糖/纤维/钠/胆固醇）、食材清单、步骤、烹饪时间、份量、**Images 图片 URL 列表** | **CC0（公有领域）**（Kaggle 页面 JSON-LD 标注）；注意 Images 为 food.com CDN 外链，不随 CC0 授权 | 无（英文；312 个分类如 breakfast/dinner/dessert，利于映射 mealTime/场景） | 有（URL 热链，不可再分发） | 众包 UGC：营养为食谱作者估算、单位不统一，需清洗 | Kaggle 下载（758MB zip，含 recipes.parquet/csv） |
| **RecipeNLG**（PUT Poznan） | 223 万条食谱（官方去重后约 160 万） | title/ingredients/directions/NER 标注，**无营养字段** | **仅限非商业研究和教育**（官方下载条款） | 无 | 无 | 中（去重清洗较好） | 官网下载（需同意条款并通过验证） |
| **Nutrition5k**（Google，CVPR 2021） | 5,006 盘餐食 | 每盘总热量/脂肪/碳水/蛋白质 + 每食材质量与营养（源自 USDA）、RGB-D 俯拍图 + 侧面视频帧 | **CC BY 4.0**（可商用，须署名） | 无 | 有（扫描台拍摄，181GB） | 称重+USDA 营养库，非常高；但为 Google 食堂西餐，**无菜名/菜系** | GCS 桶下载（181.4GB） |
| **Open Food Facts** | 全球数百万商品（含中国产地商品） | 每 100g 营养、类别、配料、多语言名称、商品照片 | **ODbL（库）+ DbCL（内容）+ 图片 CC BY-SA 3.0** | 部分（中国商品含中文名，以包装食品为主，非菜品） | 有（多为包装照） | 众包，良莠不齐 | 全量 JSONL/Parquet/CSV 每日导出 + API |
| **Food-101**（ETH） | 101 类 / 10.1 万张图 | 仅类别+图像，**无营养** | 图源自 Foodspotting："超出科学合理使用须与图片所有者协商" → **实际仅限研究** | 无 | 有 | 高（图像基准） | 官网 tar.gz（5GB） |
| **ChineseFoodNet**（arXiv 1705.02743） | 208 类 / 18 万+ 张中式菜品图 | 类别（中文菜名）+ 图像，**无营养** | 论文声明 "public available **for research**" → 仅研究 | 是 | 有 | 高 | 官网/作者申请 |
| **VIREO Food-172**（CityU） | 172 类中式菜品 / 约 11 万图 | 类别+图像，**无营养** | 官网（vi3o.cs.cityu.edu.hk）当前不可访问；按学术惯例需申请、仅研究用途（**实施阶段需再确认**） | 是 | 有 | 高 | 作者申请 |
| **FooDB** | 约 800 食物 / 化合物库 | 营养素+化合物谱 | **CC BY-NC 4.0 → 不可商用，排除** | 无 | 无 | 高 | 官网下载 |
| **下厨房等爬虫数据集**（GitHub 如 `ruter/xiachufang-api`、`Sorosliu1029/recipe-crawler`） | 无现成数据文件，多为爬虫代码 | 中文菜名/菜谱 | **无数据许可声明**，且违反目标站 ToS → 不推荐商用 | 是 | 站内图（无授权） | 低 | 需自行爬取 |

---

## ② 推荐结论

**结论先行：不存在同时满足"营养 + 分类 + 中文 + 图片"且可商用的单一开源数据集。** 核查后最接近"完整"的两个商用友好候选是 **Food.com（页面标注 CC0）** 与 **USDA FoodData Central（官方 FAQ 明确支持商业营养软件用途，但需按数据类型核查）**，均缺中文、图片不可随数据再分发；中文菜品数据集（ChineseFoodNet、VIREO Food-172）全部仅限研究用途。

- **首选（主库）**：**Food.com Recipes & Reviews（Kaggle, CC0）** —— 52 万食谱自带宏量营养与 312 个分类，是唯一同时满足"营养 + 分类 + 商用许可"的大规模来源；分类（breakfast/lunch/dinner/dessert）可直接支撑 mealTime 与场景映射。**裁剪导入 500~3000 条即可**覆盖现有 meal_item 的品类面。
- **备选/营养校准**：**USDA FoodData Central** —— 官方营养事实底座，用于：(a) 对 Food.com 的估算营养做区间校准；(b) 补 Food.com 缺的原料类条目；(c) 面试可讲的"营养数据来源权威化"卖点。缺点：无菜品级中文、西式为主，Branded 等数据类型需保留各自来源信息。
- **组合方案（见 ④）**：Food.com（营养+分类+英文名）＋ 自建中文菜名映射表（中文支持）＋ 图片暂缓。

---

## ③ 许可协议核查结论

| 候选 | 可商用 | 需署名 | 媒体例外条款 | 与健身数据集（MIT + Gym visual 署名）对照 |
|---|---|---|---|---|
| USDA FDC | ✅（FAQ 原文允许用于商业营养软件；具体数据类型仍应逐项核查） | 建议注明 USDA/FDC 来源和版本 | 无媒体（无图） | 适合作为营养事实底座，但不能把所有 Branded 字段笼统当作同一许可证 |
| Food.com（Kaggle irkaal v2） | ✅ CC0 | 否 | **Images 字段是 food.com CDN 外链，不随 CC0 覆盖** —— 与"Gym visual 署名例外"同构：数据可用、媒体需另行处理 | 数据比健身数据集宽松；图片比健身数据集更严格（连署名模式都没有，只能热链/不可再分发） |
| Nutrition5k | ✅ CC BY 4.0 | 是（保留版权声明+注明改动） | 无 | 类似 MIT 数据 + 署名要求 |
| Open Food Facts | ✅ 但受限：**ODbL 属"数据库级别 copyleft"**，抽取达到"实质性"时整个派生数据库须以 ODbL 再发布；图片需 CC BY-SA 3.0 署名 | 是 | 图片 CC BY-SA（共享衍生） | 比健身数据集严格得多，与把数据落进自有 MySQL 的表结构冲突 |
| Food-101 / ChineseFoodNet / VIREO Food-172 | ❌ 仅研究用途 | — | Food-101：图片版权归原拍摄者 | 不可商用 |
| FooDB | ❌ CC BY-NC | 是 | 无 | 不可商用 |
| RecipeNLG | ❌ 仅限非商业研究和教育 | — | 聚合 UGC 来源的上游权利仍需注意 | 不推荐 |

**总体结论**：本项目（简历向 demo，公开部署在个人服务器）优先选择 Food.com 页面标注的 CC0 数据和 USDA FDC 中可用于商业营养软件的数据类型；对 Branded 等品牌字段仍逐项保留来源与版本。需规避一切"研究用途"中文图像数据集与 NC 类许可。媒体（图片）层面没有找到与"Gym visual 署名模式"等价的合规方案——**图片是本次调研最大缺口**。

---

## ④ 组合方案与可行性评估

### 方案 A（推荐）："Food.com 营养表 + 自建中文菜名表 + 图片暂缺"

1. **营养+分类**：导入 Food.com 子集（如按分类过滤 1000~3000 条），写入 meal_item 新增营养列（calories/protein/fat/carbs，每份口径）与 `category` 字段。
2. **中文支持**：因无合规中文菜品数据集，建立一张"中文菜名映射表"（菜名中文 + 别名 + 英文原味 + cuisine/taste/mealTime 标签），映射到现有 `diet_slot_option` 字典；规模 300~1000 条即可与现有 seed 数据同质，可用 LLM 辅助生成 + 人工抽检。
3. **图片**：数据层预留 `image_url` 列暂空；Food.com 的 Images 字段可作为热链兜底（不落地、不缓存、失效即退化为无图）。
- 可行性：**高**。工作量约 3~5 天（清洗脚本 1~2 天 + 中文映射 1~2 天 + 导入/接口 1 天），全部落在纯数据层，不触碰 agent 状态机。

### 方案 B（更轻）："USDA FDC 精选 + 自建中文名"
- 只从 SR Legacy/FNDDS 精选 500~1500 条"可成菜"条目（米面、肉禽、蛋、蔬菜、常见家常菜），营养权威、许可零风险；缺点：中式菜品覆盖极低、"菜品感"弱，浏览页观感不如 A。

### 方案 C（带图，不推荐）："Food.com 数据 + OpenFoodFacts 商品图"
- 图片虽然可商用，但需 CC BY-SA 署名且多为包装照，与"菜品浏览页"诉求不符；且 ODbL 对整库抽取有共享要求。仅在"必须带图"且接受约束时可作临时选项。

### 可行性总评
- 四项标准全部满足的候选：**无**（中文×商用 交集被逐一核验排除）。
- 按优先级取舍后的现实路径 = **方案 A**：保住第 1（营养）、第 2（分类）两项硬指标，第 3（中文）用自建映射表弥补，第 4（图片）列为后续迭代项。

---

## 风险点

1. **Food.com 版权瑕疵**：CC0 由 Kaggle 上传者单方声明，原始内容（用户生成的菜谱/评论）权利仍属 food.com 及其用户；作为 demo 风险可接受，但不得对外声称拥有数据版权，也不宜写入公开简历声明"自有数据"。
2. **Images 外链失效**：food.com 图片 URL 可能随时 404，且禁止批量下载再分发。
3. **中文菜品图片整体无合规来源**：ChineseFoodNet / VIREO Food-172 / 下厨房爬虫均不可商用，短期无解。
4. **RecipeNLG 虽可下载但仅限非商业研究和教育**：官方页面已给出条款，不能因为数据免费就把它作为公开部署主数据源。
5. **营养口径不一致**：Food.com 营养为作者估算、份量口径不统一，周计划"推荐摄入量"计算建议以 USDA FDC 或固定份量表做归一化（实施阶段在导入脚本中解决）。
6. **FNDDS 中式条目为"美式中餐"**：正宗中菜的卡路里值只能作为估算，前端需标注"估算值"。

---

## 附：关键一手来源

- USDA FDC 下载页：https://fdc.nal.usda.gov/download-datasets （2026-04 版）
- USDA FDC FAQ（商用用途原文）：https://fdc.nal.usda.gov/faq
- Food.com Kaggle 页（CC0 标注于 JSON-LD）：https://www.kaggle.com/datasets/irkaal/foodcom-recipes-and-reviews
- Nutrition5k README（CC BY 4.0）：https://github.com/google-research-datasets/Nutrition5k
- Open Food Facts 数据许可：https://world.openfoodfacts.org/data
- 阿古的轻食日记公开检索页：https://search.bilibili.com/all?keyword=%E9%98%BF%E5%8F%A4%E7%9A%84%E8%BD%BB%E9%A3%9F%E6%97%A5%E8%AE%B0
- 样例视频（椒麻虾片 657 千卡）：https://www.bilibili.com/video/BV1GxuG6jEnK/
- Food-101 ETH 页 + Kaggle 说明（Foodspotting 版权条款）：https://data.vision.ee.ethz.ch/cvl/datasets_extra/food-101/ ｜ https://www.kaggle.com/datasets/dansbecker/food-101
- ChineseFoodNet 论文（research-only）：https://arxiv.org/abs/1705.02743
- FooDB（CC BY-NC 4.0）：https://foodb.ca/
- 参照物 exercises-dataset（MIT + 媒体例外）：https://github.com/hasaneyldrm/exercises-dataset
