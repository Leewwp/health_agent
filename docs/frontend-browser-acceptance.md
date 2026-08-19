# 前端浏览器验收记录（37 号：35 前端模块与用户页面）

- 验收日期：2026-08-11
- 验收环境：本地 fixture 后端（`--diet.agent.mode=fixture`，独立测试库 `diet_db_f37`，端口 8092）+ Nginx 同源反代（端口 8090，`deploy/nginx.conf`，静态托管 `frontend/`）+ 真实 Chromium（ego-browser）
- 验收方式：真实浏览器 DOM 断言（功能状态、toast、按钮态、DB 落库核对），桌面 1280×800 与移动端 390×844 两种视口

## 桌面流程

### 1. 聊天详情（含澄清与 Trace 摘要）

- URL：`http://127.0.0.1:8090/#/chat`
- 操作：发送“今晚想吃清淡一点，有什么推荐？”→ 后端返回 CLARIFY（“这顿主要是早餐、午餐还是晚餐？”+ 缺失槽位“用餐时间”chip + traceId/领域/任务/阶段 meta）→ 回复“晚餐”→ 返回 2 张餐食推荐卡（无图稳定占位 + 详情 + 收藏/喜欢/采纳/不合适）。
- 结果：通过。卡片点击打开详情抽屉（MEAL · id、推荐理由、反馈按钮）；traceId 按钮可跳转 `/admin/traces` 并自动选中该 Trace（Agent 路由/校验/降级事件 JSON 可查看）。

### 2. 餐食浏览、筛选与分页

- URL：`http://127.0.0.1:8090/#/meals`
- 操作：加载 295 条；筛选“菜系”（6 条结果，第 1/1 页）；收藏按钮（乐观更新为“收藏✓”+ toast“已收藏”，`recommend_feedback` 落库 `MEAL/88/FAVORITE`）；收藏失败回滚（注入不存在资源 id → toast“反馈资源不存在”，localStorage 回滚为 false）；打开餐食详情抽屉（营养、份量口径、来源、无图占位）。
- 结果：通过。

### 3. 动作筛选/收藏与详情

- URL：`http://127.0.0.1:8090/#/exercises`
- 操作：加载 30 条；难度“入门”筛选（12 条）；打开动作详情抽屉（plan_ready 徽章、目标/辅助肌群、风险标签、来源 gym-visual-exercises-dataset、媒体署名 © Gym visual）。
- 结果：通过。

### 4. 健康档案与周计划（DRAFT 编辑/激活/历史）

- URL：`http://127.0.0.1:8090/#/profile` → `http://127.0.0.1:8090/#/plans`
- 操作：档案保存（能量区间 1950-2150 kcal 展示）；生成草稿（7 天网格 27 项）；点击项目在抽屉内编辑日期/时间/备注（非法时间被前端校验拦截；合法修改后 toast“计划项目已保存”，DB `weekly_plan_item` 生效）；激活（第一份草稿因每日能量低于下限 5 kcal 触发 ENERGY_OUT_OF_RANGE WARNING，激活被正确拒绝——“警告可保存但不能激活”）；调整档案后重新生成并激活成功（DB `weekly_plan` DRAFT→ACTIVE，current_version=2）；ACTIVE 编辑副本（“已创建编辑草稿”，新 DRAFT 出现）。
- 结果：通过。发现并记录一处后端边界：自动生成的草稿可能因四舍五入略低于能量下限而无法激活（本票不改后端契约，留给 38 号总验收关注）。

### 5. 旧 /diet 兼容入口与 admin

- URL：`http://127.0.0.1:8090/#/diet`、`#/diet/chat`、`#/diet/meals/public`、`#/diet/meals/personal`、`#/admin/traces`、`#/admin/evaluations`
- 操作：旧首页/聊天/公共餐食（296 条）/个人餐食表单（7 槽位）；admin Trace 查询（6 行）与评估表单。
- 结果：通过。用户导航不含 admin 与旧入口，直接 hash 可访问。

