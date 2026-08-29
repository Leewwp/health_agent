package com.diet.health.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审核资源幂等导入器（33 号票 + 餐食标签加固规格）。
 * <p>
 * 启动时执行 {@code db/seed/reviewed_resources.sql}：餐食语句为
 * INSERT … ON DUPLICATE KEY UPDATE（同步 cuisine/food_type——ETL 是标签唯一事实源，
 * 旧库启动导入即与 fresh 库收敛）；动作/作息仍为 INSERT IGNORE，依赖唯一键幂等。
 * <p>
 * 餐食 facet 不变量执行者（加固规格）：种子导入后只把演示语料行上仍为空的 facet
 * 按规范稳定来源键补演示分类（{@link MealFacetVocabulary#stableKeyDemoLabel}，与 ETL/V24
 * 共用同一算法）；遇到非空但非法的 facet 必须失败并报警，不能默默覆盖；
 * 缺少复合来源身份的旧行不生成任何标签，绝不按自增 id 轮换伪造。
 */
@Component
public class ReviewedResourceSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReviewedResourceSeeder.class);

    private static final String SEED_RESOURCE = "db/seed/reviewed_resources.sql";

    private final JdbcTemplate jdbcTemplate;
    private final CatalogQualificationService catalogQualificationService;

    private final boolean enabled;

    public ReviewedResourceSeeder(JdbcTemplate jdbcTemplate,
                                  CatalogQualificationService catalogQualificationService,
                                  @Value("${diet.seed.reviewed-resources:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.catalogQualificationService = catalogQualificationService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!enabled) {
            log.info("diet.seed.reviewed-resources=false，跳过审核资源导入");
            return;
        }
        importReviewedResources(jdbcTemplate);
        // #85：seed 只承载审核动作事实，目标标签由可审计的 V13 规则按动作模式补齐，
        // 每次启动重算以保证 fresh DB 与 legacy DB 的标签结果一致。
        jdbcTemplate.update("UPDATE exercise_item SET training_goals = CASE "
                + "WHEN movement_pattern = '有氧' THEN JSON_ARRAY('减脂', '耐力', '保持健康') "
                + "ELSE JSON_ARRAY('增肌', '力量', '保持健康') END "
                + "WHERE review_status = 'APPROVED' AND plan_ready = 1");
        CatalogQualificationService.QualificationResult qualification = catalogQualificationService.qualify();
        log.info("完整目录资格补全：enabled={}, 动作 {} 条，公共餐食 {} 条",
                qualification.enabled(), qualification.qualifiedExercises(), qualification.qualifiedMeals());
    }

    /** 导入种子并执行餐食 facet 不变量；独立成方法供全新库/旧库集成测试复用同一起动路径。 */
    public void importReviewedResources(JdbcTemplate jdbcTemplate) throws IOException {
        // 先检查已有非空值，避免 ODKU 在导种时把运行期间写入的非法 facet 静默覆盖；
        // 空值由导种后的稳定键兜底补齐，身份缺失的空值按既有事实保留。
        validateExistingMealFacetValues(jdbcTemplate);
        executeSeedSql(jdbcTemplate);
        reconcileEmptyMealFacets(jdbcTemplate);
        validateMealFacetInvariant(jdbcTemplate);
    }

    private void validateExistingMealFacetValues(JdbcTemplate jdbcTemplate) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, source_name, source_id, cuisine, food_type FROM meal_item "
                        + "WHERE source_type = 'PUBLIC' AND owner_user_id IS NULL AND review_status = 'APPROVED'");
        List<String> violations = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String sourceName = row.get("source_name") == null ? "" : String.valueOf(row.get("source_name"));
            String sourceId = row.get("source_id") == null ? "" : String.valueOf(row.get("source_id"));
            boolean hasIdentity = !sourceName.isBlank() && !sourceId.isBlank();
            checkFacet(violations, id, sourceName, sourceId, hasIdentity, "cuisine", row.get("cuisine"), false);
            checkFacet(violations, id, sourceName, sourceId, hasIdentity, "foodType", row.get("food_type"), false);
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("已有审核餐食标签含非法非空值，拒绝导种覆盖（共 " + violations.size() + " 项）："
                    + String.join("；", violations.subList(0, Math.min(5, violations.size()))));
        }
    }

    /** 逐条执行种子语句（语句以 ";\n" 分隔；数据行单行生成、不含字面换行，保证分割安全）。 */
    private void executeSeedSql(JdbcTemplate jdbcTemplate) throws IOException {
        ClassPathResource resource = new ClassPathResource(SEED_RESOURCE);
        if (!resource.exists()) {
            log.warn("找不到审核资源种子文件 {}，跳过导入（未运行 ETL 或文件被移除）", SEED_RESOURCE);
            return;
        }
        String sql;
        try (InputStream in = resource.getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int statements = 0;
        int rows = 0;
        for (String statement : sql.split(";\\s*\n")) {
            String trimmed = stripComments(statement).trim();
            if (trimmed.isBlank()) {
                continue;
            }
            rows += jdbcTemplate.update(trimmed);
            statements++;
        }
        log.info("审核资源导入完成：{} 条语句，影响 {} 行（餐食 ON DUPLICATE KEY 同步 facet，其余 IGNORE 幂等）",
                statements, rows);
    }

    /**
     * 兜底补齐（只填空）：演示语料行（复合来源身份）上仍为空的 cuisine/food_type
     * 按稳定来源键补单个演示分类。不覆盖既有标签；缺少 source_id 的旧行不猜测。
     */
    private void reconcileEmptyMealFacets(JdbcTemplate jdbcTemplate) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, source_name, source_id FROM meal_item "
                        + "WHERE source_type = 'PUBLIC' AND owner_user_id IS NULL AND review_status = 'APPROVED' "
                        + "AND source_name IS NOT NULL AND source_name <> '' "
                        + "AND source_id IS NOT NULL AND source_id <> '' "
                        + "AND (cuisine IS NULL OR JSON_LENGTH(cuisine) = 0 "
                        + "OR food_type IS NULL OR JSON_LENGTH(food_type) = 0)");
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String sourceName = String.valueOf(row.get("source_name"));
            String sourceId = String.valueOf(row.get("source_id"));
            Integer cuisineLength = jdbcTemplate.queryForObject(
                    "SELECT JSON_LENGTH(cuisine) FROM meal_item WHERE id = ?", Integer.class, id);
            if (cuisineLength == null || cuisineLength == 0) {
                jdbcTemplate.update("UPDATE meal_item SET cuisine = JSON_ARRAY(?) WHERE id = ?",
                        MealFacetVocabulary.INSTANCE.stableKeyDemoLabel(
                                MealFacetVocabulary.CUISINE, sourceName, sourceId), id);
            }
            Integer foodTypeLength = jdbcTemplate.queryForObject(
                    "SELECT JSON_LENGTH(food_type) FROM meal_item WHERE id = ?", Integer.class, id);
            if (foodTypeLength == null || foodTypeLength == 0) {
                jdbcTemplate.update("UPDATE meal_item SET food_type = JSON_ARRAY(?) WHERE id = ?",
                        MealFacetVocabulary.INSTANCE.stableKeyDemoLabel(
                                MealFacetVocabulary.FOOD_TYPE, sourceName, sourceId), id);
            }
        }
        if (!rows.isEmpty()) {
            log.warn("餐食 facet 兜底补齐 {} 行（种子导入后仍为空的演示语料行，按稳定来源键补演示分类）", rows.size());
        }
    }

    /**
     * 行级 facet 不变量：非数组、词表外值或演示语料行上的空 facet 都必须失败报警；
     * 缺少复合来源身份的旧行允许空 facet（保留既有合法标签，不猜测），但同样禁止非法值。
     */
    private void validateMealFacetInvariant(JdbcTemplate jdbcTemplate) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, source_name, source_id, cuisine, food_type FROM meal_item "
                        + "WHERE source_type = 'PUBLIC' AND owner_user_id IS NULL AND review_status = 'APPROVED'");
        List<String> violations = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String sourceName = row.get("source_name") == null ? "" : String.valueOf(row.get("source_name"));
            String sourceId = row.get("source_id") == null ? "" : String.valueOf(row.get("source_id"));
            boolean hasIdentity = !sourceName.isBlank() && !sourceId.isBlank();
            checkFacet(violations, id, sourceName, sourceId, hasIdentity, "cuisine", row.get("cuisine"), true);
            checkFacet(violations, id, sourceName, sourceId, hasIdentity, "foodType", row.get("food_type"), true);
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("审核餐食标签不变量被破坏（共 " + violations.size() + " 项）："
                    + String.join("；", violations.subList(0, Math.min(5, violations.size()))));
        }
    }

    private void checkFacet(List<String> violations, long id, String sourceName, String sourceId,
                            boolean hasIdentity, String dimension, Object rawFacet, boolean requireNonEmpty) {
        String facet = rawFacet == null ? null : String.valueOf(rawFacet);
        List<String> values;
        try {
            values = facet == null ? List.of()
                    : new com.fasterxml.jackson.databind.ObjectMapper().readerForListOf(String.class)
                            .readValue(facet);
        } catch (IOException parseError) {
            violations.add("#" + id + " " + dimension + " 非法 JSON：" + facet);
            return;
        }
        List<String> illegal = values.stream()
                .filter(value -> !MealFacetVocabulary.INSTANCE.isLegal(dimension, value))
                .toList();
        if (!illegal.isEmpty()) {
            violations.add("#" + id + " " + dimension + " 含词表外值 " + illegal
                    + "（来源身份=" + sourceName + "/" + sourceId + "）");
            return;
        }
        if (values.isEmpty() && hasIdentity && requireNonEmpty) {
            violations.add("#" + id + " " + dimension + " 为空（演示语料行必须有最终标签）");
        }
    }

    /** 去掉语句内的 -- 注释行（种子文件头部注释会与第一条语句同段）。 */
    private String stripComments(String statement) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : statement.split("\n")) {
            if (!line.trim().startsWith("--")) {
                cleaned.append(line).append('\n');
            }
        }
        return cleaned.toString();
    }
}
