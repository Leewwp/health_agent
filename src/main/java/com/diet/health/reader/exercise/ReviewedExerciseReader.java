package com.diet.health.reader.exercise;

import java.util.List;

/**
 * 审核动作读取模块接口（#64，方案 B）。
 * <p>
 * 只有 {@link DbReviewedExerciseReader} 可直接依赖 ExerciseMapper；浏览用例层只消费
 * 本接口与 {@link ReviewedExercise} 视图。只暴露 APPROVED 动作，分页 id 升序稳定排序。
 */
public interface ReviewedExerciseReader {

    /** 审核动作分页（offset 从 0 起，id 升序稳定排序）。 */
    List<ReviewedExercise> browse(int offset, int size);

    /** 审核动作总数（与 browse 同口径）。 */
    int count();
}