## 移动端 390×844

- 操作：聊天/餐食/计划三页切换 + 计划抽屉编辑表单。
- 结果：通过。聊天/餐食/计划页面均无横向溢出（`scrollWidth <= innerWidth`）；详情抽屉占满屏宽（390=viewport）；导航无溢出。

## 媒体与慢接口

- 当前后端契约（ADR-0009）正式态为无图：浏览接口不下发 mediaUrl、聊天块餐食 mediaUrl 恒为 null（`MealModule.recommendMeals` 显式置 null）。无图占位已验证；`<img onerror → 稳定占位>` 兜底路径已实现（媒体 404 场景），待后端有媒体 URL 后可在 38 号复验。
- 慢接口：骨架屏验证（浏览页首次加载显示 6 张骨架卡，加载完成后替换）；失败重试按钮验证（502 期间显示“加载失败 + 重试”，恢复后重试成功）。

## 缺陷修复记录（验收中发现并修复）

1. nginx `Host $host` 丢端口导致非默认端口部署下写操作 Origin 校验 403 → 改 `$http_host`（`deploy/nginx.conf`）。
2. 收藏按钮缺 sessionId 上下文（后端反馈必填）→ 浏览页使用稳定客户端会话 id（store.getOrCreateClientSessionId）。
3. 页面重复渲染重复绑定事件导致反馈按钮双触发 → 每页 bind 单次 + 共享 UI 监听器全局单次。
4. 聊天残留旧 sessionId（后端重置后）导致永久“会话不存在” → 404 时清空会话并重试一次。
5. 浏览页加载完成回调用 `location.hash ===` 比较，带查询参数时永不命中 → 改用 router.currentRoute()。
6. 计划页加载完成后条件恒真导致无限请求循环 → 增加 loaded 一次性标记。
7. legacy.js 顶层 `import { dietChat }` 与本地 `export function dietChat` 命名冲突导致模块加载失败 → 导入改名。
8. 抽屉只认 resource.resourceType，浏览条目无该字段导致详情渲染成作息模板 → 与卡片一致的字段推断。
9. 静态资源无缓存头导致旧模块被浏览器缓存 → nginx `Cache-Control: no-cache`。

## 58 号票补充验收（2026-08-11，健身槽位词汇一致性）

- 验收环境：fixture 后端（`-Ddiet.agent.mode=fixture -Ddiet.rag.mode=structured`，端口 8092，使用 `diet_db` 正式库）+ 临时 Nginx 同源反代（端口 8093，`host.docker.internal:8092`）+ ego-browser 真实 Chromium；桌面 1710×983 与移动端 390×844 两种视口。
- 聊天页健身澄清：URL `http://localhost:8093/#/chat`，发送“推荐全身训练”→ 后端返回 CLARIFY（“你今天想练哪个部位？”），缺失槽位 chip 显示中文“训练部位”，不暴露 `bodyParts`/`trainingGoal` 内部字段名；桌面与移动视口均通过；浏览器控制台无 error/warning。
- 2026-08-12 复核补证：同一 URL 和交互在 1710×983、390×844 重新通过；复核时发现长 sessionId 会把移动端 Grid 撑到 667px，已补 `min-width: 0`、长标识换行和按钮单行约束，最终 `scrollWidth=clientWidth=390`。截图：[桌面](evidence/issue-58-chat-desktop.png) / [移动端](evidence/issue-58-chat-mobile.png)。
- 周计划链路归一验证：同一匿名身份 PUT 健康档案（175cm/70kg/轻活动/维持 → 能量区间 2150-2400）→ POST `/api/v1/health/plans/drafts` 生成草稿（planId 15），EXERCISE 项目 params.bodyPart 为 Provider 归一后的中文值（“胸”/“核心”/“背”），无英文原始值泄漏；餐食/动作/作息快照同源。
- 说明：`cardio → 全身` 归一与“全身”召回登山者/波比跳由 `DbReviewedResourceProviderTest`（真实 seed 行 0630）与 `ExerciseModuleTest` 断言覆盖；fixture 意图 Agent 关键词不含“全身”，聊天路径无法在 fixture 模式触达“全身”推荐，故该断言落在单元层。
- 清理：验收后停止 8092 fixture 后端与 8093 nginx 容器；8090 用户实例与正式库未受影响。

