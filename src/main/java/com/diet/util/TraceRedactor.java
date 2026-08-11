package com.diet.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trace 最小脱敏工具（M5 #53）。
 * <p>
 * 在 AgentTraceService 持久化边界统一调用：对 API key、Bearer token、授权头、Cookie、
 * 环境变量式凭证赋值与 JSON 敏感键值做正则脱敏，替换为 {@code [REDACTED]}。
 * 只做保守替换：匹配不到敏感模式的内容原样保留，不破坏 JSON 结构（值仍为字符串）。
 * 不是完整可观测性平台，也不保证对任意嵌套结构的完美覆盖。
 */
public final class TraceRedactor {

    private TraceRedactor() {
    }

    /** 脱敏占位符。 */
    public static final String REDACTED = "[REDACTED]";

    /** 授权/Cookie 头：整行脱敏（大小写不敏感、行首锚定）。 */
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?im)^\\s*(Authorization|Proxy-Authorization|X-Admin-Token|Cookie)\\s*:\\s*[^\\r\\n]+");

    /** 环境变量式凭证赋值：MCP_API_TOKEN=…、DASHSCOPE_API_KEY=…。 */
    private static final Pattern ENV_ASSIGN = Pattern.compile(
            "(?i)\\b([A-Z][A-Z0-9_]*(?:API[_A-Z]*KEY|TOKEN|SECRET|PASSWORD|COOKIE|CREDENTIAL))\\s*=\\s*[^\\s&]+");

    /** 常见 API key 前缀（DashScope/OpenAI 均为 sk- 开头）。 */
    private static final Pattern API_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b");

    /** Bearer token。 */
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");

    /** JSON 敏感键字符串值：{"apiKey":"…"}、{"accessToken":"…"}、{"mcp_token":"…"} 等（大小写不敏感，
     *  单词键 token/secret 等要求词边界，避免误伤 inputTokens 之类业务键）。 */
    private static final Pattern JSON_SENSITIVE_VALUE = Pattern.compile(
            "\"([^\"]*(?:api[-_]?key|access[-_]?token|refresh[-_]?token|mcp[-_]?token|session[-_]?secret"
                    + "|client[-_]?secret|\\btoken\\b|\\bsecret\\b|\\bpassword\\b|\\bauthorization\\b"
                    + "|\\bcookie\\b|\\bcredential\\b)[^\"]*)\"\\s*:\\s*\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE);

    /** 对文本统一脱敏；null 原样返回。 */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        result = AUTH_HEADER.matcher(result).replaceAll(Matcher.quoteReplacement(REDACTED));
        result = ENV_ASSIGN.matcher(result).replaceAll(m -> m.group(1) + "=" + REDACTED);
        result = JSON_SENSITIVE_VALUE.matcher(result).replaceAll(m -> "\"" + m.group(1) + "\":\"" + REDACTED + "\"");
        // 先脱敏 Bearer（会吞掉整段 token），再脱敏残留的独立 sk- 前缀 key，避免重复占位符
        result = BEARER.matcher(result).replaceAll(Matcher.quoteReplacement("Bearer " + REDACTED));
        result = API_KEY.matcher(result).replaceAll(Matcher.quoteReplacement("sk-" + REDACTED));
        return result;
    }
}
