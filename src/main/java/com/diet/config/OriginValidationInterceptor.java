package com.diet.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 写操作的同源 Origin 校验（规格 11：不引入完整 Spring Security CSRF）。
 * <p>
 * 规则：POST/PUT/PATCH/DELETE 请求若携带 Origin 头，其 host 必须与请求 Host 一致；
 * 未携带 Origin（如 curl/服务端调用）放行。非同源写请求返回 403。
 */
@Component
public class OriginValidationInterceptor implements HandlerInterceptor {

    private static final String ALLOWED_METHOD_PREFIXES = "POSTPUTPATCHDELETE";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if (method == null || !ALLOWED_METHOD_PREFIXES.contains(method)) {
            return true;
        }
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        if (!isSameOrigin(origin, request.getHeader("Host"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }

    /** 比较 Origin 的 host:port 与请求 Host（忽略协议差异）。 */
    private boolean isSameOrigin(String origin, String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String originHost;
        try {
            java.net.URI uri = java.net.URI.create(origin);
            int port = uri.getPort();
            originHost = port == -1 ? uri.getHost() : uri.getHost() + ":" + port;
        } catch (RuntimeException ignored) {
            return false;
        }
        return originHost != null && originHost.equals(hostHeader);
    }
}
