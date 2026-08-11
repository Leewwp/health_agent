package com.diet.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 安全边界端到端测试（M5 #51）。
 * <p>
 * 与闸门冒烟同构：内嵌 Tomcat 挂载真实的 {@link HttpServletStreamableServerTransportProvider}
 * 与 {@link McpSecurityFilter}（与 Spring Boot 中的装配一致），验证：
 * 合法客户端可完成 initialize、无/错 token 返回 401、非法 Origin 返回 403、
 * 合法鉴权后 transport context 携带 principal、非 MCP 协议请求得到错误响应。
 */
class McpServerSecurityIntegrationTest {

    private static final String TOKEN = "mcp-itest-token";
    private static final String ENDPOINT = "/mcp";

    private Tomcat tomcat;
    private McpSyncServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        McpSecurityFilter filter = new McpSecurityFilter(TOKEN,
                McpSecurityFilter.parseAllowlist("http://localhost:5173"), true);
        HttpServletStreamableServerTransportProvider provider = HttpServletStreamableServerTransportProvider.builder()
                .contextExtractor(request -> {
                    Object principal = request.getAttribute(McpSecurityFilter.PRINCIPAL_ATTRIBUTE);
                    return principal == null
                            ? io.modelcontextprotocol.common.McpTransportContext.EMPTY
                            : io.modelcontextprotocol.common.McpTransportContext.create(
                            java.util.Map.of("principal", String.valueOf(principal)));
                })
                .build();
        server = McpServer.sync(provider)
                .serverInfo("health-agent-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .build();

        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("mcp-sec-tomcat").toString());
        tomcat.setPort(0);
        Context ctx = tomcat.addContext("", Files.createTempDirectory("mcp-sec-docbase").toString());

        Wrapper wrapper = Tomcat.addServlet(ctx, "mcp", provider);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded(ENDPOINT, "mcp");

        FilterDef filterDef = new FilterDef();
        filterDef.setFilterName("mcpSecurity");
        filterDef.setFilter(filter);
        filterDef.setAsyncSupported("true");
        ctx.addFilterDef(filterDef);
        FilterMap filterMap = new FilterMap();
        filterMap.setFilterName("mcpSecurity");
        filterMap.addURLPattern(ENDPOINT);
        ctx.addFilterMap(filterMap);

        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.closeGracefully();
        }
        Schedulers.shutdownNow();
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private String baseUrl() {
        return "http://localhost:" + port + ENDPOINT;
    }

    private McpSyncClient client(String token) {
        return McpClient.sync(HttpClientStreamableHttpTransport.builder(baseUrl())
                        .customizeRequest(builder -> {
                            if (token != null) {
                                builder.header("Authorization", "Bearer " + token);
                            }
                        })
                        .build())
                .clientInfo(new McpSchema.Implementation("mcp-sec-itest-client", "0.1.0"))
                .build();
    }

    private HttpResponse<String> rawPost(String body, String authorization, String origin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl()))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream");
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        if (origin != null) {
            builder.header("Origin", origin);
        }
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.startsWith("application/json"),
                "拒绝响应应携带 JSON Content-Type，实际 " + contentType);
        return response;
    }

    @Test
    void 合法token客户端可完成initialize() {
        try (McpSyncClient client = client(TOKEN)) {
            McpSchema.InitializeResult init = client.initialize();
            assertNotNull(init);
            assertEquals("health-agent-mcp", init.serverInfo().name());
            assertTrue(client.isInitialized());
        }
    }

    @Test
    void 缺少token返回401() throws Exception {
        HttpResponse<String> response = rawPost("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                null, null);
        assertEquals(401, response.statusCode());
    }

    @Test
    void 错误token返回401() throws Exception {
        HttpResponse<String> response = rawPost("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                "Bearer wrong-token", null);
        assertEquals(401, response.statusCode());
    }

    @Test
    void 非法origin返回403() throws Exception {
        HttpResponse<String> response = rawPost("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                "Bearer " + TOKEN, "http://evil.example.com");
        assertEquals(403, response.statusCode());
    }

    @Test
    void allowlist内origin放行() {
        try (McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder(baseUrl())
                        .customizeRequest(builder -> {
                            builder.header("Authorization", "Bearer " + TOKEN);
                            builder.header("Origin", "http://localhost:5173");
                        })
                        .build())
                .clientInfo(new McpSchema.Implementation("mcp-sec-itest-client", "0.1.0"))
                .build()) {
            McpSchema.InitializeResult init = client.initialize();
            assertNotNull(init, "allowlist 内 Origin 的合法客户端应能完成 initialize");
            assertEquals("health-agent-mcp", init.serverInfo().name());
        }
    }

    @Test
    void 鉴权后transportContext携带principal() throws Exception {
        McpSecurityFilter probeFilter = new McpSecurityFilter(TOKEN,
                McpSecurityFilter.parseAllowlist("http://localhost:5173"), true);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        probeFilter.doFilter(req, response, new MockFilterChain());
        io.modelcontextprotocol.common.McpTransportContext ctx = McpSecurityFilter.extractTransportContext(req);
        assertNotNull(ctx);
        assertEquals("mcp-client", ctx.get("principal"));
    }

    @Test
    void 非MCP协议请求得到协议错误响应() throws Exception {
        // 合法鉴权 + 非 JSON-RPC 请求体 → SDK transport 返回协议错误（4xx），而不是业务 200
        HttpResponse<String> response = rawPost("not-json-rpc", "Bearer " + TOKEN, null);
        assertTrue(response.statusCode() >= 400 && response.statusCode() < 500,
                "非协议请求应返回 4xx，实际 " + response.statusCode());
    }

    @Test
    void 工具白名单暴露在工具列表() throws Exception {
        try (McpSyncClient client = client(TOKEN)) {
            client.initialize();
            McpSchema.ListToolsResult tools = client.listTools();
            assertNotNull(tools);
            assertEquals(0, tools.tools().size(), "M5 #47 之前不应暴露任何工具");
        }
    }
}
