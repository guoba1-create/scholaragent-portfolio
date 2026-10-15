package com.team.rag.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA（单页应用）前端控制器 —— 处理 Vue 3 前端的路由转发。
 *
 * <p>由于 SPA 应用使用前端路由（HTML5 History Mode），
 * 浏览器直接访问 /app 或 /app/ 时需要由后端将请求转发到
 * 前端的入口文件 index.html，由 Vue Router 接管后续路由。</p>
 *
 * <p>注意：本控制器使用 @Controller 而非 @RestController，
 * 返回的是视图转发路径而非 JSON 响应。</p>
 *
 * <h3>REST 端点列表</h3>
 * <table border="1">
 *   <tr><th>HTTP 方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/app</td><td>转发到 /app/index.html</td></tr>
 *   <tr><td>GET</td><td>/app/</td><td>转发到 /app/index.html</td></tr>
 * </table>
 */
@Controller
public class SpaController {

    /**
     * 前端入口路由转发 —— 将 /app 和 /app/ 请求转发到 index.html。
     *
     * <p>Spring Boot 配合静态资源处理，/app/index.html 会映射到
     * classpath:/static/app/index.html（Vue 3 构建产物的入口文件）。</p>
     *
     * @return forward 指令，由 Spring 内部转发到 /app/index.html
     */
    @GetMapping({"/app", "/app/"})
    public String agentConsole() {
        return "forward:/app/index.html";
    }
}
