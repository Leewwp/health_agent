package com.diet.health.reader.exercise;

import java.util.List;
import java.util.Optional;

/**
 * 审核动作读取模块接口（#64，方案 B）。
 * <p>
 * 只有 {@link DbReviewedExerciseReader} 可直接依赖 ExerciseMapper；浏览用例层只消费
 * 本接口与 {@link ReviewedExercise} 视图。浏览目录可包含尚不具备计划资格的动作；
 * 推荐与周计划仍通过 Provider 的 APPROVED/plan_ready 边界读取。
 */
public interface ReviewedExerciseReader {

    /** 动作目录分页（offset 从 0 起，计划资格优先且顺序稳定）。 */
    List<ReviewedExercise> browse(int offset, int size);

    /** 动作目录总数（与 browse 同口径）。 */
    int count();

    /** 按稳定 ID 读取一条动作详情。 */
    Optional<ReviewedExercise> findById(Long id);
}
