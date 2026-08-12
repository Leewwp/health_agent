package com.diet.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 安全配置启动期校验（#61）。
 * 占位 token、空白条目、非法 Origin URI 在启动阶段拒绝；prod 启用 token 但
 * 未配置 allowlist 时启动失败并指出缺失变量；dev 可通过显式配置保留本地流程。
 */
class McpConfigValidatorTest {

    @Test
    void token未配置时跳过校验() {
        assertDoesNotThrow(() -> McpConfigValidator.validateForStartup("", "not-a-valid-origin"));
        assertDoesNotThrow(() -> McpConfigValidator.validateForStartup(null, null));
        assertDoesNotThrow(() -> McpConfigValidator.requireProdAllowlist("", ""));
    }

    @Test
    void 占位token在启动阶段拒绝() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> McpConfigValidator.validateForStartup("请填入自己的apiKey", "https://demo.health.example"));
        assertTrue(error.getMessage().contains("MCP_API_TOKEN"), "错误必须指出问题变量");
        assertThrows(IllegalStateException.class,
                () -> McpConfigValidator.validateForStartup("change-me-token", "https://demo.health.example"));
    }

    @Test
    void 空白allowlist条目拒绝() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> McpConfigValidator.validateForStartup("real-token", "https://demo.health.example,  ,https://a.example"));
        assertTrue(error.getMessage().contains("空白条目"), error.getMessage());
    }

    @Test
    void 非法OriginURI在启动阶段拒绝() {
        for (String illegal : new String[]{
                "demo.health.example",
                "https://",
                "https://demo.health.example/extra",
                "ftp://demo.health.example",
                "https://demo.health.example?x=1",
                "not a uri"
        }) {
            assertThrows(IllegalStateException.class,
                    () -> McpConfigValidator.validateForStartup("real-token", illegal),
                    "非法 Origin 必须拒绝: " + illegal);
        }
    }

    @Test
    void 合法allowlist通过校验() {
        assertDoesNotThrow(() -> McpConfigValidator.validateForStartup("real-token",
                "https://demo.health.example, http://localhost:5173, https://app.example:8443"));
    }

    @Test
    void prod启用token但未配置allowlist时启动失败() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> McpConfigValidator.requireProdAllowlist("real-token", ""));
        assertTrue(error.getMessage().contains("MCP_ALLOWED_ORIGINS"), "错误必须指出缺失变量: " + error.getMessage());
        assertThrows(IllegalStateException.class,
                () -> McpConfigValidator.requireProdAllowlist("real-token", "  , "));
    }

    @Test
    void prod配置allowlist或未启用token时通过() {
        assertDoesNotThrow(() -> McpConfigValidator.requireProdAllowlist("real-token", "https://demo.health.example"));
        assertDoesNotThrow(() -> McpConfigValidator.requireProdAllowlist("", ""));
    }
}
