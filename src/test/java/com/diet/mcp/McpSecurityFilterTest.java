package com.diet.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;

/**
 * McpSecurityFilter 单元测试（M5 #51 + #61 fail-closed）：token 缺失/错误/合法、
 * Origin allowlist 精确命中、空 allowlist 拒绝任意非空 Origin、缺失 Origin 策略
 * 与鉴权结果写入请求属性。
 */
class McpSecurityFilterTest {

    private static final String TOKEN = "mcp-test-token";

    private MockHttpServletResponse doFilter(McpSecurityFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Content-Type", "application/json");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    @Test
    void 缺少token返回401() throws Exception {
        McpSecurityFilter filter = new McpSecurityFilter(TOKEN, null, true);
        MockHttpServletResponse response = doFilter(filter, request(null));
        assertEquals(401, response.getStatus());
        assertNull(response.getForwardedUrl(), "鉴权失败不应放行到 servlet");
    }

    @Test
    void 错误token返回401() throws Exception {
        McpSecurityFilter filter = new McpSecurityFilter(TOKEN, null, true);
        MockHttpServletResponse response = doFilter(filter, request("Bearer wrong-token"));
        assertEquals(401, response.getStatus());
    }

    @Test
    void 非Bearer方案返回401() throws Exception {
        McpSecurityFilter filter = new McpSecurityFilter(TOKEN, null, true);
        MockHttpServletResponse response = doFilter(filter, request("Basic dXNlcjpwYXNz"));
        assertEquals(401, response.getStatus());
    }

    @Test
    void 未配置token时failClosed() throws Exception {
        McpSecurityFilter filter = new McpSecurityFilter("", null, true);
        MockHttpServletResponse response = doFilter(filter, request("Bearer anything"));
        assertEquals(401, response.getStatus(), "未配置 MCP_API_TOKEN 时应拒绝所有请求");
    }

    @Test
    void 合法token放行并写入principal() throws Exception {
        McpSecurityFilter filter = new McpSecurityFilter(TOKEN, null, true);
        MockHttpServletRequest req = request("Bearer " + TOKEN);
        MockHttpServletResponse response = doFilter(filter, req);
        assertEquals(200, response.getStatus());
        assertEquals("mcp-client", req.getAttribute(McpSecurityFilter.PRINCIPAL_ATTRIBUTE));
    }

    @Test
    void token比较时空白差异不通过() throws Exception {
        McpSecurityFilter filter = new McpSecurityFilter(TOKEN, null, true);
        MockHttpServletResponse response = doFilter(filter, request("Bearer " + TOKEN + " "));
        assertEquals(401, response.getStatus(), "token 两侧空白应在 trim 前被拒绝");
    }

    @Test
    void 非法origin返回403() throws Exception {
        McpSecurityFilter filter = new McpSecurityFilter(TOKEN, McpSecurityFilter.parseAllowlist("http://localhost:5173"), true);
        MockHttpServletRequest req = request("Bearer " + TOKEN);
        req.addHeader("Origin", "http://evil.example.com");
        MockHttpServletResponse response = doFilter(filter, req);
        assertEquals(403, response.getStatus());
    }

    @Test
    void allowlist命中放行() throws Exception {
        McpSecurityFilter filter = new McpSecurityFilter(TOKEN, McpSecurityFilter.parseAllowlist("http://localhost:5173, https://demo.health.example"), true);
        MockHttpServletRequest req = request("Bearer " + TOKEN);
        req.addHeader("Origin", "https://demo.health.example");
        MockHttpServletResponse response = doFilter(filter, req);
        assertEquals(200, response.getStatus());
        assertNotNull(req.getAttribute(McpSecurityFilter.PRINCIPAL_ATTRIBUTE));
    }

    @Test
    void 空allowlist拒绝任意非空origin() throws Exception {
        // #61：空 allowlist 不再表达“任意来源”，非空 Origin 必须精确命中规范化 allowlist
        McpSecurityFilter filter = new McpSecurityFilter(TOKEN, McpSecurityFilter.parseAllowlist(""), true);
        MockHttpServletRequest req = request("Bearer " + TOKEN);
        req.addHeader("Origin", "http://anywhere.example");
        MockHttpServletResponse response = doFilter(filter, req);
        assertEquals(403, response.getStatus(), "空 allowlist + 非空 Origin 必须 fail-closed");
    }

    @Test
    void scheme主机端口任一不同均拒绝() throws Exception {
        McpSecurityFilter filter = new McpSecurityFilter(TOKEN,
                McpSecurityFilter.parseAllowlist("https://demo.health.example:8443"), true);
        for (String evil : new String[]{
                "http://demo.health.example:8443",
                "https://evil.health.example:8443",
                "https://demo.health.example:8444",
                "https://demo.health.example"
        }) {
            MockHttpServletRequest req = request("Bearer " + TOKEN);
            req.addHeader("Origin", evil);
            assertEquals(403, doFilter(filter, req).getStatus(), "Origin 必须精确命中: " + evil);
        }
    }

    @Test
    void 前后缀欺骗与大小写空白异常拒绝() throws Exception {
        McpSecurityFilter filter = new McpSecurityFilter(TOKEN,
                McpSecurityFilter.parseAllowlist("https://demo.health.example"), true);
        for (String evil : new String[]{
                "https://demo.health.example.evil.com",
                "https://evildemo.health.example",
                "https://demo.health.example/extra",
                "HTTPS://demo.health.example",
                " https://demo.health.example "
        }) {
            MockHttpServletRequest req = request("Bearer " + TOKEN);
            req.addHeader("Origin", evil);
            assertEquals(403, doFilter(filter, req).getStatus(), "非精确命中必须拒绝: [" + evil + "]");
        }
    }

    @Test
    void 重复Origin头拒绝() throws Exception {
        McpSecurityFilter filter = new McpSecurityFilter(TOKEN,
                McpSecurityFilter.parseAllowlist("https://demo.health.example"), true);
        MockHttpServletRequest req = request("Bearer " + TOKEN);
        req.addHeader("Origin", "https://demo.health.example");
        req.addHeader("Origin", "https://evil.example.com");
        assertEquals(403, doFilter(filter, req).getStatus(), "重复 Origin 头必须拒绝");
    }

    @Test
    void 缺失origin按allowMissingOrigin策略() throws Exception {
        McpSecurityFilter allowMissing = new McpSecurityFilter(TOKEN, McpSecurityFilter.parseAllowlist("http://localhost:5173"), true);
        assertEquals(200, doFilter(allowMissing, request("Bearer " + TOKEN)).getStatus());

        McpSecurityFilter rejectMissing = new McpSecurityFilter(TOKEN, McpSecurityFilter.parseAllowlist("http://localhost:5173"), false);
        assertEquals(403, doFilter(rejectMissing, request("Bearer " + TOKEN)).getStatus(), "缺失 Origin 默认 fail-closed");
    }

    @Test
    void 空白origin头不按缺失处理() throws Exception {
        McpSecurityFilter filter = new McpSecurityFilter(TOKEN,
                McpSecurityFilter.parseAllowlist("http://localhost:5173"), true);
        MockHttpServletRequest req = request("Bearer " + TOKEN);
        req.addHeader("Origin", "   ");
        assertEquals(403, doFilter(filter, req).getStatus(), "已提供但全空白的 Origin 必须拒绝");
    }

    @Test
    void 缺失origin且allowlist为空时按显式策略() throws Exception {
        // 空 allowlist + 缺失 Origin：只放行显式 allowMissingOrigin=true 的受控客户端
        assertEquals(200, doFilter(new McpSecurityFilter(TOKEN, Set.of(), true),
                request("Bearer " + TOKEN)).getStatus());
        assertEquals(403, doFilter(new McpSecurityFilter(TOKEN, Set.of(), false),
                request("Bearer " + TOKEN)).getStatus());
    }
}
