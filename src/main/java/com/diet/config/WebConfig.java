package com.diet.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册匿名身份、管理入口和写操作同源校验拦截器。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AnonymousIdentityInterceptor identityInterceptor;
    private final AdminTokenInterceptor adminTokenInterceptor;
    private final OriginValidationInterceptor originValidationInterceptor;

    public WebConfig(AnonymousIdentityInterceptor identityInterceptor,
                     AdminTokenInterceptor adminTokenInterceptor,
                     OriginValidationInterceptor originValidationInterceptor) {
        this.identityInterceptor = identityInterceptor;
        this.adminTokenInterceptor = adminTokenInterceptor;
        this.originValidationInterceptor = originValidationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(originValidationInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(identityInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(adminTokenInterceptor)
                .addPathPatterns("/api/v1/diet/debug/**", "/api/v1/diet/evaluations/**");
    }
}
