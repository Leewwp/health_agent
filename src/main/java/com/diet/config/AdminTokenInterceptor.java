package com.diet.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 保护 Trace、评估和调试入口的最小 admin token 校验。 */
@Component
public class AdminTokenInterceptor implements HandlerInterceptor {

    @Value("${diet.security.admin-protected:false}")
    private boolean protectedMode;

    @Value("${diet.security.admin-token:}")
    private String adminToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!protectedMode) {
            return true;
        }
        String provided = request.getHeader("X-Admin-Token");
        boolean valid = provided != null
                && adminToken != null
                && !adminToken.isBlank()
                && MessageDigest.isEqual(
                adminToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
        if (!valid) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "需要 admin token");
            return false;
        }
        return true;
    }
}
