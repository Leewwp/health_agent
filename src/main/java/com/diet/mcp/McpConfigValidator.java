package com.diet.mcp;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

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

    /** 合法 Origin：http(s) + 主机名/IP + 可选端口，不含路径、查询或尾斜杠。 */
    private static final Pattern ORIGIN_PATTERN = Pattern.compile("^https?://[a-zA-Z0-9.-]+(:\\d{1,5})?$");

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
        for (String entry : entries(allowedOriginsRaw)) {
            if (entry.isBlank()) {
                throw new IllegalStateException("MCP_ALLOWED_ORIGINS 包含空白条目，请清理配置");
            }
            if (!ORIGIN_PATTERN.matcher(entry).matches()) {
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
        boolean hasAnyEntry = entries(allowedOriginsRaw).stream().anyMatch(entry -> !entry.isBlank());
        if (!hasAnyEntry) {
            throw new IllegalStateException("生产配置缺少 MCP_ALLOWED_ORIGINS（启用 MCP 时不允许空 Origin allowlist）");
        }
    }

    private static boolean isPlaceholder(String token) {
        return PLACEHOLDER_MARKERS.stream().anyMatch(token::contains);
    }

    private static List<String> entries(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).toList();
    }
}
