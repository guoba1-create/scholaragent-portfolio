package com.team.rag.service;

import com.team.rag.config.PromptProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 模板服务（Prompt Template Service）。
 *
 * <p>本服务是 RAG 系统中 Prompt 管理的核心组件，提供统一的模板加载、渲染和访问能力。
 * 它基于 {@link PromptProperties} 从 {@code prompts.yml} 中加载 15+ 个 Prompt 模板
 * （包括意图识别、对话生成、CRAG、查询改写等），并提供变量占位符替换功能。
 *
 * <p>核心功能：
 * <ul>
 *   <li><b>模板渲染</b>：通过 {@link #render(String, Map)} 将模板中的 {@code {{variable}}}
 *       占位符替换为实际值，支持必填变量校验</li>
 *   <li><b>分层消息获取</b>：通过 {@link #getSystemMessage(String)} 和 {@link #getUserMessage(String)}
 *       分别获取系统消息和用户消息，适用于 LangChain4j 的 AiServices 场景</li>
 *   <li><b>响应管理</b>：通过 {@link #getResponse(String, String)} 获取预定义的固定回复</li>
 *   <li><b>工具方法</b>：{@link #vars(Object...)} 提供便捷的变量 Map 构建能力</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // 方式一：使用 Map 构建变量
 * String prompt = promptTemplateService.render("intent-recognition",
 *     Map.of("query", "用户问题"));
 *
 * // 方式二：使用 vars() 工具方法构建变量
 * String prompt = promptTemplateService.render("intent-recognition",
 *     PromptTemplateService.vars("query", "用户问题", "context", "上下文"));
 *
 * // 获取系统消息（用于 LangChain4j @SystemMessage）
 * String systemMessage = promptTemplateService.getSystemMessage("general-chat");
 *
 * // 获取预定义响应
 * String fallback = promptTemplateService.getResponse("crag-responses", "irrelevant");
 * </pre>
 *
 * @see PromptProperties   Prompt 配置属性，负责从 YAML 加载模板数据
 * @see PromptProperties.PromptConfig   单个 Prompt 模板的配置详情
 */
@Service
public class PromptTemplateService {

    /** 日志记录器，用于记录模板加载、渲染和异常信息 */
    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    /** Prompt 配置属性，通过构造器注入，包含所有模板的配置数据 */
    private final PromptProperties promptProperties;

    /**
     * 变量占位符的正则表达式模式。
     * <p>匹配格式为 {@code {{variableName}}} 的占位符，其中 {@code variableName}
     * 由字母、数字和下划线组成（{@code \w+}）。
     * <p>捕获组 1 为变量名（不含花括号）。
     */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    /**
     * 构造方法，通过 Spring 依赖注入 PromptProperties。
     * <p>初始化时记录已加载的模板数量到日志，便于排查配置问题。
     *
     * @param promptProperties Prompt 配置属性对象，由 Spring 自动注入
     */
    public PromptTemplateService(PromptProperties promptProperties) {
        this.promptProperties = promptProperties;
        // 记录初始化日志，输出已加载的模板数量（configs 为 null 时显示 0）
        log.info("PromptTemplateService 初始化完成 | 已加载 {} 个模板",
                promptProperties.getConfigs() != null ? promptProperties.getConfigs().size() : 0);
    }

    /**
     * 渲染 Prompt 模板
     *
     * <p>
     * 将模板中的 {{variable}} 占位符替换为实际值。
     * 支持必填校验和默认值处理。
     *
     * @param templateName 模板名称
     * @param variables    变量映射
     * @return 渲染后的 Prompt 字符串
     * @throws IllegalArgumentException 如果模板不存在或必填变量缺失
     */
    public String render(String templateName, Map<String, String> variables) {
        // 第一步：根据名称查找模板配置，不存在则抛出异常
        PromptProperties.PromptConfig config = promptProperties.getPrompt(templateName);
        if (config == null) {
            throw new IllegalArgumentException("Prompt template not found: " + templateName);
        }

        // 第二步：获取完整模板文本（自动处理 template / systemMessage+userMessage 两种模式）
        String template = config.getFullTemplate();
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("Prompt template is empty: " + templateName);
        }

        // 第三步：校验必填变量是否都已提供
        validateRequiredVariables(config, variables);

        // 第四步：执行变量占位符替换并返回渲染结果
        return replaceVariables(template, variables);
    }

    /**
     * 渲染 Prompt 模板（单个变量）
     *
     * @param templateName  模板名称
     * @param variableName  变量名
     * @param variableValue 变量值
     * @return 渲染后的 Prompt 字符串
     */
    public String render(String templateName, String variableName, String variableValue) {
        // 将单个变量包装为 Map，委托给主 render 方法处理
        return render(templateName, Map.of(variableName, variableValue));
    }

    /**
     * 获取系统消息（用于 LangChain4j @SystemMessage）
     *
     * <p>
     * 返回配置中的 systemMessage 字段，不进行变量替换。
     * 适用于 LangChain4j 的 AiServices 自动注入场景。
     *
     * @param templateName 模板名称
     * @return 系统消息字符串
     */
    public String getSystemMessage(String templateName) {
        // 根据名称查找模板配置
        PromptProperties.PromptConfig config = promptProperties.getPrompt(templateName);
        if (config == null) {
            // 模板不存在时记录警告日志并返回空字符串（不抛异常，保证调用方的健壮性）
            log.warn("Prompt template not found: {}, returning empty string", templateName);
            return "";
        }
        // 返回系统消息，null 安全处理
        return config.getSystemMessage() != null ? config.getSystemMessage() : "";
    }

    /**
     * 获取用户消息模板
     *
     * @param templateName 模板名称
     * @return 用户消息模板字符串
     */
    public String getUserMessage(String templateName) {
        // 根据名称查找模板配置
        PromptProperties.PromptConfig config = promptProperties.getPrompt(templateName);
        if (config == null) {
            return ""; // 模板不存在时返回空字符串
        }
        // 返回用户消息模板，null 安全处理
        return config.getUserMessage() != null ? config.getUserMessage() : "";
    }

    /**
     * 获取简单模板（无变量替换）
     *
     * @param templateName 模板名称
     * @return 模板字符串
     */
    public String getTemplate(String templateName) {
        // 根据名称查找模板配置
        PromptProperties.PromptConfig config = promptProperties.getPrompt(templateName);
        if (config == null) {
            log.warn("Prompt template not found: {}", templateName);
            return ""; // 模板不存在时记录警告并返回空字符串
        }
        // 返回原始模板文本（不含变量替换），null 安全处理
        return config.getTemplate() != null ? config.getTemplate() : "";
    }

    /**
     * 获取配置中的特定响应
     *
     * <p>
     * 用于获取 responses 映射中的特定响应，如 crag-responses 中的 irrelevant 或 partial-prefix
     *
     * @param templateName 模板名称
     * @param responseKey  响应键名
     * @return 响应字符串
     */
    public String getResponse(String templateName, String responseKey) {
        // 根据名称查找模板配置
        PromptProperties.PromptConfig config = promptProperties.getPrompt(templateName);
        if (config == null) {
            log.warn("Prompt template not found: {}", templateName);
            return ""; // 模板不存在时返回空字符串
        }
        // 获取该模板的响应映射
        Map<String, String> responses = config.getResponses();
        if (responses == null || !responses.containsKey(responseKey)) {
            // 响应映射为空或指定键不存在时记录警告
            log.warn("Response key '{}' not found in template '{}'", responseKey, templateName);
            return "";
        }
        return responses.get(responseKey); // 返回对应的预定义响应文本
    }

    /**
     * 检查模板是否存在
     *
     * @param templateName 模板名称
     * @return true 如果模板存在
     */
    public boolean exists(String templateName) {
        // 通过 getPrompt 返回值是否为 null 判断模板是否存在
        return promptProperties.getPrompt(templateName) != null;
    }

    /**
     * 获取模板描述
     *
     * @param templateName 模板名称
     * @return 模板描述
     */
    public String getDescription(String templateName) {
        // 查找模板配置，存在则返回描述，否则返回空字符串
        PromptProperties.PromptConfig config = promptProperties.getPrompt(templateName);
        return config != null ? config.getDescription() : "";
    }

    /**
     * 获取模板版本
     *
     * @param templateName 模板名称
     * @return 模板版本
     */
    public String getVersion(String templateName) {
        // 查找模板配置，存在则返回版本号，否则返回空字符串
        PromptProperties.PromptConfig config = promptProperties.getPrompt(templateName);
        return config != null ? config.getVersion() : "";
    }

    /**
     * 校验模板的必填变量是否都已提供。
     * <p>遍历模板配置中声明的变量列表，检查标记为 {@code required=true} 的变量
     * 是否在传入的 {@code variables} 映射中存在且非空。
     * 如果有必填变量缺失，抛出 {@link IllegalArgumentException}。
     *
     * @param config    模板配置对象，包含变量声明信息
     * @param variables 调用方传入的变量映射
     * @throws IllegalArgumentException 如果必填变量缺失或为空
     */
    private void validateRequiredVariables(PromptProperties.PromptConfig config, Map<String, String> variables) {
        // 模板未声明任何变量时直接跳过校验
        if (config.getVariables() == null) {
            return;
        }

        // 遍历所有声明的变量，逐一检查必填项
        for (PromptProperties.VariableConfig varConfig : config.getVariables()) {
            if (varConfig.isRequired()) {
                // 从传入的变量映射中获取该变量的值
                String value = variables.get(varConfig.getName());
                if (value == null || value.isBlank()) {
                    // 必填变量缺失，抛出包含详细信息的异常
                    throw new IllegalArgumentException(
                            String.format("Required variable '%s' is missing for template '%s'",
                                    varConfig.getName(), config.getName()));
                }
            }
        }
    }

    /**
     * 将模板中的 {@code {{variable}}} 占位符替换为实际值。
     * <p>使用正则表达式 {@link #VARIABLE_PATTERN} 匹配所有占位符，
     * 然后从 {@code variables} 映射中查找对应的值进行替换。
     * 未匹配到的变量默认替换为空字符串。
     *
     * <p>使用 {@link Matcher#quoteReplacement} 对替换值进行转义，
     * 防止替换值中包含 {@code $} 或 {@code \} 等正则特殊字符导致异常。
     *
     * @param template  包含占位符的模板文本
     * @param variables 变量名到变量值的映射
     * @return 替换后的文本
     */
    private String replaceVariables(String template, Map<String, String> variables) {
        // 无变量时直接返回原始模板
        if (variables == null || variables.isEmpty()) {
            return template;
        }

        StringBuffer result = new StringBuffer(); // 使用 StringBuffer 拼接替换结果
        Matcher matcher = VARIABLE_PATTERN.matcher(template); // 在模板文本上创建匹配器

        // 逐个查找并替换所有 {{variable}} 占位符
        while (matcher.find()) {
            String variableName = matcher.group(1); // 捕获组 1 为变量名（不含花括号）
            String replacement = variables.getOrDefault(variableName, ""); // 未找到时用空字符串兜底
            // quoteReplacement 防止替换值中的 $ 和 \ 被正则引擎解释
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result); // 追加最后一个匹配之后的剩余文本

        return result.toString();
    }

    /**
     * 便捷工具方法：通过键值对参数快速构建变量 Map。
     * <p>接受可变长度的参数列表，按奇偶位置依次取键和值，
     * 所有键值都会通过 {@code String.valueOf()} 转换为字符串。
     *
     * <p>使用示例：
     * <pre>
     * Map&lt;String, String&gt; variables = PromptTemplateService.vars(
     *     "query", "用户问题",
     *     "context", "相关上下文",
     *     "maxTokens", 1000  // 非字符串类型也会自动转换
     * );
     * </pre>
     *
     * @param keyValues 键值对数组，格式为 [key1, value1, key2, value2, ...]
     * @return 包含所有键值对的 Map；如果参数个数为奇数，最后一个键会被忽略
     */
    public static Map<String, String> vars(Object... keyValues) {
        Map<String, String> map = new HashMap<>();
        // 步长为 2，每次取一对键值
        for (int i = 0; i < keyValues.length; i += 2) {
            // 确保值存在（防止奇数个参数导致数组越界）
            if (i + 1 < keyValues.length) {
                // 将键和值都转为字符串后放入 Map
                map.put(String.valueOf(keyValues[i]), String.valueOf(keyValues[i + 1]));
            }
        }
        return map;
    }
}
