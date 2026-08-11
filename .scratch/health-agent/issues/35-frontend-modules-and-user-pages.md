# 35 前端模块与用户页面

- Type: task
- Status: resolved
- Triage: ready-for-agent
- Depends on: 32, 33, 34

## Scope

将现有静态前端迁移为独立 `frontend/`，按 18 号信息架构拆成原生 ES Modules，交付聊天、餐食、动作和周计划用户页面。

## Must do

- 保留 vanilla hash 路由，旧 `/diet` 路由提供兼容入口；
- 删除 `src/main/resources/static/` 双副本，Nginx 托管 `frontend/`；
- 拆出 `router/api/store/ui/pages/admin` 模块；
- 复用统一详情抽屉、资源卡片、反馈控件、媒体状态和 Toast；
- 实现餐食/动作筛选、分页、来源、无图/媒体 404、空数据和失败重试；
- 实现收藏乐观更新、失败回滚、喜欢/不喜欢/采纳反馈；
- 实现计划 DRAFT 编辑、日期/时间移动、备注、激活确认和历史版本展示；
- admin 不出现在用户导航，生产由后端 token 隔离；
- 完成桌面和 `390×844` 移动端真实浏览器验收。
- 在开发/演示配置中展示 Trace 摘要，使面试演示能够说明 Agent 路由、校验和降级；生产用户导航不暴露管理详情。

## Done when

聊天详情、动作筛选/收藏、计划编辑/激活三条桌面流程通过；移动端无横向溢出；媒体失败和慢接口不破坏布局；详情抽屉在不同资源入口保持一致。

## Answer

已完成 `frontend/` 原生 ES Modules 迁移、Nginx 同源反代和桌面端/`390×844` 移动端真实浏览器验收。验收证据见 `docs/frontend-browser-acceptance.md`，GitHub 对应实施票已关闭。
