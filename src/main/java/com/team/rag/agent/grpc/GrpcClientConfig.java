package com.team.rag.agent.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

/**
 * gRPC 客户端配置类。
 *
 * <p>创建并管理连接 Python Agent 运行时的 gRPC Channel 和 Stub：
 * <ul>
 *   <li>{@link ManagedChannel} — gRPC 连接通道，配置 keep-alive（30s）和最大入站消息（32MB）</li>
 *   <li>{@code AgentWorkflowBlockingStub} — 同步调用 stub，用于 control 等一元 RPC</li>
 *   <li>{@code AgentWorkflowStub} — 异步调用 stub，用于 runWorkflow 等服务端流式 RPC</li>
 * </ul>
 *
 * <p>连接目标由 {@code agent.grpc.python.host} 和 {@code agent.grpc.python.port} 配置项控制，
 * 应用关闭时通过 {@link PreDestroy} 优雅关闭 channel。
 */
@Configuration
public class GrpcClientConfig {

    @Value("${agent.grpc.python.host:localhost}")
    private String pythonHost;

    @Value("${agent.grpc.python.port:50051}")
    private int pythonPort;

    private ManagedChannel channel;

    /** 创建 gRPC 连接通道 Bean，配置 keep-alive 和最大消息大小。 */
    @Bean
    public ManagedChannel agentWorkflowChannel() {
        channel = ManagedChannelBuilder
                .forAddress(pythonHost, pythonPort)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .maxInboundMessageSize(32 * 1024 * 1024)
                .build();
        return channel;
    }

    /** 创建同步 gRPC Stub（用于 control 等一元调用）。 */
    @Bean
    public AgentWorkflowGrpc.AgentWorkflowBlockingStub blockingStub(ManagedChannel channel) {
        return AgentWorkflowGrpc.newBlockingStub(channel);
    }

    /** 创建异步 gRPC Stub（用于 runWorkflow 等服务端流式调用）。 */
    @Bean
    public AgentWorkflowGrpc.AgentWorkflowStub asyncStub(ManagedChannel channel) {
        return AgentWorkflowGrpc.newStub(channel);
    }

    /** 应用关闭时优雅终止 gRPC 通道（最多等待 5 秒）。 */
    @PreDestroy
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                channel.shutdownNow();
            }
        }
    }
}
