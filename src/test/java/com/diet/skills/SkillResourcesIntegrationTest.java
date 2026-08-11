package com.diet.skills;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能清单 MCP Resources 端到端测试（M5 #50）。
 * <p>
 * 真实 transport + SkillResources（Registry 用合法测试 manifest），验证
 * resources/list 暴露三个稳定 URI、resources/read 返回原始 YAML、未知 URI 返回 RESOURCE_NOT_FOUND。
 */
class SkillResourcesIntegrationTest {

    private Tomcat tomcat;
    private McpSyncServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        SkillsRegistry registry = new SkillsRegistry(List.of(
                manifest("meal-recommendation", "1.0.0", "search_meals"),
                manifest("routine-guidance", "1.0.0", "get_routine_facts"),
                manifest("health-target-calculation", "1.0.0", "calculate_targets")));
        SkillResources skillResources = new SkillResources(registry);

        HttpServletStreamableServerTransportProvider provider = HttpServletStreamableServerTransportProvider.builder()
                .contextExtractor(McpSecurityFilterCompat::extract)
                .build();
        server = McpServer.sync(provider)
                .serverInfo("health-agent-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .resources(true, false)
                        .build())
                .build();
        for (McpServerFeatures.SyncResourceSpecification resource : skillResources.specifications()) {
            server.addResource(resource);
        }

        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("mcp-skills-tomcat").toString());
        tomcat.setPort(0);
        Context ctx = tomcat.addContext("", Files.createTempDirectory("mcp-skills-docbase").toString());
        Wrapper wrapper = Tomcat.addServlet(ctx, "mcp", provider);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/mcp", "mcp");
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    /** contextExtractor 替身（无鉴权场景返回 EMPTY，与安全 Filter 未挂载时一致）。 */
    private static final class McpSecurityFilterCompat {
        static io.modelcontextprotocol.common.McpTransportContext extract(jakarta.servlet.http.HttpServletRequest req) {
            return io.modelcontextprotocol.common.McpTransportContext.EMPTY;
        }
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

    @Test
    void resourcesList暴露三个技能URI() {
        try (McpSyncClient client = client()) {
            client.initialize();
            McpSchema.ListResourcesResult resources = client.listResources();
            List<String> uris = resources.resources().stream().map(McpSchema.Resource::uri).toList();
            assertEquals(3, uris.size(), "必须暴露三个技能");
            assertTrue(uris.contains("skill://meal-recommendation"), "三个稳定 URI 必须全部可列出");
            assertTrue(uris.contains("skill://routine-guidance"));
            assertTrue(uris.contains("skill://health-target-calculation"));
        }
    }

    @Test
    void resourcesRead返回原始YAML() {
        try (McpSyncClient client = client()) {
            client.initialize();
            McpSchema.ReadResourceResult result = client.readResource(
                    new McpSchema.ReadResourceRequest("skill://meal-recommendation"));
            assertNotNull(result.contents());
            String text = ((McpSchema.TextResourceContents) result.contents().getFirst()).text();
            assertTrue(text.contains("name: meal-recommendation"), "read 必须返回原始 manifest YAML");
            assertTrue(text.contains("allowed_tools"), "manifest 必须包含 allowed_tools 字段");
        }
    }

    @Test
    void 未知技能URI返回RESOURCE_NOT_FOUND() {
        try (McpSyncClient client = client()) {
            client.initialize();
            McpError error = assertThrows(McpError.class, () -> client.readResource(
                    new McpSchema.ReadResourceRequest("skill://not-exist")));
            assertEquals(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND,
                    error.getJsonRpcError().code(), "未知技能 URI 必须映射为 RESOURCE_NOT_FOUND");
        }
    }

    private McpSyncClient client() {
        return McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port + "/mcp")
                        .customizeRequest(builder -> builder.header("Authorization", "Bearer skills-itest-token"))
                        .build())
                .clientInfo(new McpSchema.Implementation("mcp-skills-itest-client", "0.1.0"))
                .build();
    }

    private String manifest(String name, String version, String tool) {
        return "name: " + name + "\n" +
                "version: " + version + "\n" +
                "description: " + name + " 描述\n" +
                "input_schema:\n" +
                "  type: object\n" +
                "output_schema:\n" +
                "  type: object\n" +
                "allowed_tools:\n" +
                "  - " + tool + "\n" +
                "risk_level: low\n";
    }
}
