# #94 全链回归、文档、浏览器证据和 #89 交接

Status: resolved / ready-for-human

## 验证结果

- `node --test frontend/tests/*.test.mjs`：20/20。
- `mvn test`：662 tests，621 通过，41 环境门控跳过。
- `mvn test -Ditest.mysql=true`：662 tests，658 通过，4 独立门控跳过，37 个真实 MySQL 场景执行。
- `mvn test -Ditest.mysql=true -Ditest.qdrant=true`：662 tests，661 通过，1 个 live-model 门控跳过；Qdrant 场景执行。
- `mvn -q -Dtest=LiveTrainingPlanSmokeTest -Ditest.live-model=true test`：1/1 通过。
- `docker compose config --quiet`、JS 语法检查、`git diff --check`：通过。

## 证据位置

- 发布矩阵：`docs/release-evidence.md`。
- 浏览器记录和截图：`docs/frontend-browser-acceptance.md`、`docs/evidence/issue-90/`。
- #89 只收到本地完成与云端部署交接说明，不在本票执行或关闭云端工作。
