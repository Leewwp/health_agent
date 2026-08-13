package com.diet.mcp;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * MCP 安全配置启动期校验（#61）。
 * <p>
 * 集中生产与通用启动校验，供 {@link McpServerConfiguration} 与
 * {@link com.diet.config.ProductionConfigurationValidator} 复用，纯静态可单测：
 * <ul>
 *   <li>占位 token、空白 allowlist 条目、非法 Origin URI 在启动阶段拒绝；</li>
 *   <li>prod 启用 MCP token 但未配置 allowlist 时启动失败，错误指出缺失变量。</li>
 * </ul>
 */
public final class McpConfigValidator {

    private static final List<String> PLACEHOLDER_MARKERS = List.of("请填入", "change-me", "changeme", "your-token");

    private McpConfigValidator() {
    }

    /** 通用启动校验：token 配置为非空时，拒绝占位 token 与非法/空白 allowlist 条目。 */
    public static void validateForStartup(String apiToken, String allowedOriginsRaw) {
        if (apiToken == null || apiToken.isBlank()) {
            return;
        }
        if (isPlaceholder(apiToken)) {
            throw new IllegalStateException("MCP_API_TOKEN 不能使用占位值，必须配置真实 token");
        }
        for (String entry : parseEntries(allowedOriginsRaw)) {
            if (entry.isBlank()) {
                throw new IllegalStateException("MCP_ALLOWED_ORIGINS 包含空白条目，请清理配置");
            }
            if (!isValidOrigin(entry)) {
                throw new IllegalStateException("MCP_ALLOWED_ORIGINS 包含非法 Origin（必须为 http(s)://主机[:端口]，不含路径）："
                        + entry);
            }
        }
    }

    /** 生产校验：prod 启用 MCP token 但未配置 Origin allowlist 时启动失败（空 allowlist 不再表达任意来源）。 */
    public static void requireProdAllowlist(String apiToken, String allowedOriginsRaw) {
        if (apiToken == null || apiToken.isBlank()) {
            return;
        }
        boolean hasAnyEntry = parseEntries(allowedOriginsRaw).stream().anyMatch(entry -> !entry.isBlank());
        if (!hasAnyEntry) {
            throw new IllegalStateException("生产配置缺少 MCP_ALLOWED_ORIGINS（启用 MCP 时不允许空 Origin allowlist）");
        }
    }

    private static boolean isPlaceholder(String token) {
        return PLACEHOLDER_MARKERS.stream().anyMatch(token::contains);
    }

    private static boolean isValidOrigin(String entry) {
        try {
            URI uri = URI.create(entry);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && uri.getPort() <= 65535;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    /** 统一的 allowlist 分词：保留空项，由启动校验拒绝、运行时解析过滤。 */
    static List<String> parseEntries(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        // 保留首/中/尾空项，避免 String.split 的默认行为吞掉尾随空白配置。
        return Arrays.stream(raw.split(",", -1)).map(String::trim).toList();
    }
}
