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

/**
 * 审核资源幂等导入器（33 号票）。
 * <p>
 * 启动时执行 {@code db/seed/reviewed_resources.sql}（ETL 生成的 INSERT IGNORE 语句）。
 * 幂等依赖唯一键 uk_meal_source / uk_exercise_source / uk_routine_fact_ref；
 * 重复执行不会产生重复行，允许 ETL 重跑后重新导入。
 * 语句以 ";\n" 分隔；数据行单行生成、不含字面换行，保证分割安全。
 */
@Component
public class ReviewedResourceSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReviewedResourceSeeder.class);

    private static final String SEED_RESOURCE = "db/seed/reviewed_resources.sql";

    private final JdbcTemplate jdbcTemplate;

    private final boolean enabled;

    public ReviewedResourceSeeder(JdbcTemplate jdbcTemplate,
                                  @Value("${diet.seed.reviewed-resources:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!enabled) {
            log.info("diet.seed.reviewed-resources=false，跳过审核资源导入");
            return;
        }
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
        log.info("审核资源导入完成：{} 条语句，影响 {} 行（INSERT IGNORE，幂等）", statements, rows);
        // #85：seed 只承载审核动作事实，目标标签由可审计的 V13 规则按动作模式补齐，
        // 每次启动重算以保证 fresh DB 与 legacy DB 的标签结果一致。
        jdbcTemplate.update("UPDATE exercise_item SET training_goals = CASE "
                + "WHEN movement_pattern = '有氧' THEN JSON_ARRAY('减脂', '耐力', '保持健康') "
                + "ELSE JSON_ARRAY('增肌', '力量', '保持健康') END "
                + "WHERE review_status = 'APPROVED' AND plan_ready = 1");
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
