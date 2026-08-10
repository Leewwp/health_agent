# 31 MVP 基础设施与旧饮食兼容

- Type: task
- Status: open
- Triage: ready-for-agent
- Depends on: 29

## Scope

实现规格第 7、11 节的基础能力：Java 21 构建、Flyway 旧库基线、dev/prod 配置、HMAC 匿名 Cookie、admin token、统一健康 API 基础错误结构、requestId 幂等和健康检查。

## Must do

- 保留 `/api/v1/diet/**` 现有行为，补充兼容适配边界；
- 增加 `maven.compiler.release=21` 后保持 CI/运行时一致；
- 将旧 `diet_db.sql` 转为可验证的 Flyway baseline，后续迁移不执行 destructive dump；
- 实现服务端身份解析，生产忽略/拒绝 `X-User-Id`；
- 为新聊天请求增加 `requestId`，重复请求返回已保存结果；
- 增加新健康 API 的错误 DTO 和 `/actuator/health` 或等价接口；
- admin 入口使用 `ADMIN_TOKEN`，prod 缺少必要配置时启动失败；
- 补 README 的本地启动、数据库导入和配置注入说明。

## Must not do

真实账号、JWT、RBAC、复杂 CSRF、消息队列、第二实例和 Redis 分布式锁。

## Done when

旧饮食接口回归通过；Cookie 篡改、Header 越权、重复 requestId、admin 未授权和缺少 prod 配置均有固定测试或冒烟证据；Java 21 clean build 在 CI 可复现。

## Comments

2026-08-10 工作区存在一批未提交的首轮代码草稿：Java 21 release、Actuator/Flyway 配置、旧库 V2 requestId 迁移、HMAC 匿名 Cookie、开发 Header 兼容回退、生产配置校验、admin token 拦截、健康错误 DTO 和 Trace 响应快照幂等。此前只完成过源码级 `javac --release 21` 和静态解析，未完成标准 Maven、MySQL、Flyway、应用启动和真实接口验收；本轮仅提交文档，不接纳或继续开发这批草稿。后续执行本票时必须先审查草稿，再决定保留或调整。
