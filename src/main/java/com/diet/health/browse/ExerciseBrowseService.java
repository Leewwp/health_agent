package com.diet.health.browse;

import com.diet.exception.DietException;
import com.diet.health.model.ExerciseBrowseItem;
import com.diet.health.model.PagedResponse;
import com.diet.health.reader.exercise.ReviewedExercise;
import com.diet.health.reader.exercise.ReviewedExerciseReader;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 动作浏览服务（规格 6.2）。
 * 只暴露审核通过（review_status=APPROVED）的动作；分页参数：page≥1、1≤size≤50。
 * 媒体状态与署名原样透出（无图状态 + Gym visual 署名）。
 * 用户槽位字段（部位/器材/难度/肌群）已由读取模块归一为健身槽位中文词汇。
 * 数据读取经 {@link ReviewedExerciseReader}（方案 B），本层不接触 Mapper 行对象。
 */
@Service
public class ExerciseBrowseService {

    /** size 上限（规格 6.2）。 */
    static final int MAX_PAGE_SIZE = 50;

    private final ReviewedExerciseReader reviewedExerciseReader;

    public ExerciseBrowseService(ReviewedExerciseReader reviewedExerciseReader) {
        this.reviewedExerciseReader = reviewedExerciseReader;
    }

    public PagedResponse<ExerciseBrowseItem> browse(int page, int size) {
        if (page < 1) {
            throw new DietException("page 必须不小于 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new DietException("size 必须在 1 到 " + MAX_PAGE_SIZE + " 之间");
        }
        // 43 号票：long 计算 offset 防 int 溢出，超出数据库安全范围统一 400
        long offset = (long) (page - 1) * size;
        if (offset > Integer.MAX_VALUE) {
            throw new DietException("page 超出安全范围");
        }
        List<ReviewedExercise> exercises = reviewedExerciseReader.browse((int) offset, size);
        int total = reviewedExerciseReader.count();
        List<ExerciseBrowseItem> items = exercises.stream().map(ExerciseBrowseService::toItem).toList();
        return PagedResponse.of(items, page, size, total);
    }

    /** 读取模型 → 浏览条目（浏览用例层透传）。 */
    private static ExerciseBrowseItem toItem(ReviewedExercise exercise) {
        return new ExerciseBrowseItem(
                exercise.id(),
                exercise.name(),
                exercise.nameEn(),
                exercise.aliases(),
                exercise.category(),
                exercise.bodyPart(),
                exercise.targetMuscles(),
                exercise.secondaryMuscles(),
                exercise.equipment(),
                exercise.difficulty(),
                exercise.movementPattern(),
                exercise.riskTags(),
                exercise.alternativeGroup(),
                exercise.reviewStatus(),
                exercise.planReady(),
                exercise.instructionsZh(),
                exercise.steps(),
                exercise.mediaState(),
                exercise.mediaCredit(),
                exercise.sourceName(),
                exercise.sourceId(),
                exercise.sourceVersion()
        );
    }
}