## 环境清理

- 验收后已停止本地 fixture 后端与 Nginx 容器；测试库 `diet_db_f37` 保留（独立于 `diet_db`，不影响真实 API key 测试流程）。

## 62 号票补充验收（2026-08-12，档案结构化风险字段与计划写入口 Guard）

- 验收环境：本地后端（`mvn spring-boot:run` 重启加载 V7 迁移，`diet_db` 正式库，端口 8080）+ Nginx 同源反代（`health-nginx-test` 容器端口 8090，静态托管 `frontend/`，无构建步骤故前端改动即时生效）+ ego-browser 真实 Chromium；桌面 1280 视口 + 移动端 390×844 两种视口。
- 档案页风险字段：URL `http://127.0.0.1:8090/#/profile` → 表单新增「身体状况（选填）」区（5 个复选框：孕产/当前伤病/术后康复/进食障碍/需医疗干预慢病）+「风险说明（选填）」文本框（maxlength=200）→ 勾选"当前伤病"、填写"右肩扭伤恢复中"并保存 → 摘要行显示「档案版本 v1 · 估算标记：是 · 身体状况：当前伤病（右肩扭伤恢复中）」、toast「健康档案已保存」；刷新后复选框与说明原样回填（DB 核对：`risk_conditions_json=["CURRENT_INJURY"]`、`risk_note=右肩扭伤恢复中`）。
- 计划写入口直达阻断：不经过聊天，同一身份直接 `POST /api/v1/health/plans/drafts` → HTTP 409 + `{"code":"RISK_BLOCKED","message":"当前情况不适合生成具体计划，建议咨询专业医生或营养师。"}`；浏览器在 `#/plans` 点击「生成新草稿」→ toast 显示同一固定文案，列表保持空；DB 核对风险用户 `weekly_plan` 0 行（无计划/版本/项目半成品）。
- 正常档案仍成功：风险字段缺省（NULL）时同一计划 API 正常生成 DRAFT（curl 冒烟 + 既有自动化测试覆盖）。
- 未知枚举与非法组合：`riskConditions:["BOGUS_CONDITION"]` → 400 BAD_REQUEST「请求体格式错误」（新增 HttpMessageNotReadableException 处理，不再落 500）；孕产条件 + 男性性别 → 400「孕产风险条件与男性生理性别冲突」。
- 移动端 390×844：档案页风险区完整渲染（5 复选框 + 说明框），`scrollWidth=clientWidth=390` 无横向溢出。
- 说明：本次验收以 DOM 断言 + toast + DB 落库核对为准（沿用 37 号验收方式）；ego-browser 截图服务本次不可用（CDP Page.captureScreenshot 超时，浏览器侧问题，不影响断言）。
- 清理：验收身份为匿名 Cookie 用户（DB 中 `user_id=5750655426821178388`），无计划残留。

## 64/65/61 号票补充验收（2026-08-13，动作浏览槽位归一、收藏/取消收藏、MCP 经 Nginx 安全边界）

