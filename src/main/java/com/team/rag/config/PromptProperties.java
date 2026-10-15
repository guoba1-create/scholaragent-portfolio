package com.team.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Prompt 模板配置属性类（Prompt Template Configuration Properties）
 *
 * <p>本类是整个 Prompt 管理体系的配置数据载体，负责从 {@code prompts.yml} 文件中
 * 加载所有 Prompt 模板的配置信息，并通过 Spring Boot 的 {@code @ConfigurationProperties}
 * 机制实现类型安全的绑定。每个 Prompt 模板在 YAML 中以键值对的形式存储在 {@code configs}
 * 映射中，键为模板名称（如 {@code intent-recognition}），值为 {@link PromptConfig} 对象。
 *
 * <p>核心职责：
 * <ul>
 *   <li>将 YAML 配置文件中的 prompts 节点自动映射到 Java 对象</li>
 *   <li>通过 {@link #getPrompt(String)} 提供按名称查找模板的能力</li>
 *   <li>支持 15+ 个 Prompt 模板的统一管理（意图识别、对话生成、CRAG 等）</li>
 *   <li>提供变量声明、必填校验、版本追踪等元数据</li>
 * </ul>
 *
 * <h3>YAML 配置结构示例：</h3>
 * <pre>
 * prompts:
 *   configs:
 *     intent-recognition:
 *       name: "意图识别引擎"
 *       description: "分析用户输入的意图类别"
 *       template: "请分析以下用户输入的意图：{{query}}"
 *       systemMessage: "你是一个意图识别专家"
 *       userMessage: "用户输入：{{query}}"
 *       version: "1.0"
 *       variables:
 *         - name: "query"
 *           description: "用户输入的文本"
 *           required: true
 *       responses:
 *         fallback: "无法识别意图"
 * </pre>
 *
 * <p>注意：{@code prompts.yml} 通过 {@code application.yml} 的 {@code spring.config.import} 导入。
 *
 * @see PromptConfig   单个 Prompt 模板的配置详情
 * @see VariableConfig  模板变量的配置详情
 */
@Configuration
@ConfigurationProperties(prefix = "prompts")
public class PromptProperties {

    /**
     * 所有 Prompt 模板的配置映射。
     * <p>键为模板的唯一标识名称（对应 YAML 中 prompts.configs 下的一级键），
     * 值为该模板的完整配置对象 {@link PromptConfig}。
     */
    private Map<String, PromptConfig> configs;

    /**
     * 获取所有 Prompt 模板的配置映射。
     *
     * @return 模板名称到配置对象的映射，可能为 {@code null}（未配置时）
     */
    public Map<String, PromptConfig> getConfigs() {
        return configs;
    }

    /**
     * 设置所有 Prompt 模板的配置映射。
     * <p>通常由 Spring Boot 自动调用，将 YAML 配置绑定到此字段。
     *
     * @param configs 模板名称到配置对象的映射
     */
    public void setConfigs(Map<String, PromptConfig> configs) {
        this.configs = configs;
    }

    /**
     * 根据名称获取指定的 Prompt 模板配置。
     * <p>此方法是 {@link PromptTemplateService} 中所有查询方法的基础，
     * 通过模板名称从 {@code configs} 映射中查找对应的配置对象。
     *
     * @param name Prompt 模板的唯一标识名称（对应 YAML 中的键名，如 {@code "intent-recognition"}）
     * @return 对应的 {@link PromptConfig} 配置对象；如果 {@code configs} 为 {@code null} 或名称不存在则返回 {@code null}
     */
    public PromptConfig getPrompt(String name) {
        // configs 可能为 null（YAML 中未配置 prompts 节点时）
        if (configs == null) {
            return null;
        }
        return configs.get(name); // 从映射中按名称查找，未找到返回 null
    }

    /**
     * 单个 Prompt 模板的完整配置（Prompt Template Configuration）。
     *
     * <p>每个 PromptConfig 实例对应 YAML 中一个 Prompt 模板的所有配置项，
     * 包括模板正文、系统消息、用户消息、变量声明、版本信息等。
     * 模板正文支持两种模式：
     * <ul>
     *   <li><b>统一模板模式</b>：仅使用 {@code template} 字段，包含完整的 Prompt 文本</li>
     *   <li><b>分层消息模式</b>：使用 {@code systemMessage} + {@code userMessage} 分离系统指令和用户输入</li>
     * </ul>
     *
     * @see #getFullTemplate() 获取完整模板文本（自动处理上述两种模式）
     */
    public static class PromptConfig {

        /** 模板的显示名称（如"意图识别引擎"），用于日志和管理界面展示 */
        private String name;

        /** 模板的功能描述，说明该 Prompt 的用途和适用场景 */
        private String description;

        /** 模板正文，包含 {{variable}} 格式的变量占位符 */
        private String template;

        /** 系统消息，用于 LangChain4j 的 @SystemMessage 注入，定义 AI 的角色和行为规范 */
        private String systemMessage;

        /** 用户消息模板，包含用户输入的 Prompt 部分，支持变量占位符 */
        private String userMessage;

        /** 模板中声明的变量列表，用于必填校验和文档生成 */
        private List<VariableConfig> variables;

        /** 模板版本号（如 "1.0"、"2.1-beta"），用于版本管理和灰度发布 */
        private String version;

        /** 模板最后更新时间，格式为字符串（如 "2024-01-15"），用于变更追踪 */
        private String updatedAt;

        /** 预定义的响应映射，存储特定场景下的固定回复（如 CRAG 的 irrelevant、partial-prefix 等） */
        private Map<String, String> responses;

        /**
         * 获取模板的显示名称。
         *
         * @return 模板名称（如"意图识别引擎"）
         */
        public String getName() {
            return name;
        }

        /**
         * 设置模板的显示名称。
         *
         * @param name 模板名称
         */
        public void setName(String name) {
            this.name = name;
        }

        /**
         * 获取模板的功能描述。
         *
         * @return 描述文本，说明 Prompt 的用途
         */
        public String getDescription() {
            return description;
        }

        /**
         * 设置模板的功能描述。
         *
         * @param description 描述文本
         */
        public void setDescription(String description) {
            this.description = description;
        }

        /**
         * 获取模板正文。
         * <p>模板正文中可包含 {@code {{variable}}} 格式的变量占位符，
         * 渲染时会被实际值替换。
         *
         * @return 模板正文字符串
         */
        public String getTemplate() {
            return template;
        }

        /**
         * 设置模板正文。
         *
         * @param template 模板正文，支持 {{variable}} 占位符
         */
        public void setTemplate(String template) {
            this.template = template;
        }

        /**
         * 获取系统消息。
         * <p>系统消息用于定义 AI 的角色、行为规范和约束条件，
         * 通常配合 LangChain4j 的 {@code @SystemMessage} 注解使用。
         *
         * @return 系统消息字符串
         */
        public String getSystemMessage() {
            return systemMessage;
        }

        /**
         * 设置系统消息。
         *
         * @param systemMessage 系统消息字符串
         */
        public void setSystemMessage(String systemMessage) {
            this.systemMessage = systemMessage;
        }

        /**
         * 获取用户消息模板。
         * <p>用户消息模板包含用户输入部分的 Prompt，支持变量占位符，
         * 与 {@code systemMessage} 配合实现分层消息结构。
         *
         * @return 用户消息模板字符串
         */
        public String getUserMessage() {
            return userMessage;
        }

        /**
         * 设置用户消息模板。
         *
         * @param userMessage 用户消息模板字符串
         */
        public void setUserMessage(String userMessage) {
            this.userMessage = userMessage;
        }

        /**
         * 获取模板中声明的变量列表。
         * <p>变量列表用于：
         * <ul>
         *   <li>渲染时进行必填变量校验</li>
         *   <li>生成 API 文档时描述参数信息</li>
         *   <li>前端表单的动态渲染</li>
         * </ul>
         *
         * @return 变量配置列表
         */
        public List<VariableConfig> getVariables() {
            return variables;
        }

        /**
         * 设置模板的变量列表。
         *
         * @param variables 变量配置列表
         */
        public void setVariables(List<VariableConfig> variables) {
            this.variables = variables;
        }

        /**
         * 获取模板版本号。
         *
         * @return 版本号字符串（如 "1.0"）
         */
        public String getVersion() {
            return version;
        }

        /**
         * 设置模板版本号。
         *
         * @param version 版本号字符串
         */
        public void setVersion(String version) {
            this.version = version;
        }

        /**
         * 获取模板最后更新时间。
         *
         * @return 更新时间字符串（如 "2024-01-15"）
         */
        public String getUpdatedAt() {
            return updatedAt;
        }

        /**
         * 设置模板最后更新时间。
         *
         * @param updatedAt 更新时间字符串
         */
        public void setUpdatedAt(String updatedAt) {
            this.updatedAt = updatedAt;
        }

        /**
         * 获取预定义的响应映射。
         * <p>responses 映射存储特定场景下的固定回复文本，
         * 例如 CRAG 场景中的 {@code irrelevant}（不相关回复）、
         * {@code partial-prefix}（部分相关前缀）等。
         *
         * @return 响应键名到响应文本的映射
         */
        public Map<String, String> getResponses() {
            return responses;
        }

        /**
         * 设置预定义的响应映射。
         *
         * @param responses 响应键名到响应文本的映射
         */
        public void setResponses(Map<String, String> responses) {
            this.responses = responses;
        }

        /**
         * 获取完整的 Prompt 模板文本。
         *
         * <p>按照以下优先级返回模板内容：
         * <ol>
         *   <li>如果 {@code template} 非空，直接返回（统一模板模式）</li>
         *   <li>如果 {@code systemMessage} 非空，拼接 systemMessage 和 userMessage 返回（分层消息模式）</li>
         *   <li>以上都为空时返回空字符串</li>
         * </ol>
         *
         * <p>分层消息模式下，systemMessage 和 userMessage 之间用两个换行符分隔，
         * 以保持 Prompt 的可读性。
         *
         * @return 完整的 Prompt 模板文本；如果没有任何模板内容则返回空字符串
         */
        public String getFullTemplate() {
            // 优先使用统一模板（template 字段），适用于单段式 Prompt
            if (template != null && !template.isBlank()) {
                return template;
            }
            // 其次使用分层消息模式（systemMessage + userMessage），适用于角色扮演类 Prompt
            if (systemMessage != null && !systemMessage.isBlank()) {
                StringBuilder sb = new StringBuilder(systemMessage); // 以系统消息作为基础
                if (userMessage != null && !userMessage.isBlank()) {
                    sb.append("\n\n").append(userMessage); // 用空行分隔系统消息和用户消息
                }
                return sb.toString();
            }
            // 两种模板都未配置时返回空字符串
            return "";
        }
    }

    /**
     * 模板变量的配置（Variable Configuration）。
     *
     * <p>描述 Prompt 模板中一个变量的元信息，包括变量名称、描述和是否必填。
     * 这些信息用于：
     * <ul>
     *   <li>{@link PromptTemplateService#validateRequiredVariables} 进行必填校验</li>
     *   <li>自动生成 API 参数文档</li>
     *   <li>前端表单的动态渲染和校验</li>
     * </ul>
     *
     * <p>对应 YAML 配置中的 {@code variables} 列表项：
     * <pre>
     * variables:
     *   - name: "query"
     *     description: "用户输入的查询文本"
     *     required: true
     * </pre>
     */
    public static class VariableConfig {

        /** 变量名称，对应模板中的 {{name}} 占位符 */
        private String name;

        /** 变量的功能描述，说明该变量在 Prompt 中的作用 */
        private String description;

        /** 是否为必填变量，为 true 时渲染前会进行非空校验 */
        private boolean required;

        /**
         * 获取变量名称。
         *
         * @return 变量名称（对应模板中的 {{name}} 占位符）
         */
        public String getName() {
            return name;
        }

        /**
         * 设置变量名称。
         *
         * @param name 变量名称
         */
        public void setName(String name) {
            this.name = name;
        }

        /**
         * 获取变量的功能描述。
         *
         * @return 描述文本
         */
        public String getDescription() {
            return description;
        }

        /**
         * 设置变量的功能描述。
         *
         * @param description 描述文本
         */
        public void setDescription(String description) {
            this.description = description;
        }

        /**
         * 判断该变量是否为必填项。
         *
         * @return {@code true} 表示必填，渲染时如果缺失会抛出异常
         */
        public boolean isRequired() {
            return required;
        }

        /**
         * 设置该变量是否为必填项。
         *
         * @param required {@code true} 表示必填
         */
        public void setRequired(boolean required) {
            this.required = required;
        }
    }
}
