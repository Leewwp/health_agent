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
