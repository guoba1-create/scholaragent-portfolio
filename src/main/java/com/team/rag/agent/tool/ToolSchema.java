package com.team.rag.agent.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具 Schema 描述
 *
 * <p>描述工具的输入输出结构，用于：
 * <ul>
 *   <li>工具发现和文档生成</li>
 *   <li>输入验证</li>
 *   <li>LLM 工具调用提示</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolSchema {

    @JsonProperty("type")
    private String type = "object";

    @JsonProperty("description")
    private String description;

    @JsonProperty("properties")
    private Map<String, PropertySchema> properties = new HashMap<>();

    @JsonProperty("required")
    private String[] required;

    // Constructors
    public ToolSchema() {}

    public ToolSchema(String description) {
        this.description = description;
    }

    // Builder methods
    public ToolSchema addProperty(String name, PropertySchema property) {
        this.properties.put(name, property);
        return this;
    }

    public ToolSchema required(String... required) {
        this.required = required;
        return this;
    }

    // Getters and Setters
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, PropertySchema> getProperties() { return properties; }
    public void setProperties(Map<String, PropertySchema> properties) { this.properties = properties; }

    public String[] getRequired() { return required; }
    public void setRequired(String[] required) { this.required = required; }

    /**
     * 属性 Schema
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PropertySchema {

        @JsonProperty("type")
        private String type;

        @JsonProperty("description")
        private String description;

        @JsonProperty("enum")
        private String[] enumValues;

        @JsonProperty("default")
        private Object defaultValue;

        public PropertySchema() {}

        public PropertySchema(String type, String description) {
            this.type = type;
            this.description = description;
        }

        // Static factory methods
        public static PropertySchema string(String description) {
            return new PropertySchema("string", description);
        }

        public static PropertySchema integer(String description) {
            return new PropertySchema("integer", description);
        }

        public static PropertySchema number(String description) {
            return new PropertySchema("number", description);
        }

        public static PropertySchema booleanType(String description) {
            return new PropertySchema("boolean", description);
        }

        public static PropertySchema array(String description) {
            return new PropertySchema("array", description);
        }

        public static PropertySchema object(String description) {
            return new PropertySchema("object", description);
        }

        // Getters and Setters
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String[] getEnumValues() { return enumValues; }
        public void setEnumValues(String[] enumValues) { this.enumValues = enumValues; }

        public Object getDefaultValue() { return defaultValue; }
        public void setDefaultValue(Object defaultValue) { this.defaultValue = defaultValue; }

        public PropertySchema enumValues(String... values) {
            this.enumValues = values;
            return this;
        }

        public PropertySchema defaultValue(Object defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }
    }

    /**
     * 创建简单的字符串输入 Schema
     */
    public static ToolSchema simpleInput(String paramName, String paramDescription) {
        ToolSchema schema = new ToolSchema();
        schema.addProperty(paramName, PropertySchema.string(paramDescription));
        schema.required(paramName);
        return schema;
    }

    /**
     * 创建简单的字符串输出 Schema
     */
    public static ToolSchema simpleOutput(String description) {
        ToolSchema schema = new ToolSchema(description);
        return schema;
    }
}
