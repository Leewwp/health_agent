package com.diet.mcp;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MCP 端点的最小身份与 Origin 边界（M5 #51）。
 * <p>
 * 只作用于 MCP Streamable HTTP 端点，独立于匿名 Cookie、可编辑用户头和管理 token：
 * <ul>
 *   <li>要求 {@code Authorization: Bearer <MCP_API_TOKEN>}，token 缺失或错误一律 401；</li>
 *   <li>Origin 头存在时必须命中 allowlist（{@code diet.mcp.allowed-origins}），否则 403；
 *       缺失 Origin（curl/MCP Inspector/服务端调用）按 {@code diet.mcp.allow-missing-origin} 放行；
 *       空 allowlist 对任何已提供 Origin 均拒绝（生产须显式配置）；</li>
 *   <li>校验通过后在请求属性写入 principal，供 transport contextExtractor 读取。失败时
 *       Filter 直接短路，不进入 MCP servlet，因此身份边界不受 MVC 拦截器影响。</li>
 * </ul>
 * 该实现不是 MCP OAuth 2.1 或最新规范全量合规声明；仅用于本地演示与面试讲解。
 * token 比较使用常量时间 {@link MessageDigest#isEqual}，避免时序侧信道。
 */
public class McpSecurityFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(McpSecurityFilter.class);

    /** 校验通过后写入请求属性的 principal 键，transport contextExtractor 读取后进入 MCP context。 */
    public static final String PRINCIPAL_ATTRIBUTE = "com.diet.mcp.principal";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final String expectedToken;
    private final Set<String> allowedOrigins;
    private final boolean allowMissingOrigin;

    public McpSecurityFilter(String expectedToken, Set<String> allowedOrigins, boolean allowMissingOrigin) {
        this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
        this.allowedOrigins = new LinkedHashSet<>();
        if (allowedOrigins != null) {
            allowedOrigins.forEach(origin -> {
                if (origin != null && !origin.isBlank()) {
                    this.allowedOrigins.add(origin.trim());
                }
            });
        }
        this.allowMissingOrigin = allowMissingOrigin;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        if (!hasValidToken(request)) {
            log.warn("MCP 请求被拒绝：缺少或无效的 Bearer token（{} {}）", request.getMethod(), request.getRequestURI());
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "缺少或无效的 MCP Bearer token");
            return;
        }
        if (!isAllowedOrigin(request)) {
            log.warn("MCP 请求被拒绝：Origin 不在允许列表（{}）", request.getHeader("Origin"));
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "Origin 不在允许列表");
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, "mcp-client");
        chain.doFilter(servletRequest, servletResponse);
    }

    private boolean hasValidToken(HttpServletRequest request) {
        if (expectedToken.isEmpty()) {
            // 未配置 MCP_API_TOKEN：fail-closed，拒绝所有请求
            return false;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return false;
        }
        String provided = authorization.substring(BEARER_PREFIX.length());
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isAllowedOrigin(HttpServletRequest request) {
        List<String> origins = new java.util.ArrayList<>();
        java.util.Enumeration<String> headerValues = request.getHeaders("Origin");
        while (headerValues.hasMoreElements()) {
            origins.add(headerValues.nextElement());
        }
        // 只有没有 Origin 头时才应用 allowMissingOrigin；空白头是异常输入，不能伪装成缺失。
        if (origins.isEmpty()) {
            return allowMissingOrigin;
        }
        // #61：空 allowlist 不得表达“任意来源”；非空 Origin 必须精确命中规范化 allowlist。
        // 不做请求侧 trim——真实代理会先剥离 OWS，客户端侧带空白属于异常输入，一律拒绝
        if (allowedOrigins.isEmpty()) {
            return false;
        }
        if (origins.size() > 1) {
            return false;
        }
        return allowedOrigins.contains(origins.get(0));
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(CONTENT_TYPE_JSON);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    /** 解析逗号分隔的 allowlist 配置。 */
    public static Set<String> parseAllowlist(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(McpConfigValidator.parseEntries(raw).stream()
                .filter(s -> !s.isEmpty())
                .toList());
    }

    /**
     * Filter 鉴权结果 → MCP transport context 的提取器。
     * <p>
     * 供 {@code HttpServletStreamableServerTransportProvider.Builder.contextExtractor} 使用：
     * 从请求属性读取 principal，无鉴权结果时返回 EMPTY。放在本类是为了让配置装配
     * 与测试复用同一份映射逻辑。
     */
    public static io.modelcontextprotocol.common.McpTransportContext extractTransportContext(
            jakarta.servlet.http.HttpServletRequest request) {
        Object principal = request.getAttribute(PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            return io.modelcontextprotocol.common.McpTransportContext.EMPTY;
        }
        return io.modelcontextprotocol.common.McpTransportContext.create(
                java.util.Map.of("principal", String.valueOf(principal)));
    }
}
