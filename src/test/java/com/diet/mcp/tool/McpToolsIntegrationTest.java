package com.diet.mcp.tool;

import com.diet.health.module.HealthResource;
import com.diet.health.module.MealModule;
import com.diet.health.module.RoutineFact;
import com.diet.health.module.RoutineModule;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.mcp.McpSecurityFilter;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
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

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 四个公共 MCP Tools 端到端测试（M5 #47）。
 * <p>
 * 与安全边界测试同构：内嵌 Tomcat 挂载真实 transport + 四个工具（领域服务用 mock 替身），
 * 验证 tools/list 暴露、合法调用返回结构化结果、Schema 拒绝与领域失败映射的 MCP 错误语义。
 */
class McpToolsIntegrationTest {

    private static final String TOKEN = "mcp-tools-itest-token";

    private Tomcat tomcat;
    private McpSyncServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        MealModule mealModule = mock(MealModule.class);
        when(mealModule.recommendMeals(anyMap(), anyList(), any()))
                .thenReturn(List.of(
                        new HealthResource("MEAL", "1", "鸡胸肉糙米饭", "PUBLIC", "公共餐食库",
                                null, false, Map.of("mealTime", List.of("午餐"))),
                        new HealthResource("MEAL", "2", "蔬菜沙拉", "PUBLIC", "公共餐食库",
                                null, false, Map.of("mealTime", List.of("晚餐")))));

        HealthResourceProvider resourceProvider = mock(HealthResourceProvider.class);
        when(resourceProvider.mealById("1"))
                .thenReturn(Optional.of(new HealthResource("MEAL", "1", "鸡胸肉糙米饭", "PUBLIC",
                        "公共餐食库", null, false, Map.of("mealTime", List.of("午餐")))));
        when(resourceProvider.mealById("999"))
                .thenReturn(Optional.empty());

        RoutineModule routineModule = mock(RoutineModule.class);
        when(routineModule.lookup(any(), any()))
                .thenReturn(List.of(new RoutineFact("R1", "睡眠", "成人睡眠时长推荐 7-9 小时", "WHO", "睡眠指南")));

