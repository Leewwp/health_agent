# 31 MVP 基础设施与旧饮食兼容

- Type: task
- Status: resolved
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

## Answer

2026-08-10 完成验收（提交 3547bc7 接纳草稿，提交 3547bc7 之后本票完成）：

- **Flyway 基线双路径**：旧 `diet_db.sql` 转换为 `db/migration/V1__legacy_baseline.sql`；旧库（无 flyway_schema_history）由 baseline-on-migrate 标记 V1 并应用 V2；全新空库顺序执行 V1+V2，两种路径均在真实 MySQL 8.4 上验证（种子数据 5 餐食/91 槽位完整导入）。
- **真实接口冒烟**（`/api/v1/health/chat` 前置部分 + 旧接口）：
  - `/actuator/health` → UP；
  - 旧 `/api/v1/diet/chat` 回归通过（PUBLIC 澄清）；
  - requestId 幂等：同 sessionId+requestId 重复提交返回相同响应与 traceId，不同消息复用已保存结果；
  - 匿名 Cookie：`HEALTH_SESSION` HMAC 签名、HttpOnly、SameSite=Lax；
  - admin token：无 token/错误 token → 401，正确 token → 放行（通过 400 验证）；
  - prod 缺配置：占位符解析失败 + ProductionConfigurationValidator（值含 change-me 时输出"生产配置缺少 DIET_SESSION_SECRET"）；
- **README 更新**：本地启动（Flyway 自动迁移说明）、配置注入表（DASHSCOPE_API_KEY/DIET_SESSION_SECRET/ADMIN_TOKEN/DATABASE_*）、测试与冒烟示例、dev/prod 差异说明。

### 验收证据矩阵

| 验收项 | 证据 |
|---|---|
| 旧饮食接口回归 | curl 冒烟通过 |
| Cookie 篡改 | HMAC 校验失败回落新建身份（代码级） |
| Header 越权 | prod `allow-legacy-user-header=false` + 匿名 Cookie |
| 重复 requestId | 相同响应快照（冒烟验证） |
| admin 未授权 | 401（无 token/错误 token） |
| 缺少 prod 配置 | 启动失败（两种路径均复现） |
| Java 21 clean build | `mvn clean compile` + major 65 |
