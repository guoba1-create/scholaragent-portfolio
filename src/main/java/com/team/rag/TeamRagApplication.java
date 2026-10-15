package com.team.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 团队智能 RAG 知识库与私人 AI 助手 —— 启动类
 *
 * <p>
 * Spring Boot 应用入口，负责引导整个应用程序的启动和初始化。
 *
 * <p>
 * 技术栈概览：
 * <ul>
 * <li>Spring Boot 3.2.5 - 应用框架</li>
 * <li>LangChain4j - AI 应用开发框架</li>
 * <li>DashScope (Qwen-Plus) - 大语言模型服务</li>
 * <li>Pinecone - 向量数据库（稠密 + 稀疏检索）</li>
 * <li>Redis - 对话历史持久化与缓存</li>
 * <li>Kafka - 消息队列（可选）</li>
 * </ul>
 *
 * <p>
 * 核心注解说明：
 * <ul>
 * <li>@SpringBootApplication - 组合注解，包含自动配置、组件扫描、配置类</li>
 * <li>@EnableAsync - 启用异步方法执行能力，支持 @Async 注解的方法在独立线程中运行</li>
 * </ul>
 */
@SpringBootApplication
@EnableAsync
public class TeamRagApplication {

    /**
     * 应用主入口方法
     *
     * @param args 命令行参数（可传入 Spring 配置覆盖项，如 --server.port=8081）
     */
    public static void main(String[] args) {
        SpringApplication.run(TeamRagApplication.class, args);
    }
}
