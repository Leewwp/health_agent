# 个人收藏集合

Status: resolved
Type: task
Priority: P0
Blocked by: none

## Goal

建立独立于推荐反馈的用户资源收藏集合，供餐食页、动作页和计划资源弹窗统一筛选。

## Scope

- 按匿名用户和类型化资源身份唯一；
- 添加、取消、分页查询和类型筛选；
- 餐食/动作浏览页增加“仅看收藏”；
- 计划候选弹窗复用同一筛选；
- 旧 FAVORITE/UNFAVORITE 不迁移；历史反馈继续保留给评估审计。

## Acceptance

- 收藏在页面间一致；
- 取消收藏可恢复；
- 分页筛选不受前 50 条限制；
- 重复添加和重复取消幂等；
- 写入失败时前端乐观状态回滚。

## Dependencies and fallback

- 依赖匿名身份和审核资源 Reader；
- 收藏接口不可用时显示稳定错误，不影响资源浏览和推荐。
