# 前端修复复验截图

测试入口：`http://127.0.0.1:8090/`

测试环境：Spring Boot `8091` + Nginx 同源代理 `8090` + 真实 Chromium。桌面视口为 1280×800，移动端视口为 390×844。

## 核心页面与交互

1. [01-chat-initial.png](./01-chat-initial.png) - 健康聊天初始页
2. [02-meals-list.png](./02-meals-list.png) - 餐食库
3. [03-exercises-list.png](./03-exercises-list.png) - 健身动作库
4. [04-profile.png](./04-profile.png) - 健康档案
5. [05-plans.png](./05-plans.png) - 我的计划
6. [06-plan-create-modal.png](./06-plan-create-modal.png) - 生成草稿页面内模态框
7. [07-exercise-detail-drawer.png](./07-exercise-detail-drawer.png) - 动作详情抽屉
8. [08-mobile-chat.png](./08-mobile-chat.png) - 移动端聊天页
9. [09-mobile-plan-modal.png](./09-mobile-plan-modal.png) - 移动端计划模态框

## 兼容与管理页面

10. [10-legacy-home.png](./10-legacy-home.png) - 旧版兼容首页
11. [11-legacy-chat.png](./11-legacy-chat.png) - 旧版兼容聊天
12. [12-legacy-public-meals.png](./12-legacy-public-meals.png) - 旧版公共餐食
13. [13-legacy-personal-meals.png](./13-legacy-personal-meals.png) - 旧版个人餐食
14. [14-admin-traces.png](./14-admin-traces.png) - Trace 调试
15. [15-admin-evaluations.png](./15-admin-evaluations.png) - 评估报告

## 复验结果

- 计划页不再触发原生 `prompt` / `confirm`；模态框打开后焦点进入输入框，Escape 关闭并恢复到触发按钮。
- 抽屉打开后焦点进入关闭按钮；正文独立滚动，底部操作栏保持在视口内；路由切换后抽屉完整清理。
- 390px 下五个导航项保持单行并完整显示，最小触控高度 44px，页面无横向溢出。
- 交互控件键盘焦点环为 3px 实线；路由主容器的程序化焦点不显示整块外框。
- 浏览器控制台无错误；前端 JavaScript 语法检查和 `git diff --check` 通过。
