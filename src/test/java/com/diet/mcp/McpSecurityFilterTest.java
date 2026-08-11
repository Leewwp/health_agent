package com.diet.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * McpSecurityFilter 单元测试（M5 #51）：token 缺失/错误/合法、Origin allowlist、
 * 缺失 Origin 策略与鉴权结果写入请求属性。
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
    void 空allowlist不限制origin() throws Exception {
        McpSecurityFilter filter = new McpSecurityFilter(TOKEN, McpSecurityFilter.parseAllowlist(""), true);
        MockHttpServletRequest req = request("Bearer " + TOKEN);
        req.addHeader("Origin", "http://anywhere.example");
        MockHttpServletResponse response = doFilter(filter, req);
        assertEquals(200, response.getStatus(), "空 allowlist 表示本地演示不限制 Origin");
    }

    @Test
    void 缺失origin按allowMissingOrigin策略() throws Exception {
        McpSecurityFilter allowMissing = new McpSecurityFilter(TOKEN, McpSecurityFilter.parseAllowlist("http://localhost:5173"), true);
        assertEquals(200, doFilter(allowMissing, request("Bearer " + TOKEN)).getStatus());

        McpSecurityFilter rejectMissing = new McpSecurityFilter(TOKEN, McpSecurityFilter.parseAllowlist("http://localhost:5173"), false);
        assertEquals(403, doFilter(rejectMissing, request("Bearer " + TOKEN)).getStatus());
    }
}
