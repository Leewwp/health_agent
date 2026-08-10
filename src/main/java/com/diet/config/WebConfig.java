package com.diet.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册匿名身份和管理入口拦截器。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AnonymousIdentityInterceptor identityInterceptor;
    private final AdminTokenInterceptor adminTokenInterceptor;

    public WebConfig(AnonymousIdentityInterceptor identityInterceptor, AdminTokenInterceptor adminTokenInterceptor) {
        this.identityInterceptor = identityInterceptor;
        this.adminTokenInterceptor = adminTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(identityInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(adminTokenInterceptor)
                .addPathPatterns("/api/v1/diet/debug/**", "/api/v1/diet/evaluations/**");
    }
}
