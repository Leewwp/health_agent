# 27 匿名身份与部署安全验收契约

- Type: grilling
- Status: resolved
- Blocked by: 11, 12, 21

## Question

把匿名演示身份和单实例部署从架构原则收敛为可验收的运行契约：Cookie 的签发、轮换、HttpOnly/Secure/SameSite、重置和 TTL 清理如何工作；静态前端与 API 的同源/跨域路径、CSRF、代理头和错误处理如何定义？

同时冻结用户入口与 Trace/评估/导入/调试入口的隔离方式、管理员认证边界、数据库和另一个项目的资源隔离、DashScope/数据库/session secret 的注入与轮换、备份/恢复/回滚和健康检查。产出应能直接变成部署手册、配置校验和安全冒烟场景。

## Answer

2026-08-10 已按“面试展示项目的最小实现”确认：

- 匿名身份使用服务端签发的 HMAC 签名 Cookie。Cookie 保存随机匿名用户 ID，设置 `HttpOnly`、`Path=/`；生产环境设置 `Secure=true`、`SameSite=Lax`。前端不再提交可编辑的 `X-User-Id`，服务端只信任已验证的 Cookie。
- 提供重置演示数据接口；首版不开发注册、登录、密码、JWT、独立身份表或自动 TTL 清理任务，只保留后续可配置清理的边界。
- 使用 Nginx 同源反向代理：`/` 托管静态前端，`/api/**` 转发 Spring Boot。不启用跨域 CORS；写操作校验 `Origin`，不引入完整 Spring Security CSRF 体系。
- Trace、评估、导入和调试接口不出现在用户导航中。生产 admin 请求使用环境变量注入的 `ADMIN_TOKEN`，通过 `X-Admin-Token` 校验；开发环境允许简化访问，不建设角色和权限管理系统。
- 使用 `dev/prod` 配置边界。开发环境可以使用本地数据库和 API key 占位；生产环境缺少 DashScope key、数据库密码或 session secret 时启动失败。密钥不进入源码、镜像或前端，不接入 Vault、云密钥管理服务或自动轮换平台，只记录手动轮换步骤。
- 部署保持单实例：Nginx、Spring Boot 和 MySQL 使用同一 Compose 拓扑，与另一个项目使用独立数据库/网络。Redis、消息队列和第二个后端实例不由本票新增。
- 提供 `/actuator/health` 或等价健康接口；Flyway 迁移前执行一次 `mysqldump`。发布失败时回退应用镜像，不建设复杂自动回滚、恢复演练或多区域容灾。
- CI 只负责编译、核心测试和镜像构建；真实部署、密钥填写和服务器操作写入运行手册，不在代码中自动执行。

### 最小安全验收

1. 修改或删除 Cookie 签名后，服务端拒绝读取其他身份数据。
2. 用户不能仅通过 `X-User-Id` 读取其他会话、反馈、档案或计划。
3. 生产配置缺少必要 key、数据库凭证或 session secret 时启动失败。
4. 普通用户不能访问 admin 接口；携带正确 admin token 可以访问开发/管理功能。
5. `/api` 与前端同源，写操作的非同源 Origin 被拒绝。
6. 健康检查、数据库备份和应用镜像回退命令在部署手册中可复现。

### 明确不做

- 真实账号体系、JWT、RBAC、密码管理、密钥管理平台、自动密钥轮换、复杂 CSRF 框架、多实例会话共享和灾备演练。
- 将“安全验收”扩展成真实医疗产品的合规或隐私认证。