- 验收环境：本地后端重启为最新代码（杀掉 2026-08-12 启动的旧进程，仓库根 `nohup mvn spring-boot:run`，`diet_db` 正式库，端口 8080；MCP token 与 Origin allowlist 仅通过环境变量注入，具体值不记录；dev profile 默认 `allow-missing-origin=true`）+ `health-nginx-test` 容器（8090→80，静态托管 `frontend/`）+ ego-browser 真实 Chromium；桌面 1710×983（emulation override 后 `innerWidth=1710`）+ 移动端 390×844 两种视口。
- 环境修复（验收前置）：`health-nginx-test` 容器挂载的 `/var/folders/.../opencode/nginx-local.conf` 是 29 小时前的旧版本（无 `/mcp` location，`POST /mcp` 直接 nginx 405），已按当前 `deploy/nginx.conf` 重新生成（`127.0.0.1:8080` → `host.docker.internal:8080`）并 `nginx -s reload` 后恢复。
- #61 MCP 经 Nginx（`curl -X POST http://localhost:8090/mcp`）：无 Authorization → 401 `{"error":"缺少或无效的 MCP Bearer token"}`；错误 token → 401 同上；合法 token + `Origin: http://evil.example` → 403 `{"error":"Origin 不在允许列表"}`；合法 token + `Origin: http://localhost:8090` + `Accept: application/json, text/event-stream` → 200 initialize JSON-RPC（`serverInfo: health-agent-mcp 0.1.0`）；带 `Mcp-Session-Id` 续调 `tools/list` → 200，4 个工具（calculate_targets / get_meal_detail / search_meals / get_routine_facts）全中文 schema 描述。全程经 Nginx 反代，无直连。
- #64 动作浏览页（URL `http://localhost:8090/#/exercises`，桌面 + 移动）：
  - 列表来自浏览 API（`/api/v1/health/exercises` 分页拉全，共 30 条），槽位全部为归一中文：训练部位「背/核心/肩/全身/手臂/腿/胸」、器材「弹力带/徒手/哑铃」、难度「进阶/入门」、动作模式「蹲/核心/踝/髋/拉/推/有氧」，筛选下拉无英文原始值。
  - 搜索「登山者」→ 单卡显示「进阶 / 全身 / 徒手」；详情抽屉显示「训练部位：全身 · 难度：进阶 · 动作模式：有氧」+ 目标肌群「核心」，全文无 `cardio`/`body weight` 等英文原始值（DOM 正则断言 + 抽屉内文本核对）。
  - 筛选：难度=入门 → 12 条；分页：30 条 / 每页 18 → 第 1/2 页 18 卡 ↔ 下一页 12 卡 ↔ 上一页回 18 卡，页码文案正确。
  - 归一机制佐证：DB `exercise_item` 行 38/58/59/60 存英文原始值（`cardio`/`body weight`/`upper legs`/`waist`/`中级`），`DbReviewedExerciseReader` 归一后 API 输出全中文（38 登山者→全身/徒手/进阶/有氧；60 侧平板支撑→核心/徒手/进阶/核心）；合法映射值不产生日志，只有真正未收录值才从用户标签集合过滤并打 WARN。
  - 移动端 390×844：卡片/筛选/抽屉正常，`scrollWidth=clientWidth=390` 无横向溢出，导航无溢出。
- #65 收藏/取消收藏（网络 payload 拦截 + DOM 状态 + 刷新持久 + DB 落库四方核对）：
  - 桌面 EXERCISE 38（登山者）：点收藏 → 按钮「收藏✓」`aria-pressed=true`、toast「已收藏」，请求 `action=FAVORITE`；再点 → 请求 `action=UNFAVORITE`（前端 feedback-control.js 不再重复发 FAVORITE）、按钮回「收藏」`aria-pressed=false`、toast「已取消收藏」，localStorage 收藏项清除；刷新页面后仍「收藏」（未收藏态）。
  - 桌面 MEAL 2310 同流程通过（`action=FAVORITE` → `action=UNFAVORITE` → 刷新后未收藏）。
  - 移动端 390×844：EXERCISE 38 同流程通过（toast「已收藏」/「已取消收藏」）。
  - DB 核对 `recommend_feedback`：会话 `sess_d72a0e...` 下 EXERCISE/38（桌面+移动）与 MEAL/2310 各 `FAVORITE`+`UNFAVORITE` 成对落库（latest-wins，UNFAVORITE 撤销收藏贡献）。
  - 全程控制台无 error/warning（桌面 + 移动，多次采样含 `Log.entryAdded` 与 `Runtime.exceptionThrown`）。
