# 11 Vercel 部署适配调研

- Type: research
- Status: resolved
- Blocked by: —

## Question

截至 2026-08-09，Vercel 是否适合承载本项目的完整技术栈（Java 21 / Spring Boot / MyBatis / MySQL / Redis / DashScope 长耗时调用），还是只能托管独立静态前端？

必须依据一手官方文档核实：Java runtime、常驻进程与 Docker Compose、函数时长与文件系统、外部 MySQL/Redis 连接、流式响应与后台任务、部署区域及中国大陆访问。结论需分别覆盖整栈部署、仅前端部署、改造成 serverless function 三种方案，并给出推荐拓扑。

产出：`.scratch/health-agent/research/vercel-deployment-fit.md`。

## Answer

调研产出：`.scratch/health-agent/research/vercel-deployment-fit.md`。

结论：

- Vercel 适合部署无构建步骤的 vanilla 静态前端。
- Java 21 不是 Vercel 官方 Functions runtime；2026 年虽可通过 Beta OCI Container Images 运行 Spring Boot 镜像，但其本质仍是会缩容、受时长/网络/文件系统限制的 Function，不是常驻 VM，也不能把现有 Docker Compose 中的 Spring Boot、MySQL、Redis 整体搬入。
- 不为 Vercel 重写 Node/Python 后端，也不把 Beta Java 容器作为生产默认方案。
- 推荐拓扑是 Vercel 静态前端 + 外部单实例 Spring Boot + 外部/同区域 MySQL、Redis + DashScope。Vercel 无中国大陆节点，不保证大陆访问质量；前端是否最终采用 Vercel需在实际网络做静态页面验收。
- 把前端移到 Vercel几乎不释放服务器 CPU/内存；真正占资源的是 Java、MySQL 和 Redis。若目的是给另一个大项目腾资源，必须移动或托管后端/数据层，而不是只移动前端。
