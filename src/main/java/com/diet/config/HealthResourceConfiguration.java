package com.diet.config;

import com.diet.health.resource.DbReviewedResourceProvider;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.SeedResourceProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * 审核资源 Provider 运行配置：按 diet.resource.mode 选择数据库审核子集或内存种子。
 * prod 强制 reviewed，fixture 模式在 prod 下启动失败（防止把演示数据当生产能力），
 * 与 {@link HealthAgentConfiguration} 的 diet.agent.mode 先例一致。
 */
@Configuration
public class HealthResourceConfiguration {

    @Bean("healthResourceProvider")
    @Primary
    public HealthResourceProvider healthResourceProvider(
            DbReviewedResourceProvider dbReviewedResourceProvider,
            SeedResourceProvider seedResourceProvider,
            Environment environment,
            @Value("${diet.resource.mode:reviewed}") String mode
    ) {
        boolean prodActive = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if ("fixture".equalsIgnoreCase(mode)) {
            if (prodActive) {
                throw new IllegalStateException("生产环境禁止 diet.resource.mode=fixture，必须使用 reviewed");
            }
            return seedResourceProvider;
        }
        return dbReviewedResourceProvider;
    }
}