- #65 失败回滚复验（2026-08-13 收尾）：390×844 动作页注入网络失败后，收藏 localStorage 与 `aria-pressed` 均回滚为未收藏，toast 展示失败原因，且 `unhandledrejection` 事件数为 0；页面宽度仍为 `scrollWidth=innerWidth=390`。
- 发现的 Bug（本次验收复现，**先报告未改代码**）：浏览页共享实现（`frontend/assets/js/pages/browse.js`）跨页面事件委托互相覆盖——每个 `createBrowsePage` 实例都把 `click/change/submit` 委托监听器绑到同一个 `#app` 上且从不解绑，后访问的页面会把另一个页面重新渲染到当前路由下。复现（纯应用内导航，无刷新）：`#/meals` 加载 → 点导航进 `#/exercises`（正常显示健身动作库）→ 在动作页搜索「登山者」或切换难度筛选 → URL 仍是 `#/exercises`，但 `#app` 被餐食页整体覆盖（显示「餐食库」+「没有符合筛选条件的数据」，且筛选值串到餐食数据集）。反向同样：`#/meals` 上改「用餐时间」筛选后会被动作页覆盖（视访问顺序谁后绑定谁赢）。无 console error、无未捕获异常，纯监听器串扰 + 渲染竞态。影响 #64 的搜索/筛选/分页验收流程（首次访问动作页时全部通过，访问过餐食页后即触发）；`browse.js` 不在本批次工作区 diff 内，属既有问题，建议后续单独修（如模块级单例绑定 + 路由守卫，或解绑/按当前路由分发）。
- 截图：captureScreenshot CDP 超时（沿用 62 号已知浏览器侧问题），以 DOM 断言为准。

## 浏览页监听器串扰修复复验（2026-08-13）

- 修复：`browse.js` 的 click/change/submit 委托增加当前 route 守卫，submit 仅处理当前浏览页的筛选表单；旧页面实例的监听器即使仍绑定在 `#app`，也不能处理另一路由事件。
- 真实 Chromium，经 Nginx `http://localhost:8090`：`#/meals`（20 卡）→ `#/exercises`（18 卡）→ `#/meals` → `#/exercises` → 在搜索表单提交「登山者」；最终 URL 保持 `#/exercises`，标题保持「健身动作库」，仅显示 1 张「登山者」卡（进阶/全身/徒手），未再被餐食页覆盖。
- 当前代码独立实例回归（Spring Boot `8094` + Nginx `http://localhost:8095`）：`#/meals` 首屏 20 卡，点击首张「黑豆千层面」打开详情抽屉，抽屉名称与来源字段正常显示，证明迁移后的 reviewed 分页与详情链路可用。
- 页面加载、应用内导航、搜索表单提交与 DOM 结果均通过；截图仍因 ego/CDP `Page.captureScreenshot` 超时失败，与本页前述环境问题一致。
- 清理：验收无残留数据（收藏/取消收藏成对落库为业务正常事件流）；后端（8080）与 `health-nginx-test`（8090）验收后保持运行供继续验收。

## MCP 外部客户端连续全链复验（2026-08-13）

- 当前 HEAD 独立实例 `http://localhost:8094/mcp`，客户端 `batch-closeout-review/1.0.0`，通过同一个 `Mcp-Session-Id` 连续完成 `initialize → tools/list → tools/call`。
- `tools/list` 返回 4 个工具，均同时包含 `inputSchema` 与 `outputSchema`，对象 schema 均为封闭对象（`additionalProperties=false`）。
- 四次连续调用全部成功且返回 `structuredContent`：`search_meals(slots.mealTime=早餐, limit=1)` 返回 1 条；`get_meal_detail(mealId=2310)` 返回黑豆千层面；`get_routine_facts(keyword=睡多久)` 返回 3 条；`calculate_targets(age=30, sex=MALE, heightCm=175, weightKg=70, activityLevel=MODERATE, goal=MAINTAIN)` 返回 2450-2700 kcal。
- Authorization、Origin 与 session 均通过同一 MCP endpoint 验证；token 仅注入本地进程，未写入仓库或文档。

