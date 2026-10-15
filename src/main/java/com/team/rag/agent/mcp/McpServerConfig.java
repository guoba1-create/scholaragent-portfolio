package com.team.rag.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.tool.MCPToolRegistry;
import com.team.rag.agent.runtime.RawToolPayloadStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

/**
 * MCP Server 配置。
 * 注册 /mcp/sse (SSE 连接) 和 /mcp/message (JSON-RPC 消息) 路由。
 */
@Configuration
public class McpServerConfig {

    @Bean
    public McpToolBridge mcpToolBridge(MCPToolRegistry registry, ObjectMapper objectMapper,
                                       RawToolPayloadStore rawPayloadStore) {
        return new McpToolBridge(registry, objectMapper, rawPayloadStore);
    }

    @Bean
    public RouterFunction<ServerResponse> mcpRoutes(McpSseHandler handler) {
        return RouterFunctions
                .route(GET("/mcp/sse"), handler::handleSse)
                .andRoute(POST("/mcp/message"), handler::handleMessage);
    }
}
