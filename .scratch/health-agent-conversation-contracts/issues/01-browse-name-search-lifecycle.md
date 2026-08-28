# 浏览页名称搜索事件生命周期

Type: task
Status: resolved / ready-for-human
Blocked by: none
Priority: P1
GitHub: https://github.com/Leewwp/health_agent/issues/105

## Goal

修复餐食库和动作库名称输入回车后原生刷新、搜索无效或跨页面监听器串扰的问题，并固定浏览器行为。

## Acceptance

- 餐食库名称回车不触发文档导航，请求只发送到餐食 API 并带 `q`。
- 动作库名称回车不触发文档导航，请求只发送到动作 API 并带 `q`。
- 餐食→动作→餐食切换后，旧页面监听器不会处理当前表单；搜索结果和资源类型与当前页面一致。
- 事件监听器可销毁或由唯一路由委托管理，不随页面实例累积。
- 前端 Node 测试覆盖 submit、`preventDefault`、`q` 透传和跨路由切换；真实 Chromium 记录 Network 无文档导航。
- 后端 Controller/Reader 参数透传测试覆盖中文名和英文名匹配，审核资源边界保持不变。

## Notes

现有 API/Reader/SQL 链路已经支持 `name/name_en LIKE`。不要通过放宽 SQL 或改变分页语义掩盖前端事件问题。

## 实现与验收记录（2026-08-28）

- 监听器生命周期：`frontend/assets/js/pages/browse.js` 改为**唯一路由事件委托**——所有浏览页共享同一组绑定在 `#app` 的 click/change/submit 监听器（模块级 `delegationBound` 保证只绑一次），按 `currentRoute()` 分发给活跃页面控制器；餐食↔动作来回切换监听器数量恒为 1 套，不随页面实例累积。
- 回车契约：`handleSubmit` 保留 `preventDefault`、路由守卫与 `form[data-browse-filter='1']` 匹配；搜索词经 `state.filters.search` → `load({q})` → `listMeals/listExercises`（空值由 `toQuery` 剔除）。
- 后端不变：`HealthResourceController` `q` 参数、`Meal/ExerciseBrowseService` 分页校验、`DbReviewed*Reader` filtered SQL（`name/name_en LIKE`）与审核边界（APPROVED + PUBLIC）零改动。
- 前端测试：新增 `frontend/tests/browse-search.test.mjs`（3 用例，最小浏览器垫片驱动真实模块）：回车 preventDefault、只向对应 API 发 `q`、跨路由切换监听器不累积、切回后旧搜索词恢复。
- 后端测试：`DbReviewedMealReaderTest`/`DbReviewedExerciseReaderTest` 新增 q 透传与空白归一用例；`HealthResourceControllerTest` 新增正式库 q 透传 + 未知筛选键丢弃用例；`MysqlReviewedReadersIntegrationTest` 新增真库中英文名称搜索用例（16/16 执行）。
- 真实 Chromium 证据：`docs/frontend-browser-acceptance.md`（餐食/动作库回车搜索无文档导航，Network 仅 XHR）。