        HttpServletStreamableServerTransportProvider provider = HttpServletStreamableServerTransportProvider.builder()
                .contextExtractor(McpSecurityFilter::extractTransportContext)
                .build();
        server = McpServer.sync(provider)
                .serverInfo("health-agent-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .build();
        for (McpToolSpec spec : tools(mealModule, resourceProvider, routineModule)) {
            server.addTool(spec.specification());
        }

        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("mcp-tools-tomcat").toString());
        tomcat.setPort(0);
        Context ctx = tomcat.addContext("", Files.createTempDirectory("mcp-tools-docbase").toString());
        Wrapper wrapper = Tomcat.addServlet(ctx, "mcp", provider);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded("/mcp", "mcp");
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    private List<McpToolSpec> tools(MealModule mealModule, HealthResourceProvider resourceProvider,
                                    RoutineModule routineModule) {
        return List.of(
                new MealSearchTool(mealModule),
                new MealDetailTool(resourceProvider),
                new RoutineFactsTool(routineModule),
                new CalculateTargetsTool());
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

    private McpSyncClient client() {
        return McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port + "/mcp")
                        .customizeRequest(builder -> builder.header("Authorization", "Bearer " + TOKEN))
                        .build())
                .clientInfo(new McpSchema.Implementation("mcp-tools-itest-client", "0.1.0"))
                .build();
    }

    /**
     * 断言 callTool 以 McpError 失败，并对传输层 keep-alive 连接竞态重试一次。
     * <p>
     * JDK HttpClient 连接池可能选中一条刚被内嵌 Tomcat 关闭的连接，请求尚未到达工具层便以
     * "HTTP/1.1 header parser received no bytes"（Reactor 包装的 IOException）失败，CI 上已
     * 两次偶发误报。该失败与被测错误语义无关：仅当根因为该 IOException 时重试一次（新连接
     * 必达）；调用意外成功或其余异常原样抛出。
     */
    private McpError callToolExpectingMcpError(McpSyncClient client, McpSchema.CallToolRequest request) {
        RuntimeException transportRace = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                client.callTool(request);
                throw new AssertionError("callTool 应当抛出 McpError，实际成功返回");
            } catch (McpError expected) {
                return expected;
            } catch (RuntimeException failure) {
                if (!isKeepAliveConnectionRace(failure)) {
                    throw failure;
                }
                transportRace = failure;
            }
        }
        throw transportRace;
    }

    private boolean isKeepAliveConnectionRace(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException io && String.valueOf(io.getMessage()).contains("no bytes")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void 工具列表暴露四个白名单工具() {
        try (McpSyncClient client = client()) {
            client.initialize();
            McpSchema.ListToolsResult tools = client.listTools();
            List<String> names = tools.tools().stream().map(McpSchema.Tool::name).toList();
            assertEquals(List.of("search_meals", "get_meal_detail", "get_routine_facts", "calculate_targets"),
                    names, "工具白名单必须恰好是四个只读/纯计算工具");
        }
    }

    @Test
    void searchMeals合法调用返回结构化结果() {
        try (McpSyncClient client = client()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                    "search_meals", Map.of("slots", Map.of("mealTime", List.of("午餐")), "limit", 5)));
            assertNotNull(result.structuredContent(), "search_meals 必须返回结构化结果");
            assertEquals(Boolean.FALSE, result.isError());
        }
    }

    @Test
    void getMealDetail存在与不存在映射() {
        try (McpSyncClient client = client()) {
            client.initialize();
            McpSchema.CallToolResult ok = client.callTool(new McpSchema.CallToolRequest(
                    "get_meal_detail", Map.of("mealId", 1)));
            assertEquals(Boolean.FALSE, ok.isError());
            assertTrue(String.valueOf(ok.structuredContent()).contains("鸡胸肉糙米饭"));

            McpError notFound = callToolExpectingMcpError(client, new McpSchema.CallToolRequest(
                    "get_meal_detail", Map.of("mealId", 999)));
            assertEquals(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND,
                    notFound.getJsonRpcError().code(), "不存在的餐食必须映射为 RESOURCE_NOT_FOUND");
        }
    }

    @Test
    void getRoutineFacts合法调用返回事实() {
        try (McpSyncClient client = client()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                    "get_routine_facts", Map.of("keyword", "睡多久")));
            assertEquals(Boolean.FALSE, result.isError());
            assertNotNull(result.structuredContent());
        }
    }

    @Test
    void calculateTargets确定性计算能量区间() {
        try (McpSyncClient client = client()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                    "calculate_targets", Map.of("age", 30, "sex", "MALE", "heightCm", 175,
                    "weightKg", 70, "activityLevel", "LIGHT", "goal", "MAINTAIN")));
            assertEquals(Boolean.FALSE, result.isError());
            String content = String.valueOf(result.structuredContent());
            assertTrue(content.contains("lowKcal") && content.contains("highKcal"), "能量区间必须包含 low/high kcal");
        }
    }

    @Test
    void 非法枚举被Schema拒绝() {
        try (McpSyncClient client = client()) {
            client.initialize();
            McpError error = callToolExpectingMcpError(client, new McpSchema.CallToolRequest(
                    "calculate_targets", Map.of("age", 30, "sex", "MALE", "heightCm", 175,
                    "weightKg", 70, "activityLevel", "EXTREME", "goal", "MAINTAIN")));
            assertEquals(McpSchema.ErrorCodes.INVALID_PARAMS,
                    error.getJsonRpcError().code(), "非法枚举必须映射为 INVALID_PARAMS");
        }
    }

    @Test
    void 缺失必填参数被Schema拒绝() {
        try (McpSyncClient client = client()) {
            client.initialize();
            McpError error = callToolExpectingMcpError(client, new McpSchema.CallToolRequest(
                    "get_meal_detail", Map.of()));
            assertEquals(McpSchema.ErrorCodes.INVALID_PARAMS,
                    error.getJsonRpcError().code(), "缺失必填参数必须映射为 INVALID_PARAMS");
        }
    }

    @Test
    void 非法槽位类型被Schema拒绝() {
        try (McpSyncClient client = client()) {
            client.initialize();
            McpError error = callToolExpectingMcpError(client, new McpSchema.CallToolRequest(
                    "search_meals", Map.of("slots", "晚餐")));
            assertEquals(McpSchema.ErrorCodes.INVALID_PARAMS,
                    error.getJsonRpcError().code(), "非对象 slots 必须映射为 INVALID_PARAMS");
        }
    }
}
