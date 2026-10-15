package com.team.rag.agent.grpc;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.team.rag.agent.runtime.WorkflowEvent;
import com.team.rag.agent.runtime.WorkflowEventBus;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Python Agent 运行时的 gRPC 客户端。
 *
 * <p>职责：
 * <ul>
 *   <li>通过 gRPC server-streaming 调用 Python 侧的 RunWorkflow，将流式事件逐条发布到 {@link WorkflowEventBus}</li>
 *   <li>识别终态事件（WORKFLOW_DONE / WORKFLOW_FAILED / AWAITING_CONFIRMATION）并回调 {@link TerminalEventCallback}</li>
 *   <li>提供 Protobuf {@link Struct} 与 Java {@link Map} 之间的双向转换工具方法</li>
 * </ul>
 *
 * <p>在系统中的位置：WorkflowRunService → AgentGrpcClient → Python gRPC Server → LangGraph Runtime
 */
@Component
public class AgentGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(AgentGrpcClient.class);

    private final AgentWorkflowGrpc.AgentWorkflowBlockingStub blockingStub;
    private final AgentWorkflowGrpc.AgentWorkflowStub asyncStub;

    public AgentGrpcClient(AgentWorkflowGrpc.AgentWorkflowBlockingStub blockingStub,
                           AgentWorkflowGrpc.AgentWorkflowStub asyncStub) {
        this.blockingStub = blockingStub;
        this.asyncStub = asyncStub;
    }

    /**
     * Run a workflow via gRPC server-streaming. Each streamed event is published to EventBus.
     * The terminal event (WORKFLOW_DONE/WORKFLOW_FAILED) triggers onTerminal callback.
     *
     * @return CompletableFuture that completes when the stream ends.
     */
    public CompletableFuture<Void> runWorkflow(
            AgentWorkflowProto.RunWorkflowRequest request,
            WorkflowEventBus eventBus,
            TerminalEventCallback onTerminal,
            int timeoutSeconds) {

        CompletableFuture<Void> future = new CompletableFuture<>();

        asyncStub
                .withDeadlineAfter(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                .runWorkflow(request, new StreamObserver<>() {
                    @Override
                    public void onNext(AgentWorkflowProto.WorkflowEventMsg msg) {
                        try {
                            String eventType = msg.getEventType();
                            boolean isTerminal = "WORKFLOW_DONE".equals(eventType)
                                    || "WORKFLOW_FAILED".equals(eventType)
                                    || "AWAITING_CONFIRMATION".equals(eventType);

                            if (isTerminal) {
                                onTerminal.onTerminal(msg);
                            } else {
                                WorkflowEvent event = toWorkflowEvent(msg);
                                eventBus.publish(event);
                            }
                        } catch (Exception e) {
                            log.error("Error processing gRPC event | workflowId={}, error={}",
                                    request.getWorkflowId(), e.getMessage(), e);
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        log.error("gRPC stream error | workflowId={}, error={}",
                                request.getWorkflowId(), t.getMessage());
                        future.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                        future.complete(null);
                    }
                });

        return future;
    }

    /**
     * Send a control command (INTERRUPT) via gRPC unary call.
     */
    public AgentWorkflowProto.ControlResponse control(AgentWorkflowProto.ControlRequest request) {
        return blockingStub
                .withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS)
                .control(request);
    }

    // ---------------------------------------------------------------
    // Protobuf <-> Java 转换
    // ---------------------------------------------------------------

    /**
     * 将 Protobuf WorkflowEventMsg 转换为 Java WorkflowEvent 对象。
     * 仅在非终态事件时调用（终态事件由 TerminalEventCallback 处理）。
     */
    public static WorkflowEvent toWorkflowEvent(AgentWorkflowProto.WorkflowEventMsg msg) {
        WorkflowEvent event = new WorkflowEvent(
                msg.getWorkflowId(),
                msg.getEventType(),
                msg.getPhase(),
                msg.getStatus(),
                msg.getSummary()
        );
        if (!msg.getEventId().isEmpty()) event.setEventId(msg.getEventId());
        if (!msg.getAgentName().isEmpty()) event.setAgentName(msg.getAgentName());
        if (!msg.getToolName().isEmpty()) event.setToolName(msg.getToolName());
        if (msg.hasPayload()) event.setPayload(structToMap(msg.getPayload()));
        if (msg.hasCreatedAt()) {
            event.setCreatedAt(java.time.Instant.ofEpochSecond(
                    msg.getCreatedAt().getSeconds(), msg.getCreatedAt().getNanos()));
        }
        return event;
    }

    /** 将 Protobuf Struct 转换为 Java Map（递归处理嵌套结构）。 */
    public static Map<String, Object> structToMap(Struct struct) {
        if (struct == null) return new LinkedHashMap<>();
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, Value> entry : struct.getFieldsMap().entrySet()) {
            map.put(entry.getKey(), valueToObject(entry.getValue()));
        }
        return map;
    }

    /** 将 Protobuf Struct 列表转换为 Java Map 列表。 */
    public static List<Map<String, Object>> structListToMapList(Iterable<Struct> structs) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Struct s : structs) {
            list.add(structToMap(s));
        }
        return list;
    }

    private static Object valueToObject(Value value) {
        return switch (value.getKindCase()) {
            case NULL_VALUE -> null;
            case NUMBER_VALUE -> value.getNumberValue();
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case STRUCT_VALUE -> structToMap(value.getStructValue());
            case LIST_VALUE -> {
                List<Object> list = new ArrayList<>();
                for (Value v : value.getListValue().getValuesList()) {
                    list.add(valueToObject(v));
                }
                yield list;
            }
            default -> null;
        };
    }

    /** 将 Java Map 转换为 Protobuf Struct（递归处理嵌套结构）。 */
    public static Struct mapToStruct(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return Struct.getDefaultInstance();
        Struct.Builder builder = Struct.newBuilder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            builder.putFields(entry.getKey(), objectToValue(entry.getValue()));
        }
        return builder.build();
    }

    private static Value objectToValue(Object obj) {
        if (obj == null) return Value.newBuilder().setNullValue(com.google.protobuf.NullValue.NULL_VALUE).build();
        if (obj instanceof String s) return Value.newBuilder().setStringValue(s).build();
        if (obj instanceof Number n) return Value.newBuilder().setNumberValue(n.doubleValue()).build();
        if (obj instanceof Boolean b) return Value.newBuilder().setBoolValue(b).build();
        if (obj instanceof Map<?, ?> map) {
            Struct.Builder sb = Struct.newBuilder();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sb.putFields(String.valueOf(e.getKey()), objectToValue(e.getValue()));
            }
            return Value.newBuilder().setStructValue(sb.build()).build();
        }
        if (obj instanceof Iterable<?> list) {
            com.google.protobuf.ListValue.Builder listBuilder = com.google.protobuf.ListValue.newBuilder();
            for (Object item : list) {
                listBuilder.addValues(objectToValue(item));
            }
            return Value.newBuilder().setListValue(listBuilder.build()).build();
        }
        return Value.newBuilder().setStringValue(String.valueOf(obj)).build();
    }

    /**
     * 终态事件回调接口。
     * 当 gRPC 流收到 WORKFLOW_DONE / WORKFLOW_FAILED / AWAITING_CONFIRMATION 事件时触发。
     */
    @FunctionalInterface
    public interface TerminalEventCallback {
        void onTerminal(AgentWorkflowProto.WorkflowEventMsg msg);
    }
}
