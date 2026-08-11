package com.diet.mcp.tool;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP 工具规格 seam（M5 #47）：每个工具一个 {@link McpServerFeatures.SyncToolSpecification}，
 * handler 直接调用既有领域服务（resource/profile/target），不通过本应用 HTTP API 回调自身。
 */
public interface McpToolSpec {

    /** 工具名（MCP tools/list 暴露名）。 */
    String name();

    /** 注册到 MCP server 的完整规格（Schema + handler）。 */
    McpServerFeatures.SyncToolSpecification specification();
}
