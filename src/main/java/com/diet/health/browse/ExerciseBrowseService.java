package com.diet.health.browse;

import com.diet.exception.DietException;
import com.diet.health.model.ExerciseBrowseItem;
import com.diet.health.model.PagedResponse;
import com.diet.mapper.ExerciseMapper;
import com.diet.model.ExerciseItemRow;
import com.diet.util.JsonService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 动作浏览服务（规格 6.2）。
 * 只暴露审核通过（review_status=APPROVED）的动作；分页参数：page≥1、1≤size≤50。
 * 媒体状态与署名原样透出（无图状态 + Gym visual 署名）。
 */
@Service
public class ExerciseBrowseService {

    /** size 上限（规格 6.2）。 */
    static final int MAX_PAGE_SIZE = 50;

    private final ExerciseMapper exerciseMapper;
    private final JsonService jsonService;

    public ExerciseBrowseService(ExerciseMapper exerciseMapper, JsonService jsonService) {
        this.exerciseMapper = exerciseMapper;
        this.jsonService = jsonService;
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
        List<ExerciseItemRow> rows = exerciseMapper.browse((int) offset, size);
        int total = exerciseMapper.count();
        List<ExerciseBrowseItem> items = rows.stream().map(this::toItem).toList();
        return PagedResponse.of(items, page, size, total);
    }

    private ExerciseBrowseItem toItem(ExerciseItemRow row) {
        return new ExerciseBrowseItem(
                row.getId(),
                row.getName(),
                row.getNameEn(),
                jsonService.fromJsonArray(row.getAliases()),
                row.getCategory(),
                row.getBodyPart(),
                jsonService.fromJsonArray(row.getTargetMuscles()),
                jsonService.fromJsonArray(row.getSecondaryMuscles()),
                row.getEquipment(),
                row.getDifficulty(),
                row.getMovementPattern(),
                jsonService.fromJsonArray(row.getRiskTags()),
                row.getAlternativeGroup(),
                row.getReviewStatus(),
                Boolean.TRUE.equals(row.getPlanReady()),
                row.getInstructionsZh(),
                jsonService.fromJsonArray(row.getStepsJson()),
                row.getMediaState(),
                row.getMediaCredit(),
                row.getSourceName(),
                row.getSourceId(),
                row.getSourceVersion()
        );
    }
}
