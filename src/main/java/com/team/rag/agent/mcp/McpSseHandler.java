package com.team.rag.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP SSE Transport 实现。
 * <p>
 * 协议流程：
 * 1. 客户端 GET /mcp/sse → 服务端建立 SSE 连接，发送 endpoint 事件
 * 2. 客户端 POST /mcp/message?sessionId=xxx → 发送 JSON-RPC 请求
 * 3. 服务端通过 SSE 连接返回 JSON-RPC 响应
 */
@Component
public class McpSseHandler {

    private static final Logger log = LoggerFactory.getLogger(McpSseHandler.class);
    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final McpToolBridge toolBridge;
    private final ObjectMapper objectMapper;

    /** sessionId → SSE sink */
    private final ConcurrentHashMap<String, Sinks.Many<String>> sessions = new ConcurrentHashMap<>();

    public McpSseHandler(McpToolBridge toolBridge, ObjectMapper objectMapper) {
        this.toolBridge = toolBridge;
        this.objectMapper = objectMapper;
    }

    /**
     * GET /mcp/sse → 建立 SSE 连接
     */
    public Mono<ServerResponse> handleSse(ServerRequest request) {
        String sessionId = UUID.randomUUID().toString();
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        sessions.put(sessionId, sink);

        log.info("MCP SSE session created | sessionId={}", sessionId);

        // 发送 endpoint 事件，告知客户端消息发送地址
        sink.tryEmitNext("event: endpoint\ndata: /mcp/message?sessionId=" + sessionId + "\n\n");

        // 心跳保活
        Flux<String> heartbeat = Flux.interval(Duration.ofSeconds(30))
                .map(tick -> "event: ping\ndata: {}\n\n");

        // SSE 数据流：合并 sink 和心跳
        Flux<String> sseFlux = sink.asFlux()
                .mergeWith(heartbeat)
                .doFinally(signal -> {
                    sessions.remove(sessionId);
                    log.info("MCP SSE session closed | sessionId={}, signal={}", sessionId, signal);
                });

        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(sseFlux, String.class);
    }

    /**
     * POST /mcp/message?sessionId=xxx → 处理 JSON-RPC 请求
     */
    public Mono<ServerResponse> handleMessage(ServerRequest request) {
        String sessionId = request.queryParam("sessionId").orElse(null);
        if (sessionId == null || !sessions.containsKey(sessionId)) {
            return ServerResponse.badRequest().bodyValue(
                    Map.of("error", "Invalid or missing sessionId"));
        }

        return request.bodyToMono(String.class)
                .flatMap(body -> {
                    try {
                        Map<String, Object> jsonRpc = objectMapper.readValue(body,
                                new TypeReference<Map<String, Object>>() {});
                        String response = handleJsonRpc(jsonRpc);
                        Sinks.Many<String> sink = sessions.get(sessionId);
                        if (sink != null) {
                            sink.tryEmitNext("event: message\ndata: " + response + "\n\n");
                        }
                        return ServerResponse.ok().bodyValue("ok");
                    } catch (Exception e) {
                        log.error("MCP message processing error | sessionId={}", sessionId, e);
                        return ServerResponse.badRequest().bodyValue(
                                Map.of("error", e.getMessage()));
                    }
                });
    }

    /**
     * 处理 JSON-RPC 请求，返回 JSON-RPC 响应字符串。
     */
    private String handleJsonRpc(Map<String, Object> request) {
        String jsonrpc = (String) request.getOrDefault("jsonrpc", "2.0");
        Object id = request.get("id");
        String method = (String) request.get("method");
        Object params = request.get("params");

        log.debug("MCP JSON-RPC | method={}, id={}", method, id);

        // 通知（无 id）不需要响应
        if (id == null) {
            log.debug("MCP notification | method={}", method);
            return serialize(Map.of("jsonrpc", "2.0", "id", null, "result", Map.of()));
        }

        try {
            Object result = switch (method) {
                case "initialize" -> handleInitialize(params);
                case "tools/list" -> handleToolsList();
                case "tools/call" -> handleToolsCall(params);
                case "ping" -> Map.of();
                default -> throw new UnsupportedOperationException("Unknown method: " + method);
            };
            return serialize(Map.of("jsonrpc", "2.0", "id", id, "result", result));
        } catch (Exception e) {
            log.error("MCP method error | method={}", method, e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", -32000);
            error.put("message", e.getMessage());
            return serialize(Map.of("jsonrpc", "2.0", "id", id, "error", error));
        }
    }

    /**
     * 处理 initialize 请求。
     */
    private Map<String, Object> handleInitialize(Object params) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("listChanged", false);
        capabilities.put("tools", tools);
        result.put("capabilities", capabilities);

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "literature-rag-mcp-server");
        serverInfo.put("version", "1.0.0");
        result.put("serverInfo", serverInfo);

        return result;
    }

    /**
     * 处理 tools/list 请求。
     */
    private Map<String, Object> handleToolsList() {
        return Map.of("tools", toolBridge.getMcpToolDefinitions());
    }

    /**
     * 处理 tools/call 请求。
     */
    private Map<String, Object> handleToolsCall(Object params) {
        @SuppressWarnings("unchecked")
        Map<String, Object> paramsMap = (Map<String, Object>) params;
        String toolName = (String) paramsMap.get("name");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) paramsMap.getOrDefault("arguments", Map.of());
        return toolBridge.handleToolCall(toolName, arguments);
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\": \"serialize failed\"}";
        }
    }
}
