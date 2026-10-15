package com.team.rag.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.rag.agent.tool.*;
import com.team.rag.agent.runtime.RawToolPayloadStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 桥接 ScholarAgentTool 注册表与 MCP 协议。
 * 将 MCPToolRegistry 中的 ScholarAgentTool 转换为 MCP Tool 定义，
 * 并将 MCP tools/call 请求转发给 MCPToolRegistry 执行。
 */
public class McpToolBridge {

    private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);

    private final MCPToolRegistry registry;
    private final ObjectMapper objectMapper;
    private final RawToolPayloadStore rawPayloadStore;

    public McpToolBridge(MCPToolRegistry registry, ObjectMapper objectMapper,
                         RawToolPayloadStore rawPayloadStore) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.rawPayloadStore = rawPayloadStore;
    }

    /**
     * 获取所有可用工具的 MCP Tool 定义列表（JSON 格式）。
     */
    public List<Map<String, Object>> getMcpToolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ScholarAgentTool<?, ?> tool : registry.getAvailableTools()) {
            tools.add(toMcpToolDef(tool));
        }
        return tools;
    }

    /**
     * 将 ScholarAgentTool 转换为 MCP Tool 定义 Map。
     */
    public Map<String, Object> toMcpToolDef(ScholarAgentTool<?, ?> tool) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", tool.name());
        def.put("description", tool.description());
        def.put("inputSchema", buildInputSchema(tool));
        return def;
    }

    /**
     * 处理 MCP tools/call 请求，委托给 MCPToolRegistry。
     */
    public Map<String, Object> handleToolCall(String toolName, Map<String, Object> arguments) {
        ToolContext context = extractContext(arguments);
        Map<String, Object> toolInput = extractToolInput(arguments);

        ToolResult<?> result = registry.executeTool(toolName, toolInput, context);
        Object rawPayload = result.isSuccess() ? result.getData() : Map.of(
                "errorCode", result.getErrorCode() == null ? "TOOL_ERROR" : result.getErrorCode(),
                "errorMessage", result.getErrorMessage() == null ? "Tool execution failed" : result.getErrorMessage());
        String rawPayloadRef = rawPayloadStore.store(context.getWorkflowId(), toolName, rawPayload);

        Map<String, Object> response = new LinkedHashMap<>();
        if (result.isSuccess()) {
            String json = serialize(result.getData());
            response.put("content", List.of(Map.of("type", "text", "text", json)));
            response.put("isError", false);
        } else {
            String errorText = "{\"error\": \"" + escapeJson(result.getErrorMessage())
                    + "\", \"code\": \"" + escapeJson(result.getErrorCode()) + "\"}";
            response.put("content", List.of(Map.of("type", "text", "text", errorText)));
            response.put("isError", true);
        }
        response.put("rawPayloadRef", rawPayloadRef);
        return response;
    }

    private ToolContext extractContext(Map<String, Object> arguments) {
        if (arguments == null) return new ToolContext();
        String workflowId = getStringArg(arguments, "__workflowId", "");
        String userId = getStringArg(arguments, "__userId", "user-001");
        String researchGroupId = getStringArg(arguments, "__researchGroupId", "replace-me");
        String agentName = getStringArg(arguments, "__agentName", "mcp-client");
        return ToolContext.of(workflowId, userId, researchGroupId, agentName);
    }

    private Map<String, Object> extractToolInput(Map<String, Object> arguments) {
        if (arguments == null) return Map.of();
        Map<String, Object> input = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (!entry.getKey().startsWith("__")) {
                input.put(entry.getKey(), entry.getValue());
            }
        }
        return input;
    }

    private Map<String, Object> buildInputSchema(ScholarAgentTool<?, ?> tool) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        ToolSchema inputSchema = tool.inputSchema();
        Map<String, Object> properties = new LinkedHashMap<>();

        if (inputSchema != null && inputSchema.getProperties() != null) {
            for (Map.Entry<String, ToolSchema.PropertySchema> entry : inputSchema.getProperties().entrySet()) {
                Map<String, Object> prop = new LinkedHashMap<>();
                prop.put("type", entry.getValue().getType());
                if (entry.getValue().getDescription() != null) {
                    prop.put("description", entry.getValue().getDescription());
                }
                if (entry.getValue().getEnumValues() != null) {
                    prop.put("enum", entry.getValue().getEnumValues());
                }
                if (entry.getValue().getDefaultValue() != null) {
                    prop.put("default", entry.getValue().getDefaultValue());
                }
                properties.put(entry.getKey(), prop);
            }
        }

        // 内部上下文字段
        properties.put("__workflowId", Map.of("type", "string", "description", "Workflow ID (internal)"));
        properties.put("__userId", Map.of("type", "string", "description", "User ID (internal)"));
        properties.put("__researchGroupId", Map.of("type", "string", "description", "Research Group ID (internal)"));
        properties.put("__agentName", Map.of("type", "string", "description", "Agent name (internal)"));
        properties.put("__stepId", Map.of("type", "string", "description", "Step ID (internal)"));
        properties.put("__parentStepId", Map.of("type", "string", "description", "Parent step ID (internal)"));

        schema.put("properties", properties);

        if (inputSchema != null && inputSchema.getRequired() != null) {
            schema.put("required", List.of(inputSchema.getRequired()));
        }

        return schema;
    }

    private String getStringArg(Map<String, Object> args, String key, String defaultValue) {
        Object val = args.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private String serialize(Object obj) {
        if (obj == null) return "null";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
