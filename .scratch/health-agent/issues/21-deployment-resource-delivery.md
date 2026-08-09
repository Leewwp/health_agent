# 21 部署资源预算与交付流水线

- Type: grilling
- Status: open
- Blocked by: 11, 12

## Question

在腾讯云 4C4G 服务器还要承载另一个更大项目的前提下，健康 agent 的前端、后端、MySQL、Redis 和反向代理如何分配或外置，部署流水线如何保证可回滚与不泄露密钥？

待决策：

1. 前端和后端是否分平台，MySQL/Redis 是共享实例、独立容器还是托管服务。
2. JVM、MySQL、Redis 的资源上限与健康检查；两个项目之间的网络和数据隔离。
3. GitHub Actions 只做编译测试和镜像构建，还是自动 SSH/镜像拉取部署。
4. Flyway 执行时机、备份、失败回滚和旧版本兼容窗口。
5. DashScope key、数据库凭证、session secret 的环境变量和轮换方式。

产出：部署拓扑、资源预算、CI/CD 阶段、回滚清单和运行手册边界。
