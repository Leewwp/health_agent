package com.diet.mcp;

import com.diet.mcp.tool.McpToolSpec;
import com.diet.skills.SkillResources;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/**
 * MCP Streamable HTTP 端点装配（M5 #51）。
 * <p>
 * 注册单一 MCP endpoint（默认 {@code /mcp}）：{@link HttpServletStreamableServerTransportProvider}
 * 本身是 {@link HttpServlet}，直接以 ServletRegistrationBean 挂到内嵌 Tomcat；
 * 身份/Origin 校验由独立 {@link McpSecurityFilter} 完成，鉴权结果通过
 * transport contextExtractor 进入 {@link McpTransportContext}，不依赖 MVC 拦截器。
 * <p>
 * 本配置只负责传输与身份边界；工具由 M5 #47 在构建时注册到同一 server。
 * 不是 MCP OAuth 2.1 或最新规范全量合规声明，仅本地演示。
 */
@Configuration
public class McpServerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfiguration.class);

    private McpSyncServer mcpServer;

    @Bean
    public McpSecurityFilter mcpSecurityFilter(
            @Value("${diet.mcp.api-token:}") String apiToken,
            @Value("${diet.mcp.allowed-origins:}") String allowedOrigins,
            @Value("${diet.mcp.allow-missing-origin:false}") boolean allowMissingOrigin) {
        // #61：占位 token、空白条目、非法 Origin URI 在启动阶段拒绝（fail-closed 前置校验）
        McpConfigValidator.validateForStartup(apiToken, allowedOrigins);
        Set<String> allowlist = McpSecurityFilter.parseAllowlist(allowedOrigins);
        if (apiToken == null || apiToken.isBlank()) {
            log.warn("MCP_API_TOKEN 未配置：MCP 端点将拒绝所有请求（fail-closed）");
        } else {
            if (allowlist.isEmpty()) {
                log.warn("MCP_API_TOKEN 已配置但 MCP_ALLOWED_ORIGINS 为空：非空 Origin 的请求将全部被拒绝（fail-closed）");
            } else {
                log.info("MCP 端点启用 Origin allowlist：{}", allowlist);
            }
            if (allowMissingOrigin) {
                log.warn("MCP 缺失 Origin 被显式放行（allow-missing-origin=true）：只适合受控服务端/CLI 客户端，生产请评估风险");
            }
        }
        return new McpSecurityFilter(apiToken, allowlist, allowMissingOrigin);
    }

    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider(McpSecurityFilter filter) {
        // 鉴权结果（Filter 写入的请求属性）→ MCP transport context，供工具 handler 读取调用方身份
        return HttpServletStreamableServerTransportProvider.builder()
                .contextExtractor(McpSecurityFilter::extractTransportContext)
                .build();
    }

    @Bean
    public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider provider,
                                   List<McpToolSpec> tools,
                                   SkillResources skillResources) {
        McpSyncServer server = McpServer.sync(provider)
                .serverInfo("health-agent-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .resources(true, false)
                        .build())
                .build();
        for (McpToolSpec tool : tools) {
            server.addTool(tool.specification());
            log.info("MCP 工具已注册：{}", tool.name());
        }
        for (McpServerFeatures.SyncResourceSpecification resource : skillResources.specifications()) {
            server.addResource(resource);
            log.info("MCP 资源已注册：{}", resource.resource().uri());
        }
        log.info("MCP Streamable HTTP server 已装配（serverInfo=health-agent-mcp 0.1.0，工具 {} 个，资源 {} 个）",
                tools.size(), skillResources.specifications().size());
        mcpServer = server;
        return server;
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServletRegistration(
            HttpServletStreamableServerTransportProvider provider,
            @Value("${diet.mcp.endpoint:/mcp}") String endpoint) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(provider, endpoint);
        registration.setName("mcpStreamableHttp");
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<McpSecurityFilter> mcpSecurityFilterRegistration(
            McpSecurityFilter filter,
            @Value("${diet.mcp.endpoint:/mcp}") String endpoint) {
        FilterRegistrationBean<McpSecurityFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("mcpSecurityFilter");
        registration.addUrlPatterns(endpoint);
        registration.setOrder(0);
        return registration;
    }

    @PreDestroy
    public void close() {
        if (mcpServer != null) {
            mcpServer.closeGracefully();
            log.info("MCP server 已优雅关闭");
        }
    }
}
