package com.diet.m5;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 闸门 #48：MCP Java SDK 0.17.0 与 Spring Boot 3.3.13 / Java 21 依赖线的兼容性冒烟。
 * <p>
 * 用 SDK 自带的 servlet streamable transport（{@link HttpServletStreamableServerTransportProvider}，
 * 它本身就是一个 jakarta.servlet.http.HttpServlet）挂到内嵌 Tomcat（tomcat-embed-core 由
 * spring-boot-starter-web 传递提供，不引入新依赖），再以 SDK 同步客户端完成
 * initialize → tools/list → tools/call → resources/read 全链路。
 * 本测试仅验证依赖线可承载 MCP，不接入任何应用代码（应用接入是 02/04 号票）。
 */
class McpGateSmokeTest {

    private Tomcat tomcat;
    private McpSyncServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.closeGracefully();
        }
        // SDK 客户端使用 Reactor 全局调度器；测试结束时关闭，避免 Tomcat 报 WebApp 线程泄漏。
        Schedulers.shutdownNow();
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    @Test
    void mcpSyncFullChainOnServletTransport() throws Exception {
        // ---- 服务端：注册 1 个样例 tool + 1 个样例 resource ----
        HttpServletStreamableServerTransportProvider provider =
                HttpServletStreamableServerTransportProvider.builder().build();
        server = McpServer.sync(provider)
                .serverInfo("diet-gate-smoke", "0.0.1")
                .tool(McpSchema.Tool.builder()
                                .name("echo")
                                .description("回显传入的 message 参数（闸门冒烟样例 tool）")
                                .inputSchema(new McpSchema.JsonSchema("object",
                                        Map.of("message", Map.of("type", "string")),
                                        List.of("message"), false, Map.of(), Map.of()))
                                .build(),
                        (exchange, args) -> new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(String.valueOf(args.get("message")))),
                                false))
                .resources(new McpServerFeatures.SyncResourceSpecification(
                        McpSchema.Resource.builder()
                                .uri("gate://demo/note")
                                .name("闸门冒烟样例 resource")
                                .mimeType("text/plain")
                                .build(),
                        (exchange, req) -> new McpSchema.ReadResourceResult(
                                List.of(new McpSchema.TextResourceContents(
                                        req.uri(), "text/plain", "gate-smoke-ok")))))
                .build();

        // ---- 内嵌 Tomcat 挂载 MCP servlet（模拟 Spring Boot 3.3 的 jakarta.servlet 容器）----
        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("mcp-tomcat").toString());
        tomcat.setPort(0);
        Context ctx = tomcat.addContext("", Files.createTempDirectory("mcp-docbase").toString());
        Wrapper wrapper = Tomcat.addServlet(ctx, "mcp", provider);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/mcp", "mcp");
        tomcat.start();
        int port = tomcat.getConnector().getLocalPort();

        // ---- 客户端：SDK 同步客户端全链路 ----
        String baseUrl = "http://localhost:" + port + "/mcp";
        try (McpSyncClient client = McpClient.sync(
                        HttpClientStreamableHttpTransport.builder(baseUrl).build())
                .clientInfo(new McpSchema.Implementation("diet-gate-smoke-client", "0.0.1"))
                .build()) {

            // 1. initialize
            McpSchema.InitializeResult init = client.initialize();
            assertNotNull(init);
            assertEquals("diet-gate-smoke", init.serverInfo().name());
            assertTrue(client.isInitialized());

            // 2. tools/list
            McpSchema.ListToolsResult tools = client.listTools();
            assertNotNull(tools);
            assertEquals(1, tools.tools().size());
            assertEquals("echo", tools.tools().getFirst().name());

            // 3. tools/call
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder()
                    .name("echo")
                    .arguments(Map.of("message", "hello-from-gate"))
                    .build());
            assertNotNull(result);
            assertNotNull(result.content());
            assertEquals(1, result.content().size());
            assertTrue(result.content().getFirst() instanceof McpSchema.TextContent);
            assertEquals("hello-from-gate",
                    ((McpSchema.TextContent) result.content().getFirst()).text());

            // 4. resources/read
            McpSchema.ReadResourceResult res = client.readResource(
                    new McpSchema.ReadResourceRequest("gate://demo/note"));
            assertNotNull(res);
            assertNotNull(res.contents());
            assertEquals(1, res.contents().size());
            assertEquals("gate-smoke-ok",
                    ((McpSchema.TextResourceContents) res.contents().getFirst()).text());
        }
    }
}
