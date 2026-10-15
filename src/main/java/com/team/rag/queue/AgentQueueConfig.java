package com.team.rag.queue;

import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Kafka listener infrastructure for document ingestion and Telegram
 * notifications. Java-to-Python workflow execution uses gRPC, not Kafka.
 */
@Configuration
@EnableKafka
public class AgentQueueConfig {
}
