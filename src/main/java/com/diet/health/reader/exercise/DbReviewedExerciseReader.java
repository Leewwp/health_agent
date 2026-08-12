package com.diet.health.reader.exercise;

import com.diet.mapper.ExerciseMapper;
import com.diet.model.ExerciseItemRow;
import com.diet.util.JsonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审核动作读取模块 DB adapter（#64，方案 B）。
 * <p>
 * 本类与 {@code DbReviewedResourceProvider} 是仅有的两个允许依赖 ExerciseMapper 的类。
 * 封装 APPROVED 过滤（Mapper SQL）、分页读取、稳定排序与面向浏览的不可变读取模型；
 * 用户槽位字段经 {@link ExerciseVocabulary} 归一为中文，未收录原始值过滤并记录
 * 可诊断信息，不原样透出。SQL 复用现有 Mapper 契约（不为抽象重写 SQL）。
 */
@Component
public class DbReviewedExerciseReader implements ReviewedExerciseReader {

    private static final Logger log = LoggerFactory.getLogger(DbReviewedExerciseReader.class);

    private final ExerciseMapper exerciseMapper;
    private final JsonService jsonService;

    public DbReviewedExerciseReader(ExerciseMapper exerciseMapper, JsonService jsonService) {
        this.exerciseMapper = exerciseMapper;
        this.jsonService = jsonService;
    }

    @Override
    public List<ReviewedExercise> browse(int offset, int size) {
        return exerciseMapper.browse(offset, size).stream()
                .map(this::toReviewedExercise)
                .toList();
    }

    @Override
    public int count() {
        return exerciseMapper.count();
    }

    /** 行 → 浏览读取模型：槽位字段归一为健身槽位中文词汇（与领域 Provider 同口径）。 */
    private ReviewedExercise toReviewedExercise(ExerciseItemRow row) {
        List<String> rawTarget = jsonService.fromJsonArray(row.getTargetMuscles());
        List<String> rawSecondary = jsonService.fromJsonArray(row.getSecondaryMuscles());
        logUnrepresented("动作 " + row.getId(), "靶肌", ExerciseVocabulary.unrepresentedParts(rawTarget));
        logUnrepresented("动作 " + row.getId(), "次肌", ExerciseVocabulary.unrepresentedParts(rawSecondary));
        logUnrepresented("动作 " + row.getId(), "部位", singleUnrepresented(row.getBodyPart()));
        logUnrepresented("动作 " + row.getId(), "器材", singleUnrepresented(row.getEquipment()));
        logUnrepresented("动作 " + row.getId(), "难度", singleUnrepresented(row.getDifficulty()));
        return new ReviewedExercise(
                row.getId(),
                row.getName(),
                row.getNameEn(),
                jsonService.fromJsonArray(row.getAliases()),
                ExerciseVocabulary.partZh(row.getCategory()),
                ExerciseVocabulary.partZh(row.getBodyPart()),
                ExerciseVocabulary.normalizeParts(rawTarget),
                ExerciseVocabulary.normalizeParts(rawSecondary),
                ExerciseVocabulary.equipmentZh(row.getEquipment()),
                ExerciseVocabulary.difficultyZh(row.getDifficulty()),
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

    /** 未收录单值包装为列表，便于统一降级记录。 */
    private List<String> singleUnrepresented(String raw) {
        return raw == null || raw.isBlank() ? List.of() : List.of(raw);
    }

    /** 未收录原始值明确降级：过滤并记录可诊断信息，不原样透出英文。 */
    private void logUnrepresented(String resource, String field, List<String> dropped) {
        if (!dropped.isEmpty()) {
            log.warn("{} 的{}含未收录英文原始值，已从用户标签集合过滤：{}", resource, field, dropped);
        }
    }
}