## #78–#82 健康聊天稳定化 Agent 模式降级复验（历史中间状态，2026-08-18）

- 验收环境：当前工作树 Spring Boot（AgentScope 模式，后端端口 `18081`）+ `health-agent-media-ui` Nginx 同源入口 + ego-browser 真实 Chromium，URL `http://127.0.0.1:18090/#/chat`，视口 1710×983；使用新的匿名浏览器会话，凭证只从 gitignore 配置读取，未输出或写入仓库。
- 健身闭环：发送“帮我推荐一份适合新手的轻量训练”→ `EXERCISE / RECOMMEND / CLARIFY`，只追问训练部位；回复“胸肌”→ `EXERCISE / RECOMMEND / RESPOND`，显示靠墙俯卧撑、俯卧撑、双杠臂屈伸 3 张动作卡，均来自 `gym-visual-exercises-dataset`，无餐食卡。
- 跨域餐食：在同一会话发送“中午吃什么”→ 显式切换为 `MEAL / RECOMMEND / CLARIFY`，未继承胸部等动作槽位；回复“清淡点”→ `MEAL / RECOMMEND / RESPOND`，只显示清蒸鱼、柠檬腌梅蒸鲳鱼 2 张审核公共餐食卡，无动作卡。
- 作息事实：发送“晚上几点前应该停止喝咖啡？”→ `ROUTINE / RECOMMEND / RESPOND`，直接返回睡前约 6 小时停止咖啡因的事实卡，并显示 Drake 等 J Clin Sleep Med 2013 双盲 RCT 来源，无餐食或动作卡。
- 无关问题：发送“推荐一部电影”→ `OTHER / CHAT / RESPOND`，明确说明超出健康助手范围，未显示任何健康资源卡。
- 配套门控：`docker compose config --quiet` 通过；普通 Maven 套件 656 个测试中 619 通过、37 个环境门控跳过；启用真实 MySQL 8.4 门控后 653 通过、仅 3 个 Qdrant 门控跳过。当前 macOS/JDK 21 通过显式加载项目已有 Byte Buddy agent 运行 Mockito，未修改生产代码。
- live model 状态：本段记录的中间运行中 `qwen3.7-flash` 调用均为 `request timed out`，Embedding 调用为 HTTP 404；当时的页面行为由确定性降级完成，不能作为“真实模型成功”证据。随后已在 #82 最终验收和 #84 收尾中补齐可用配置下的运行/降级证据。

## #82 真实 Chromium 全链最终验收（2026-08-18）

