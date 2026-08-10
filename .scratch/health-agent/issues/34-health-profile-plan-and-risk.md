# 34 健康档案、周计划与风险校验

- Type: task
- Status: open
- Triage: ready-for-agent
- Depends on: 32, 33

## Scope

实现规格第 8、9 节：最小健康档案、确定性能量区间、周计划聚合与版本、三阶段风险校验和计划 API。

## Must do

- 建立健康档案及版本表，使用 Mifflin-St Jeor 和三档活动系数；
- 只计算每日能量区间，四舍五入到 50 kcal 并显示估算标记；
- 建立 `weekly_plan`、版本和项目表；
- 实现 DRAFT/ACTIVE/ARCHIVED、档案快照、ACTIVE 编辑复制 DRAFT 和历史版本；
- 实现周一至周日、本地时区、时间冲突、非连续训练部位和能量区间校验；
- 实现 `NORMAL/ADVISORY/BLOCK_PLAN` 和固定中文文案；
- 实现候选前、组合时、LLM 输出后三层 Guard，并复用 32 号的 Agent 契约和失败分类；
- 实现档案、计划创建/查看/激活/编辑接口；
- 未校验计划不得持久化或激活，Agent 只解释已校验结果。

## Done when

核心自动化测试覆盖公式、缺失性别、计划版本、硬错误/警告、风险拒绝和降级；接口可完成生成 DRAFT、移动项目、保存备注和激活。浏览器验收属于 35 号。
