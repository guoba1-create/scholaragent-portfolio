package com.team.rag.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器
 *
 * <p>
 * 使用 @RestControllerAdvice 拦截所有 Controller 层抛出的异常，
 * 将异常转换为统一的 JSON 错误响应格式，避免将堆栈信息暴露给前端。
 *
 * <p>
 * 统一响应格式：
 * <pre>
 * {
 *   "error": "错误类型标识",
 *   "message": "可读的错误描述",
 *   "status": 400
 * }
 * </pre>
 *
 * <p>
 * 处理的异常类型（按优先级排序）：
 * <ol>
 * <li>ResponseStatusException - 带 HTTP 状态码的业务异常</li>
 * <li>MissingServletRequestParameterException - 缺少必需的请求参数</li>
 * <li>IllegalArgumentException - 非法参数异常</li>
 * <li>Exception - 所有未捕获的异常（兜底处理）</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理 ResponseStatusException
     *
     * <p>
     * 处理显式抛出的带 HTTP 状态码异常（如 404 Not Found、403 Forbidden），
     * 保留原始状态码返回给客户端。
     *
     * @param e ResponseStatusException 异常实例
     * @return 包含错误信息的 ResponseEntity，状态码与异常中指定的一致
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e) {
        log.warn("ResponseStatusException | status={}, reason={}", e.getStatusCode(), e.getReason());
        return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                "error", e.getReason() != null ? e.getReason() : "error",
                "status", e.getStatusCode().value()
        ));
    }

    /**
     * 处理缺少请求参数异常
     *
     * <p>
     * 当客户端未传递必需的请求参数时触发，返回 400 Bad Request。
     *
     * @param e 缺少参数异常实例
     * @return 400 状态码的错误响应，包含缺失参数的名称
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("Missing parameter: {}", e.getParameterName());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "missing_parameter",
                "message", "Missing required parameter: " + e.getParameterName(),
                "status", 400
        ));
    }

    /**
     * 处理非法参数异常
     *
     * <p>
     * 当业务逻辑检测到不合法的参数值时触发，返回 400 Bad Request。
     *
     * @param e 非法参数异常实例
     * @return 400 状态码的错误响应，包含原始错误信息
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "bad_request",
                "message", e.getMessage() != null ? e.getMessage() : "Invalid request",
                "status", 400
        ));
    }

    /**
     * 兜底异常处理 —— 捕获所有未被上述处理器拦截的异常
     *
     * <p>
     * 记录完整堆栈日志（ERROR 级别），返回通用的 500 错误信息。
     * 不向客户端暴露内部实现细节，仅提示"请稍后重试"。
     *
     * @param e 未知异常实例
     * @return 500 状态码的通用错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("Unhandled exception", e);  // 记录完整堆栈，便于排查
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "internal_error");
        body.put("message", "An unexpected error occurred. Please try again later.");
        body.put("status", 500);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
