# 计划资源详情

Type: task
Status: ready-for-agent
Blocked by: none
Priority: P0
Estimated complexity: 中
External dependency: reviewed reader
Database migration: 否，除非补充资源快照字段

## Goal

让计划成为查看餐食和动作详情的中心入口。

## Scope

- 计划周表展示餐次、热量、训练部位、时长、组数和次数。
- 增加按资源类型和 ID 获取餐食/动作详情的读取 seam。
- 计划项目点击后在详情抽屉懒加载资源详情。
- 餐食显示营养、份量、食材和过敏原；不生成制作方法。
- 动作显示步骤、肌群、器材、难度、风险提示和媒体。

## Response Contract

- 计划项目继续返回不可变的 `resourceType/resourceId`、计划参数和名称快照。
- 详情读取按 `resourceType + resourceId` 走 reviewed reader；详情不存在时返回稳定 404，不伪造数据。
- 抽屉先展示计划快照，再异步读取资源详情；详情读取失败不清空已展示的计划参数。
- 营养、份量、食材、过敏原、动作步骤、肌群、器材、难度、风险和媒体字段必须定义单位及空值语义。

## Acceptance

- 计划表不依赖离开页面即可理解主要执行参数。
- 详情读取遵守 reviewed reader 架构边界。
- 资源不存在时有明确错误，不伪造详情。
- 桌面和移动浏览器详情抽屉均可操作。
- 浏览器验证覆盖详情加载中、成功、404 和网络失败；普通详情请求不应一次读取完整目录。