- 环境：当前分支 Spring Boot `18081`，仅通过绝对路径 `spring.config.import` 读取主工作区 gitignore `.env`；临时 Nginx `http://127.0.0.1:18091` 明确挂载当前 worktree 的 `frontend/`。命令行未覆盖 MaaS URL 或超时，未输出、复制或提交凭证。桌面 Chromium 1710×983。
- 配置回归：`application.yml` 现在显式消费 `DIET_AGENT_TIMEOUT_MS`/`DIET_EMBEDDING_TIMEOUT_MS`；`DotenvConfigDataTest` 以临时无密钥配置验证 workspace chat/embedding URL 与 60000/10000 ms 超时均进入 Spring Environment。
- PLAN：点击“新会话”后生成新的显式 `sess_web_*`，发送“帮我安排一下这周的健身计划”→ `EXERCISE / PLAN / RESPOND`、无资源卡，显示“进入周计划页面”；点击后到达 `#/plans`，页面显示“还没有周计划”，证明聊天未创建草稿。
- 健身与跨域餐食：在第二个隔离会话发送“帮我推荐一份适合新手的轻量训练”→ `EXERCISE / RECOMMEND / CLARIFY`；回复“大腿”→ 3 张 EXERCISE 卡；随后“中午吃什么”→ `MEAL / RECOMMEND / CLARIFY`，回复“清淡点”→ 2 张 MEAL 卡，无跨类型资源。
- 作息/越界/风险：“晚上几点前应该停止喝咖啡？”返回 `ROUTINE / RECOMMEND / RESPOND` 与 Drake 2013 来源；“推荐一部电影”返回 `OTHER / CHAT / RESPOND` 且无卡；“我现在胸痛，但还想做高强度训练”返回 `EXERCISE / RECOMMEND / BLOCKED` 与固定安全文案。
- live Trace：两个新会话共 8 条 Trace 全为 SUCCESS。IntentAgent=`qwen3.7-flash`（10.5–18.7 秒），RecommendResponseAgent 请求配置名=`qwen-turbo`（18.2–21.8 秒）；全部 Agent event error 为空，意图 `degraded=false/fallbackReason=null`，三次响应 Agent `fallbackReason=null`，无 timeout。餐食检索的 Embedding 不再是 `embedding_unavailable`；因本次启动显式关闭 index-on-startup，向量库无命中时按契约记录 `no_vector_hits` 并回到 STRUCTURED。
- 证据校正：后续审计发现旧 `AgentScopeInvoker` 以历史名称 `qwen-max` 判断是否选择主模型 Bean，因此上述 Trace 能证明真实 Agent 调用成功和请求配置名，但不能单独证明响应 Agent 底层使用了主模型 Bean。现已改为显式 `MAIN/LIGHT` 职责路由并增加回归测试；修复后的主模型 live 延迟证据由 #84 重新采集。
- 自动化：PLAN/配置聚焦 39/39；普通全量 658（621 通过 + 37 环境门控跳过）；真实 MySQL 门控 658（655 通过 + 3 个 Qdrant 门控跳过）；`docker compose config --quiet` 通过。
- 清理：后端与临时 `health-agent-issue82-ui` 容器均已停止；浏览器任务空间在 GitHub 关票完成后关闭。

## #84/#88 收尾补充验收（2026-08-19）

- #84 临时同源验收入口：`http://127.0.0.1:18092/#/chat`；独立 Spring Boot 运行在 `18080`，验收结束后已停止，不影响既有本地服务。
- 餐食页加载审核库 295 条；打开首张餐食详情抽屉，抽屉显示营养、来源、过敏原和标签；按 `Escape` 关闭后 `drawer-root[aria-hidden]` 恢复为 `true`。
- 聊天发送明确作息请求后，页面显示等待文案，表单 `aria-busy=true`，输入框禁用，发送按钮切换为“等待中”；慢请求结束后等待文案消失，控件恢复，显示中文失败提示“暂时无法完成推荐，请稍后重试。”。
- 前端行为测试 11/11 通过；完整 Maven 双门控 `mvn test -Ditest.mysql=true -Ditest.qdrant=true` 为 661 tests、0 failures。Qdrant 真实证据见 `data/reports/rag_evaluation.json`：295/295 索引、60/60 `REAL_HYBRID`、0 降级。
- 这是本地真实 Chromium 的交互/恢复补充验收；公网 HTTPS、训练计划 Agent 生成和管理员 Trace 全链仍属于 #89 的发布验收范围。

## #85-#87 训练计划与 Trace 核心流程（2026-08-19）

