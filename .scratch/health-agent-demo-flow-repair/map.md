# 面试演示主流程修复

Status: resolved（2026-08-30 实施完成：方案 A 全八槽位召回 + 稳定排序；Hybrid 三餐兼容；
同域换主题 RecommendationTopicPolicy；候选稀缺进入 generationNotes.candidateScarcity；
标签统一器械/训练日。全量 870 绿、MySQL 门控全绿（12 个 integration 类）、前端 node 42 绿、
真实浏览器 T1–T4 验收通过，证据见 `.local-run/acceptance/review-2026-08-30-repair/`）

## Scope

修复真实验收暴露的健康链餐食召回缺陷、训练候选不足说明不可见、同域换主题槽位继承风险，并完成真实浏览器与 MySQL 回归验收。完整决策与验收合同见 [`spec.md`](spec.md)。

## Decision Summary

- P0：审核餐食结构化召回不能静默丢弃显式八槽位；排序必须确定性；保持 Reader 边界和 Hybrid 硬约束。
- P0 回退护栏：不得修改 `meal-facet-hardening` 的 canonical 词表、seed、ETL 稳定键、facet provenance、V24/fresh-schema 收敛、browse/count、foodType 未支持通道或旧 diet 兼容；详见规格中的回退风险清单。
- P1：同域换主题提供显式替换/reset 语义；不破坏活动上下文继承和跨域暂停/恢复。
- P1：训练候选稀缺说明必须在计划 API 与计划页用户可见。
- P2：能量回退提示、训练 chip 术语统一；保留现有 WARNING 启用保护。

## Status Notes

原始运行态验收见 `docs/review-2026-08-30-full-flow.md`。其中关于“所有偏好恒为 0”和“启动刷新全部 updated_at”的绝对表述已被实现核验收窄，详见规格的 Further Notes。
