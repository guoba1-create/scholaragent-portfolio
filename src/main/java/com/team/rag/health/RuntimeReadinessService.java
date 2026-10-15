package com.team.rag.health;

import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Performs bounded, secret-free readiness checks for the complete Agent stack. */
@Service
public class RuntimeReadinessService {

    private static final int CONNECT_TIMEOUT_MS = 900;

    private final Environment environment;
    private final RedisConnectionFactory redisConnectionFactory;

    public RuntimeReadinessService(Environment environment,
                                   RedisConnectionFactory redisConnectionFactory) {
        this.environment = environment;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    public ReadinessReport check() {
        List<ComponentHealth> components = new ArrayList<>();
        components.add(configured("llm", true,
                property("langchain4j.community.dashscope.chat-model.api-key"),
                "DASHSCOPE_API_KEY is required for routing, generation and evaluation."));
        components.add(configured("pinecone", true, property("pinecone.api-key"),
                "PINECONE_API_KEY is required for literature retrieval."));
        components.add(internalSecurity());
        components.add(redis());

        boolean pythonEnabled = boolProperty("agent.runtime.python.enabled", true);
        components.add(pythonEnabled
                ? tcp("python-grpc", true, property("agent.grpc.python.host", "localhost"),
                        intProperty("agent.grpc.python.port", 50051), "Python LangGraph runtime")
                : ComponentHealth.disabled("python-grpc", false, "Python runtime is disabled."));

        boolean kafkaRequired = "kafka".equalsIgnoreCase(property("agent.queue.backend", "kafka"))
                || boolProperty("document.ingest.kafka.enabled", true);
        HostPort kafka = hostPort(property("spring.kafka.bootstrap-servers", "localhost:9092"), 9092);
        components.add(tcp("kafka", kafkaRequired, kafka.host(), kafka.port(), "Kafka broker"));

        boolean grobidEnabled = boolProperty("team-rag.grobid.enabled", true);
        components.add(grobidEnabled
                ? urlSocket("grobid", false, property("team-rag.grobid.base-url", "http://localhost:8070"),
                        "Optional academic PDF parser; PDFBox fallback remains available.")
                : ComponentHealth.disabled("grobid", false, "GROBID is disabled; PDFBox fallback is active."));
        components.add(urlSocket("searxng", false,
                property("agent.search.searxng-url", "http://localhost:8888"),
                "Optional external web search used only by External Scholar Scout."));

        boolean ready = components.stream().noneMatch(component -> component.required && !component.isUp());
        String status = ready
                ? (components.stream().anyMatch(component -> !component.isUp() && !component.required)
                    ? "DEGRADED" : "READY")
                : "NOT_READY";
        return new ReadinessReport(ready, status, Instant.now(), components);
    }

    private ComponentHealth internalSecurity() {
        String runtimeToken = property("agent.internal.token");
        String identityMode = property("app.identity.mode", "dev");
        String gatewayToken = property("app.identity.gateway-token");
        if (runtimeToken == null || runtimeToken.isBlank()) {
            return ComponentHealth.down("runtime-auth", true, "AGENT_INTERNAL_TOKEN is not configured.");
        }
        if ("gateway".equalsIgnoreCase(identityMode) && (gatewayToken == null || gatewayToken.isBlank())) {
            return ComponentHealth.down("runtime-auth", true,
                    "Gateway identity mode requires APP_IDENTITY_GATEWAY_TOKEN.");
        }
        if ("gateway".equalsIgnoreCase(identityMode)
                && "dev-agent-runtime-token-change-me".equals(runtimeToken)) {
            return ComponentHealth.down("runtime-auth", true,
                    "Production gateway mode cannot use the development runtime token.");
        }
        return ComponentHealth.up("runtime-auth", true,
                "Internal tool/MCP authentication and request identity policy are configured.");
    }

    private ComponentHealth redis() {
        boolean required = boolProperty("app.readiness.redis-required", true);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            String pong = connection.ping();
            if (pong != null && "PONG".equalsIgnoreCase(pong)) {
                return ComponentHealth.up("redis", required, "Redis persistence is reachable.");
            }
            return ComponentHealth.down("redis", required, "Redis ping returned an unexpected response.");
        } catch (Exception exception) {
            return ComponentHealth.down("redis", required, "Redis is unreachable: " + safe(exception.getMessage()));
        }
    }

    private ComponentHealth configured(String name, boolean required, String value, String hint) {
        return value == null || value.isBlank()
                ? ComponentHealth.down(name, required, hint)
                : ComponentHealth.up(name, required, "Configured.");
    }

    private ComponentHealth urlSocket(String name, boolean required, String value, String detail) {
        try {
            URI uri = URI.create(value);
            int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            return tcp(name, required, uri.getHost(), port, detail);
        } catch (Exception exception) {
            return ComponentHealth.down(name, required, "Invalid service URL configuration.");
        }
    }

    private ComponentHealth tcp(String name, boolean required, String host, int port, String detail) {
        if (host == null || host.isBlank()) {
            return ComponentHealth.down(name, required, "Host is not configured.");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return ComponentHealth.up(name, required, detail + " is reachable at " + host + ":" + port + ".");
        } catch (Exception exception) {
            return ComponentHealth.down(name, required,
                    detail + " is unreachable at " + host + ":" + port + ".");
        }
    }

    private HostPort hostPort(String value, int fallbackPort) {
        String first = value == null ? "" : value.split(",")[0].trim();
        if (first.contains(":")) {
            String[] parts = first.split(":", 2);
            try {
                return new HostPort(parts[0], Integer.parseInt(parts[1]));
            } catch (NumberFormatException ignored) {
                return new HostPort(parts[0], fallbackPort);
            }
        }
        return new HostPort(first.isBlank() ? "localhost" : first, fallbackPort);
    }

    private String property(String key) {
        return environment.getProperty(key);
    }

    private String property(String key, String fallback) {
        return environment.getProperty(key, fallback);
    }

    private boolean boolProperty(String key, boolean fallback) {
        return environment.getProperty(key, Boolean.class, fallback);
    }

    private int intProperty(String key, int fallback) {
        return environment.getProperty(key, Integer.class, fallback);
    }

    private String safe(String message) {
        return message == null || message.isBlank() ? "unknown error" : message;
    }

    private record HostPort(String host, int port) {
    }

    public record ReadinessReport(boolean ready, String status, Instant checkedAt,
                                  List<ComponentHealth> components) {
        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ready", ready);
            result.put("status", status);
            result.put("checkedAt", checkedAt);
            result.put("components", components);
            return result;
        }
    }

    public record ComponentHealth(String name, String status, boolean required, String detail) {
        static ComponentHealth up(String name, boolean required, String detail) {
            return new ComponentHealth(name, "UP", required, detail);
        }

        static ComponentHealth down(String name, boolean required, String detail) {
            return new ComponentHealth(name, "DOWN", required, detail);
        }

        static ComponentHealth disabled(String name, boolean required, String detail) {
            return new ComponentHealth(name, "DISABLED", required, detail);
        }

        boolean isUp() {
            return "UP".equals(status) || "DISABLED".equals(status);
        }
    }
}