- 验收环境：当前工作树 Spring Boot `8082` + 同源 Nginx `8092` + ego-browser 真实 Chromium；桌面 `1710×983`，移动端 `390×844`。凭证只从本地未提交配置读取，未写入仓库或本文档。
- 聊天 URL：`http://localhost:8092/#/chat`。完整训练偏好生成简报后，“确认训练偏好”单击即提交，输入框保持为空并直接出现“生成训练计划”，无需用户再次手动发送。
- 计划 URL：`http://localhost:8092/#/plans`。真实 `qwen-turbo` 在约 1.27 秒内从 3 个审核候选生成 `Agent 生成` 草稿；页面展示周一/三/五训练和七日辅助计划。修正偏好产生的新 `requestId` 会真正重试，不复用此前失败状态。
- Trace URL：计划页“查看本次 Trace”直接打开对应 `trace_adbcd5d4a99d4e558073fcb8ed717562`，展示 `SUCCESS`、模型 `qwen-turbo`、`PARSED`、`PLAN_GUARD_PASSED` 和 5 步时间线；“脱敏原始 JSON”使用关闭的 `<details>`，默认 `open=false`。
- 移动端检查：计划页 `clientWidth=scrollWidth=390`，来源标签、七日项目和 Trace 入口均存在，无文档级横向溢出。
- 截图：[Agent 计划桌面](evidence/issue-86-agent-plan-desktop.png) / [Agent Trace 桌面](evidence/issue-86-agent-trace-desktop.png) / [Agent 计划移动端](evidence/issue-86-agent-plan-mobile.png)。

## #90–#94 计划语义收敛验收（2026-08-20）

- 验收环境：当前工作树 Spring Boot `8091` + Nginx 同源代理 `8090` + ego-browser 真实 Chromium，任务空间 `38`；计划相关 URL 为 `http://localhost:8090/#/chat`、`#/profile`、`#/plans`、`#/admin/traces`。凭证只由本地未提交配置注入，未输出或写入本文档。
- 口语简报与中断续轮：在聊天页输入训练计划请求，使用“三天，二四六”“下午六点至七点”完成日期/时间澄清；切换到“今晚清淡的晚餐”后只进入餐食推荐链，发送“回到训练计划”后原训练简报继续显示，未重置已收集字段。
- 缺档案往返：训练简报确认后触发缺档案入口，进入 `#/profile` 保存档案，再返回聊天确认；简报仍存在，确认后出现对应的训练计划生成操作。
- 范围正向链路：分别走训练、餐食和综合计划确认/生成；计划详情显示训练计划无餐食/作息项目、餐食计划无训练/作息项目，综合计划显示 3 个训练项目 + 21 个餐食项目，共 24 个项目。综合计划“全部/训练/餐食”筛选结果分别为 24/3/21。
- 计划与 Trace：在 `#/plans` 查看并激活训练、餐食、综合计划，打开现有详情编辑抽屉完成一次副本编辑；进入 `#/admin/traces` 查看综合 Trace `trace_236d0cd9fabd4071a1098bf91e644d45`，时间线包含 `PLAN_PERSISTED`，详情显示 `planScope=COMPOSITE`。
- 响应式矩阵：桌面 `1710px`、中等宽度 `1024px`、平板 `768px`，以及移动 `414/390/375/320px` 均检查 `document.documentElement.clientWidth === scrollWidth`；计划详情为主宽度，历史计划收敛为紧凑选择器，移动端按日期纵向展示。1、3、21、29 项目数据、长中文餐名/动作名、状态标签和按钮均未叠加、裁切或产生不可达内容。
- 可访问性与交互：计划选择器、综合分段筛选、计划项目和详情抽屉均可通过键盘聚焦/操作；Escape 可关闭详情抽屉；没有使用 `overflow-x: clip` 掩盖布局问题。浏览器控制台未发现本轮页面的渲染错误。
- 截图：[计划桌面](evidence/issue-90/plan-desktop.png) / [计划移动端](evidence/issue-90/plan-mobile.png) / [综合筛选](evidence/issue-90/composite-filter.png)。

## 本地 #89 交接边界

- #90 及 #91–#94 的本地开发、自动化门控、真实模型 smoke 和真实浏览器验收已具备解除 #89 阻塞的证据；完整测试数字和迁移证据见 `docs/release-evidence.md`。
- 本记录不包含公网 URL、HTTPS、DNS/TLS、云服务器、远端 Compose、生产数据库或云端密钥结果；这些均明确留给 #89，当前任务在此停止。
