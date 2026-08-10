# 29 Java 21 构建基线核验

- Type: task
- Status: open
- Triage: ready-for-agent
- Blocked by: —

## Question

核验并固定项目的 Java 21 构建基线：当前 `pom.xml` 声明 source/target 为 21，但本次 Maven 编译日志使用了 `release 17`，生成 class 的 major version 也是 61。需要确认是否由 Spring Boot 父 POM 的 `maven.compiler.release` 覆盖，并统一 POM、CI、运行时、文档和构建产物的版本约束。

完成后记录可复现的验证命令和结果；如果项目有意兼容 Java 17，则必须反向修正文档和交付环境，而不是保留两个相互矛盾的基线。

## Answer

2026-08-10 已完成问题定位，但修正只存在于未提交的工作区代码草稿，尚未接纳：

- 运行时为 Eclipse Temurin `21.0.12`，Maven 为 `3.9.16`。
- Spring Boot 3.3.13 父 POM 默认声明 `java.version=17` 和 `maven.compiler.release=${java.version}`；项目原先只声明 `maven.compiler.source/target=21`，因此有效 release 仍为 17。
- 工作区草稿在 `pom.xml` 增加了 `<maven.compiler.release>21</maven.compiler.release>`，用于覆盖父 POM 默认值；本轮文档提交不包含该代码改动。
- 之前曾通过触发源码重编译观察到 release 21，并用 `javap` 看到 class major version 65；但当前仍需在接纳草稿后执行标准 Maven clean build，不能把临时产物当作最终提交证据。

### 构建基线

```text
Java runtime: 21
Maven: 3.9.16+
Compiler release: 21
Spring Boot: 3.3.13
验证命令: mvn -DskipTests compile
```

### 实施约束

1. 后续执行本票时先审查并接纳或调整 POM 草稿；CI、Docker 镜像、部署运行时和 README 必须统一使用 Java 21。
2. 不保留 Java 17/21 双基线；如果未来要兼容 Java 17，必须重新形成独立决策。
3. 完成标准是提交 POM 修正，并由 `mvn clean test` 或等价 Java 21 clean build 和 `javap` 共同给出可复现证据；完成前本票保持 open。
