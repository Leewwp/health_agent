# Kaggle Food.com 数据集真实性核验

## 核验范围

- **目标数据集**：`irkaal/foodcom-recipes-and-reviews`
- **核验日期**：2026-08-10（Asia/Shanghai）
- **核验方式**：只读取 Kaggle 官方数据集页面、官方 API 元数据和官方文件列表接口；未点击下载按钮，未调用下载接口获取新数据。
- **本地已有副本**：`data/meal/raw/foodcom-recipes-and-reviews-v2.zip`。仅使用 ZIP 目录信息和本地文件属性作一致性比对，没有覆盖、删除或重新下载该文件。

## 结论

1. 数据集身份可以由 Kaggle 官方页面和 API 交叉确认：标题为 **Food.com - Recipes and Reviews**，slug 为 `irkaal/foodcom-recipes-and-reviews`，数据集 ID 为 `1063627`，发布者为 Kaggle 用户 `irkaal`（显示名 Alvin）。
2. 官方当前版本为 **v2**。版本记录为 `versionNumber=2`、状态 `Ready`、版本说明 `Updates`，创建/更新时间为 `2020-12-28T15:56:04.647Z`。
3. 官方描述给出 **522,517 条 recipes**、**1,401,982 条 reviews**、**312 个 recipe categories**，reviews 来自 **271,907 个用户**。两类记录合计为 **1,924,499 条**（本报告计算值，不是页面单独声明的总行数）。
4. 官方文件列表包含 4 个文件。逐项大小之和为 **1,552,797,790 字节**；官方搜索/list 元数据给出的下载包大小为 **758,217,627 字节**。这两个数字分别对应文件总大小口径和下载包大小口径，不能混用。
5. Kaggle 页面和 `view` API 均标注许可为 **CC0: Public Domain**，并链接到 Creative Commons 的 CC0 1.0 文本。但这只能证明 Kaggle 数据集页面的许可元数据声明；Kaggle 返回内容没有提供 Food.com 原始内容的权利链、单独的图片授权或原始发布仓库，因此公开再分发前仍需单独核查原始内容和图片 URL 的权利边界。
6. **来源说明的可证范围有限**：Kaggle 官方描述把数据称为来自 Food.com，并说明了记录内容；它没有在本次 API 元数据中提供独立的原始 Food.com 数据发布 URL、采集时间范围或转换脚本。因而可以确认“该 Kaggle 数据集页面声称的来源和内容”，不能仅凭 Kaggle 元数据证明完整的上游 provenance 或所有字段的版权归属。

## 官方身份与版本元数据

| 字段 | 官方值 |
| --- | --- |
| 标题 | `Food.com - Recipes and Reviews` |
| slug / ref | `irkaal/foodcom-recipes-and-reviews` |
| 数据集 ID | `1063627` |
| owner / creator | `irkaal` / `Alvin` |
| currentVersionNumber | `2` |
| 版本记录 | `versionNumber=2`, `status=Ready`, `versionNotes=Updates` |
| lastUpdated / version creationDate | `2020-12-28T15:56:04.647Z` |
| 页面许可 | `CC0: Public Domain` |
| 页面副标题 | `Data on over 500,000 recipes and 1,400,000 reviews from Food.com` |

来源：Kaggle `datasets/view` API 的字段 `ref`、`id`、`ownerRef`、`ownerName`、`currentVersionNumber`、`versions`、`lastUpdated` 和 `licenseName`；页面显示的标题、发布者、更新时间和许可与这些字段一致。[S1][S2]

## 数据规模与官方来源说明

Kaggle 官方 Data Card 的 Context/Content 说明如下：

- recipes 数据包含 **522,517** 条 recipe，来自 **312** 个不同类别；字段涉及烹饪时间、份量、食材、营养、步骤等。
- reviews 数据包含 **1,401,982** 条 review，来自 **271,907** 个不同用户；字段涉及作者、评分、评论文本等。
- recipes 和 reviews 各提供 Parquet 与 CSV 两种格式。官方推荐 `recipes.parquet` 和 `reviews.parquet`，理由是能够保留原始数据的 schema；`recipes.csv` 面向 R 解析，`reviews.csv` 不含 list-column，便于解析。
- 页面展示的 recipes.csv 数据视图为 **28 列**，示例列包括 `RecipeId`、`Name`、`AuthorId`、`AuthorName`、`CookTime`、`PrepTime`、`TotalTime`、`DatePublished`、`Description`、`Images`；该页面还明确说明部分 CSV 字段（`Images`、`Keywords`、`RecipeIngredientQuantities`、`RecipeIngredientParts`、`RecipeInstructions`）需要按 R 表达式解析。
- 页面给出的图片示例是 `img.sndimg.com/food/...` URL。它说明记录中存在图片 URL 字段，不等于图片文件随数据集分发，也不等于这些 URL 的再分发授权已被 CC0 页面元数据单独证明。

“来自 Food.com”是 Kaggle 发布者在官方 Data Card 中的来源说明；本次核验没有把搜索结果、博客或其他二手介绍当作来源证据。

## 官方文件列表

