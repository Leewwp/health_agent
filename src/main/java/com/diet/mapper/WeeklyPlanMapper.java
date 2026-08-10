package com.diet.mapper;

import com.diet.model.WeeklyPlanItemRow;
import com.diet.model.WeeklyPlanRow;
import com.diet.model.WeeklyPlanVersionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** weekly_plan / weekly_plan_version / weekly_plan_item 表（34 号票周计划）。 */
@Mapper
public interface WeeklyPlanMapper {

    /** 按 id + 归属查询计划。 */
    WeeklyPlanRow findPlanById(@Param("id") Long id, @Param("userId") Long userId);

    /** 按 id + 归属查询计划并锁定行（激活路径使用，串行化并发激活）。 */
    WeeklyPlanRow findPlanByIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    /** 某用户的 ACTIVE 计划（同一用户最多一份）。 */
    WeeklyPlanRow findActiveByUser(@Param("userId") Long userId);

    /** 某用户的 ACTIVE 计划并锁定行（激活路径归档前使用）。 */
    WeeklyPlanRow findActiveByUserForUpdate(@Param("userId") Long userId);

    /** 按用户列出全部计划（ACTIVE 优先，再 DRAFT，最后 ARCHIVED，按更新时间倒序）。 */
    List<WeeklyPlanRow> listPlans(@Param("userId") Long userId);

    int insertPlan(WeeklyPlanRow row);

    int updatePlan(WeeklyPlanRow row);

    /** 激活专用更新：status='DRAFT' 条件保证原子性，影响 0 行说明状态已变化。 */
    int activatePlan(WeeklyPlanRow row);

    int insertVersion(WeeklyPlanVersionRow row);

    List<WeeklyPlanItemRow> findItems(@Param("planId") Long planId, @Param("versionNo") Long versionNo);

    WeeklyPlanItemRow findItemById(@Param("itemId") Long itemId);

    int insertItem(WeeklyPlanItemRow row);

    /** PATCH 只允许修改日期、时间、备注（规格 6.3）。 */
    int updateItemSchedule(WeeklyPlanItemRow row);
}
