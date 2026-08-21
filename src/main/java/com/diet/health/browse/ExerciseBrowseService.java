package com.diet.health.browse;

import com.diet.exception.DietException;
import com.diet.health.model.ExerciseBrowseItem;
import com.diet.health.model.PagedResponse;
import com.diet.health.reader.exercise.ReviewedExercise;
import com.diet.health.reader.exercise.ReviewedExerciseReader;
import com.diet.health.collection.FavoriteResourceService;
import com.diet.health.resource.HealthResourceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 动作浏览服务（规格 6.2）。
 * 资料库展示完整本地动作目录；只有 plan_ready 动作可被自动周计划消费。
 * 媒体状态与署名原样透出（无图状态 + Gym visual 署名）。
 * 用户槽位字段（部位/器材/难度/肌群）已由读取模块归一为健身槽位中文词汇。
 * 数据读取经 {@link ReviewedExerciseReader}（方案 B），本层不接触 Mapper 行对象。
 */
@Service
public class ExerciseBrowseService {

    /** size 上限（规格 6.2）。 */
    static final int MAX_PAGE_SIZE = 50;

    private final ReviewedExerciseReader reviewedExerciseReader;
    private final HealthResourceProvider resourceProvider;
    private final FavoriteResourceService favoriteService;

    public ExerciseBrowseService(ReviewedExerciseReader reviewedExerciseReader,
                                 @Qualifier("healthResourceProvider") HealthResourceProvider resourceProvider) {
        this(reviewedExerciseReader, resourceProvider, null);
    }

    @Autowired
    public ExerciseBrowseService(ReviewedExerciseReader reviewedExerciseReader,
                                 @Qualifier("healthResourceProvider") HealthResourceProvider resourceProvider,
                                 FavoriteResourceService favoriteService) {
        this.reviewedExerciseReader = reviewedExerciseReader;
        this.resourceProvider = resourceProvider;
        this.favoriteService = favoriteService;
    }

    public PagedResponse<ExerciseBrowseItem> browse(int page, int size, Long userId, boolean favoriteOnly) {
        return browse(page, size, userId, favoriteOnly, null, Map.of());
    }

    public PagedResponse<ExerciseBrowseItem> browse(int page, int size, Long userId, boolean favoriteOnly,
                                                    String query, Map<String, String> filters) {
        requireReviewedMode();
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
        boolean useQuery = query != null && !query.isBlank() || filters != null && !filters.isEmpty();
        boolean useFavoriteFilter = userId != null || favoriteOnly;
        List<ReviewedExercise> exercises = useQuery
                ? reviewedExerciseReader.browse((int) offset, size, userId, favoriteOnly, query, filters)
                : useFavoriteFilter
                    ? reviewedExerciseReader.browse((int) offset, size, userId, favoriteOnly)
                    : reviewedExerciseReader.browse((int) offset, size);
        int total = useQuery
                ? reviewedExerciseReader.count(userId, favoriteOnly, query, filters)
                : useFavoriteFilter ? reviewedExerciseReader.count(userId, favoriteOnly) : reviewedExerciseReader.count();
        java.util.Set<String> favoriteIds = favoriteService == null ? java.util.Set.of()
                : favoriteService.ids(userId, "EXERCISE");
        List<ExerciseBrowseItem> items = exercises.stream()
                .map(exercise -> toItem(exercise, favoriteIds.contains(String.valueOf(exercise.id())))).toList();
        return PagedResponse.of(items, page, size, total);
    }

    public PagedResponse<ExerciseBrowseItem> browse(int page, int size) {
        return browse(page, size, null, false);
    }

    public ExerciseBrowseItem detail(long id) {
        return detail(id, null);
    }

    public ExerciseBrowseItem detail(long id, Long userId) {
        requireReviewedMode();
        return reviewedExerciseReader.findById(id)
                .map(exercise -> toItem(exercise, favoriteService != null && userId != null
                        && favoriteService.contains(userId, "EXERCISE", String.valueOf(exercise.id()))))
                .orElseThrow(() -> new com.diet.exception.HealthApiException(
                        com.diet.exception.HealthApiException.CODE_NOT_FOUND, "动作不存在或未通过审核"));
    }

    private void requireReviewedMode() {
        ReviewedBrowseMode.require(resourceProvider, "动作浏览");
    }

    /** 读取模型 → 浏览条目（浏览用例层透传）。 */
    private static ExerciseBrowseItem toItem(ReviewedExercise exercise) {
        return toItem(exercise, false);
    }

    private static ExerciseBrowseItem toItem(ReviewedExercise exercise, boolean favorite) {
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
                licensedMediaUrl(exercise.thumbnailUrl(), exercise.mediaState()),
                licensedMediaUrl(exercise.mediaUrl(), exercise.mediaState()),
                exercise.mediaState(),
                exercise.mediaCredit(),
                exercise.sourceName(),
                exercise.sourceId(),
                exercise.sourceVersion(),
                exercise.sourceHash(),
                exercise.sourceCategory(),
                exercise.sourceBodyPart(),
                exercise.sourceEquipment(),
                exercise.sourceTarget(),
                exercise.sourceMuscleGroup(),
                exercise.sourceSecondaryMuscles(),
                exercise.instructionsZhStatus(),
                exercise.qualificationVersion(),
                exercise.qualificationVisible(),
                exercise.qualificationRecommendable(),
                exercise.qualificationPlanReady(),
                exercise.qualificationReportJson(),
                favorite
        );
    }

    private static String licensedMediaUrl(String mediaUrl, String mediaState) {
        return "LICENSED".equals(mediaState) ? mediaUrl : null;
    }
}