以下来自 Kaggle 官方文件列表接口 `datasets/list/irkaal/foodcom-recipes-and-reviews`，其中 `totalBytes` 是接口返回的文件大小，日期为接口原始 UTC 时间。

| 文件 | 创建时间（UTC） | `totalBytes` | 说明 |
| --- | --- | ---: | --- |
| `recipes.csv` | `2020-12-28T15:56:38.063Z` | 704,213,964 | recipes 的 CSV 表示 |
| `recipes.parquet` | `2020-12-28T15:56:18.120Z` | 178,723,234 | recipes 的 Parquet 表示，官方推荐 |
| `reviews.csv` | `2020-12-28T15:56:30.218Z` | 496,098,450 | reviews 的 CSV 表示 |
| `reviews.parquet` | `2020-12-28T15:56:20.432Z` | 173,762,142 | reviews 的 Parquet 表示，官方推荐 |
| **合计** | — | **1,552,797,790** | 四个文件的未压缩/文件大小合计 |

官方 `datasets/view` API 也返回 `totalBytes=1,552,797,790`，与上表合计完全相等。官方搜索/list 元数据返回 `totalBytes=758,217,627`，对应下载包大小。官方文件接口没有在本次响应中返回 checksum；因此不能声称仅凭 API 完成密码学级文件身份认证。

## 本地副本一致性比对

对已有 `data/meal/raw/foodcom-recipes-and-reviews-v2.zip` 执行了只读 `zipinfo -l`：

- ZIP 文件大小：**758,217,627 字节**，与官方搜索/list 元数据的下载包大小完全相等。
- ZIP 条目数：**4**。
- 条目名称与官方列表完全相同：`recipes.csv`、`recipes.parquet`、`reviews.csv`、`reviews.parquet`。
- 条目未压缩大小与官方逐项 `totalBytes` 完全相同，合计 **1,552,797,790 字节**。
- 本地 ZIP 的 SHA-256 为 `fa7910c9591997641448147809faa21b4bba539c01225fda2ab6b315b5f7527d`。Kaggle 本次公开元数据响应没有提供可对照的官方 checksum，所以该摘要只作为本地复核记录，不能单独证明它是官方下载的字节级副本。

以上一致性足以支持“本地包的文件布局和大小与 Kaggle v2 官方元数据相符”，但不替代官方 checksum 或对每个内容字节的独立比对。

## 许可与使用边界

- Kaggle 页面许可字段为 **CC0: Public Domain**，`licenseName` API 字段也是同一值。
- 页面给出的正式许可链接为 [Creative Commons CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/)。
- 当前报告只核验 Kaggle 页面级许可声明，不把它扩展解释为 Food.com、recipe 作者、review 作者或图片 CDN 对所有原始内容分别作出的独立授权。
- 本项目现有处理脚本保存的是图片 URL，不应据此批量下载或再分发图片；营养字段也应保留为来源数据的估算值，不能把 Kaggle 页面声明当成医学或营养准确性证明。

## 可复核官方 URL

- **Kaggle 数据集页面**：[https://www.kaggle.com/datasets/irkaal/foodcom-recipes-and-reviews](https://www.kaggle.com/datasets/irkaal/foodcom-recipes-and-reviews) [S1]
- **Kaggle Data 页面**：[https://www.kaggle.com/datasets/irkaal/foodcom-recipes-and-reviews/data](https://www.kaggle.com/datasets/irkaal/foodcom-recipes-and-reviews/data) [S1]
- **Kaggle 数据集 view API**：[https://www.kaggle.com/api/v1/datasets/view/irkaal/foodcom-recipes-and-reviews](https://www.kaggle.com/api/v1/datasets/view/irkaal/foodcom-recipes-and-reviews) [S2]
- **Kaggle 文件列表 API**：[https://www.kaggle.com/api/v1/datasets/list/irkaal/foodcom-recipes-and-reviews](https://www.kaggle.com/api/v1/datasets/list/irkaal/foodcom-recipes-and-reviews) [S3]
- **Kaggle 下载入口**：使用 [Kaggle 数据集页面](https://www.kaggle.com/datasets/irkaal/foodcom-recipes-and-reviews) 上的官方 **Download** 控件；本次未操作该控件。候选下载 API 路径的只读 `HEAD` 探测返回 404，按“不重复下载”约束未继续用 `GET` 探测或传输数据。
- **CC0 正式文本**：[https://creativecommons.org/publicdomain/zero/1.0/](https://creativecommons.org/publicdomain/zero/1.0/) [S4]

### 来源索引

- **[S1] Kaggle 官方数据集页面/Data Card**：标题、发布者、版本页面、Data Card 的 Context/Content、文件格式说明、schema 展示、许可和来源说明。
- **[S2] Kaggle 官方 `datasets/view` API**：版本、状态、更新时间、ID、owner、描述、许可、文件总大小和版本列表。
- **[S3] Kaggle 官方 `datasets/list/{owner}/{dataset}` API**：四个文件的名称、创建时间和逐项大小，以及下载包总大小可由搜索/list 元数据核对。
- **[S4] Creative Commons 官方 CC0 1.0 文本**：Kaggle 页面所链接的许可文本。
