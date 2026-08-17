# 前端页面审查截图

测试入口：`http://127.0.0.1:8090/`

运行方式：Spring Boot `dev + fixture`，后端 `8091`，Nginx 同源代理 `8090`，MySQL 审核库；桌面截图为 1280×800，移动截图为 390×844。

## 桌面流程

1. [01-chat-initial.png](./01-chat-initial.png) - `/chat` 初始状态
2. [02-meals-list.png](./02-meals-list.png) - `/meals` 餐食列表
3. [03-exercises-list.png](./03-exercises-list.png) - `/exercises` 动作列表
4. [04-profile.png](./04-profile.png) - `/profile` 档案
5. [05-plans-empty.png](./05-plans-empty.png) - `/plans` 空计划状态
6. [06-legacy-home.png](./06-legacy-home.png) - `/diet` 兼容首页
7. [07-legacy-chat.png](./07-legacy-chat.png) - `/diet/chat` 兼容聊天
8. [08-legacy-public-meals.png](./08-legacy-public-meals.png) - `/diet/meals/public` 公共餐食
9. [09-legacy-personal-meals.png](./09-legacy-personal-meals.png) - `/diet/meals/personal` 个人餐食
10. [10-admin-traces-empty.png](./10-admin-traces-empty.png) - `/admin/traces` 空状态
11. [11-admin-evaluations-empty.png](./11-admin-evaluations-empty.png) - `/admin/evaluations` 空状态
12. [12-chat-clarify.png](./12-chat-clarify.png) - 聊天澄清“用餐时间”
13. [13-chat-recommendations.png](./13-chat-recommendations.png) - 聊天餐食推荐
14. [14-meal-detail.png](./14-meal-detail.png) - 餐食详情抽屉
15. [15-meals-filtered.png](./15-meals-filtered.png) - 餐食“西餐”筛选
16. [16-exercises-filtered.png](./16-exercises-filtered.png) - 动作“入门”筛选
17. [17-exercise-detail.png](./17-exercise-detail.png) - 动作详情抽屉
18. [18-profile-saved.png](./18-profile-saved.png) - 档案保存后的底部状态
19. [19-profile-saved-top.png](./19-profile-saved-top.png) - 档案保存后的首屏
20. [20-plan-empty-recheck.png](./20-plan-empty-recheck.png) - 计划生成流程复核时的空态
21. [26-legacy-chat-response.png](./26-legacy-chat-response.png) - 旧聊天切换公共库后发送问题
22. [27-admin-traces-populated.png](./27-admin-traces-populated.png) - admin Trace 查询后的当前态（仍为空）

## 移动流程

23. [21-mobile-chat.png](./21-mobile-chat.png) - `/chat`，390×844
24. [22-mobile-meals.png](./22-mobile-meals.png) - `/meals`，390×844
25. [23-mobile-exercises.png](./23-mobile-exercises.png) - `/exercises`，390×844
26. [24-mobile-profile.png](./24-mobile-profile.png) - `/profile`，390×844
27. [25-mobile-plans-empty.png](./25-mobile-plans-empty.png) - `/plans`，390×844

计划草稿 API 流程实际生成了 7 天、31 项、校验 OK 的草稿；由于 ego-browser 的截图通道在长计划页面超时，且内置浏览器不支持页面 `prompt()`，没有把这次草稿状态伪装成截图，详情见审查结论。
