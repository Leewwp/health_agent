package com.diet.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TraceRedactor 脱敏测试（M5 #53）：API key、Bearer token、授权/Cookie 头、
 * 环境变量式凭证赋值、JSON 敏感键值；非敏感文本原样保留。
 */
class TraceRedactorTest {

    @Test
    void 空值与null原样返回() {
        assertNull(TraceRedactor.redact(null));
        assertEquals("", TraceRedactor.redact(""));
    }

    @Test
    void dashscope风格APIKey被脱敏() {
        String redacted = TraceRedactor.redact("使用 sk-abcdef1234567890abcdef1234567890 调用");
        assertTrue(!redacted.contains("sk-abcdef1234567890abcdef1234567890"), redacted);
        assertTrue(redacted.contains("sk-[REDACTED]"), redacted);
    }

    @Test
    void bearerToken被脱敏() {
        String redacted = TraceRedactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc.def");
        assertTrue(!redacted.contains("eyJhbGciOiJIUzI1NiJ9"), redacted);
        assertTrue(redacted.contains("[REDACTED]"), redacted);
    }

    @Test
    void 独立bearer短语被脱敏() {
        String redacted = TraceRedactor.redact("调用凭证为 Bearer eyJhbGciOiJIUzI1NiJ9.abc.def，请使用");
        assertTrue(!redacted.contains("eyJhbGciOiJIUzI1NiJ9"), redacted);
        assertTrue(redacted.contains("Bearer [REDACTED]"), redacted);
    }

    @Test
    void 授权头整行脱敏() {
        String redacted = TraceRedactor.redact("GET /api HTTP/1.1\nAuthorization: Basic dXNlcjpwYXNz\nCookie: session=abc123");
        assertTrue(!redacted.contains("Basic dXNlcjpwYXNz"), redacted);
        assertTrue(!redacted.contains("session=abc123"), redacted);
        assertTrue(redacted.contains("[REDACTED]"), redacted);
    }

    @Test
    void 环境变量式凭证赋值被脱敏() {
        String redacted = TraceRedactor.redact("export MCP_API_TOKEN=my-secret-token-123 && export DASHSCOPE_API_KEY=sk-live-9f8e7d6c5b4a");
        assertTrue(!redacted.contains("my-secret-token-123"), redacted);
        assertTrue(!redacted.contains("sk-live-9f8e7d6c5b4a"), redacted);
        assertTrue(redacted.contains("MCP_API_TOKEN=[REDACTED]"), redacted);
        assertTrue(redacted.contains("DASHSCOPE_API_KEY=[REDACTED]"), redacted);
    }

    @Test
    void json敏感键值被脱敏() {
        String json = "{\"apiKey\":\"sk-1234567890abcdef\",\"meal\":\"清蒸鲈鱼\",\"accessToken\":\"tok_xyz\"}";
        String redacted = TraceRedactor.redact(json);
        assertTrue(!redacted.contains("sk-1234567890abcdef"), redacted);
        assertTrue(!redacted.contains("tok_xyz"), redacted);
        assertTrue(redacted.contains("\"apiKey\":\"[REDACTED]\""), redacted);
        assertTrue(redacted.contains("\"accessToken\":\"[REDACTED]\""), redacted);
        assertTrue(redacted.contains("清蒸鲈鱼"), redacted);
    }

    @Test
    void 非敏感文本原样保留() {
        String text = "推荐清蒸鲈鱼与杂粮饭，热量约 450 kcal，来源：健康食谱";
        assertEquals(text, TraceRedactor.redact(text));
    }

    @Test
    void 脱敏不破坏JSON结构() {
        String json = "{\"message\":\"Bearer abcdef 已过期\",\"count\":3}";
        String redacted = TraceRedactor.redact(json);
        assertTrue(redacted.startsWith("{\""), redacted);
        assertTrue(redacted.endsWith("}"), redacted);
        assertTrue(!redacted.contains("abcdef"), redacted);
    }
}
