package com.diet.health.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 本地面试展示媒体种子。
 * <p>
 * 脚本生成完整动作资料库与已授权餐食图片映射；仅 dev 默认启用，
 * 自动周计划仍由既有 {@code plan_ready=1} 审核动作边界控制。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class LocalMediaCatalogSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalMediaCatalogSeeder.class);
    private static final String SEED_RESOURCE = "db/seed/local_media_catalog.sql";

    private final JdbcTemplate jdbcTemplate;
    private final CatalogQualificationService catalogQualificationService;
    private final boolean enabled;

    public LocalMediaCatalogSeeder(JdbcTemplate jdbcTemplate,
                                   CatalogQualificationService catalogQualificationService,
                                   @Value("${diet.seed.local-media-catalog:false}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.catalogQualificationService = catalogQualificationService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!enabled) {
            return;
        }
        ClassPathResource resource = new ClassPathResource(SEED_RESOURCE);
        if (!resource.exists()) {
            log.warn("本地媒体种子 {} 不存在；动作库将保持审核集，无外部媒体回退", SEED_RESOURCE);
            return;
        }
        String sql;
        try (InputStream stream = resource.getInputStream()) {
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        int statements = 0;
        int rows = 0;
        for (String statement : sql.split(";\\s*\\n")) {
            String trimmed = stripComments(statement).trim();
            if (trimmed.isBlank()) {
                continue;
            }
            rows += jdbcTemplate.update(trimmed);
            statements++;
        }
        log.info("本地展示媒体导入完成：{} 条语句，影响 {} 行", statements, rows);
        CatalogQualificationService.QualificationResult qualification = catalogQualificationService.qualify();
        log.info("完整目录资格补全：enabled={}, 动作 {} 条，公共餐食 {} 条",
                qualification.enabled(), qualification.qualifiedExercises(), qualification.qualifiedMeals());
    }

    private String stripComments(String statement) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : statement.split("\\n")) {
            if (!line.trim().startsWith("--")) {
                cleaned.append(line).append('\n');
            }
        }
        return cleaned.toString();
    }
}
