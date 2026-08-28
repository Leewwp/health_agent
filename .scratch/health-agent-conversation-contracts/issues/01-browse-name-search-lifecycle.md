# 浏览页名称搜索事件生命周期

Type: task
Status: ready-for-agent
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
