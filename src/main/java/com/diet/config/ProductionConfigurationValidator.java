package com.diet.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 启动时检查生产环境必须注入的最小配置。 */
@Configuration
@Profile("prod")
public class ProductionConfigurationValidator {

    @Value("${agentscope.dashscope.api-key}")
    private String dashScopeApiKey;

    @Value("${diet.security.session-secret}")
    private String sessionSecret;

    @Value("${diet.security.admin-token}")
    private String adminToken;

    @PostConstruct
    public void validate() {
        requireConfigured("DASHSCOPE_API_KEY", dashScopeApiKey);
        requireConfigured("DIET_SESSION_SECRET", sessionSecret);
        requireConfigured("ADMIN_TOKEN", adminToken);
    }

    private void requireConfigured(String name, String value) {
        if (value == null || value.isBlank() || value.contains("请填入自己的apiKey") || value.contains("change-me")) {
            throw new IllegalStateException("生产配置缺少 " + name);
        }
    }
}
