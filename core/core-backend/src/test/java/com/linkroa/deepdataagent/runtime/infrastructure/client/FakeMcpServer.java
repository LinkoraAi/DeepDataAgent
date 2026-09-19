package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * 最小假 MCP 服务器（streamable-http 单端点 JSON-RPC 2.0），供装配期 discovery 的端到端断言使用。
 * <p>只实现 {@code McpServerRegistrar} 注册路径真实需要的三个往返：{@code initialize} 握手、
 * {@code notifications/initialized} 通知（202 空体）、{@code tools/list} 枚举，以及一次工具调用
 * {@code tools/call}（回固定文本，用于断言改名后仍以服务端原始工具名发起 RPC）；GET 一律 405
 * （不提供服务端推送流）。协议版本固定回 {@code 2024-11-05}——客户端默认仅支持该版本，
 * 回其它版本会以 “Unsupported protocol version” 失败。</p>
 * <p>全部请求（含方法与请求头）被记录，用于断言「握手与枚举都携带注入的鉴权头」；
 * 可切换为「接受连接但不回响应」以断言超时收敛，切换 {@code tools/list} 失败 / 空集以断言
 * 枚举降级，切换「要求 Bearer」以断言无凭证命中时的未鉴权拒绝。</p>
 */
final class FakeMcpServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单次请求记录（方法 + 请求头 + 原始报文）。 */
    record RecordedRequest(String method, Map<String, List<String>> headers, String body) {

        /** 首个同名请求头值（不区分大小写），缺失返回 null。 */
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().isEmpty() ? null : entry.getValue().get(0))
                    .findFirst()
                    .orElse(null);
        }
    }

    private final HttpServer server;
    private final List<Map<String, Object>> tools;
    private final List<RecordedRequest> recorded = new CopyOnWriteArrayList<>();

    /** 停机信号：阻塞中的「不回响应」处理器据此释放，避免测试 JVM 被挂起线程拖住。 */
    private final CountDownLatch stopped = new CountDownLatch(1);

    /** true=正常握手；false=接受连接但永不回响应（验证装配不会无限阻塞）。 */
    private volatile boolean completesHandshake = true;

    /** true={@code tools/list} 回 JSON-RPC 错误（握手成功但枚举失败）。 */
    private volatile boolean failsToolList;

    /** true={@code tools/list} 回空工具集（握手成功但枚举为空）。 */
    private volatile boolean emptyToolList;

    /** true=要求 {@code Authorization} 头；缺失时回 401（模拟需鉴权服务器拒绝无凭证连接）。 */
    private volatile boolean requiresBearer;

    FakeMcpServer(List<Map<String, Object>> tools) throws IOException {
        this.tools = List.copyOf(tools);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 阻塞式「不回响应」用例会占住处理线程，必须用多线程执行器避免请求互相饿死；
        // 线程设为守护线程，确保处理器未及释放时也不会阻止测试 JVM 退出
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-mcp-server");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/mcp", this::handle);
        server.start();
    }

    /** 服务器地址（与 {@code McpServerConfig.url} 同形，路径 {@code /mcp}）。 */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    void setCompletesHandshake(boolean completesHandshake) {
        this.completesHandshake = completesHandshake;
    }

    void setFailsToolList(boolean failsToolList) {
        this.failsToolList = failsToolList;
    }

    void setEmptyToolList(boolean emptyToolList) {
        this.emptyToolList = emptyToolList;
    }

    void setRequiresBearer(boolean requiresBearer) {
        this.requiresBearer = requiresBearer;
    }

    /** 全部已记录请求（收到即记录，含未获响应的请求）。 */
    List<RecordedRequest> requests() {
        return List.copyOf(recorded);
    }

    /** 按 JSON-RPC 方法名过滤的请求（保序）。 */
    List<RecordedRequest> requestsOf(String method) {
        return recorded.stream().filter(request -> method.equals(request.method())).toList();
    }

    /** 最近一次 {@code tools/call} 的目标工具名（未发生调用返回 null）。 */
    String lastCalledToolName() {
        List<RecordedRequest> calls = requestsOf("tools/call");
        if (calls.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(calls.get(calls.size() - 1).body())
                    .path("params").path("name").asText(null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        stopped.countDown();
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            // streamable-http 的 GET 走 SSE 服务端推送流，本假服务器不提供
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode message = MAPPER.readTree(body);
        String method = message.path("method").asText("");
        recorded.add(new RecordedRequest(method, copyHeaders(exchange), body));

        if (requiresBearer && !hasBearer(exchange)) {
            // 需鉴权服务器对无凭证连接的标准应答：401 + WWW-Authenticate（握手即失败）
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        if ("initialize".equals(method)) {
            if (!completesHandshake) {
                // 接受连接但永不回响应：客户端应在 initializationTimeout 内收敛为失败
                awaitStop();
                exchange.close();
                return;
            }
            respondJson(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + idJson(message)
                    + ",\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}},"
                    + "\"serverInfo\":{\"name\":\"fake-mcp\",\"version\":\"1.0.0\"}}}");
            return;
        }
        if (message.path("id").isMissingNode()) {
            // notifications/initialized 无 id：202 空体即为标准响应
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        if ("tools/list".equals(method)) {
            if (failsToolList) {
                respondJson(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + idJson(message)
                        + ",\"error\":{\"code\":-32603,\"message\":\"internal error\"}}");
                return;
            }
            respondJson(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + idJson(message)
                    + ",\"result\":{\"tools\":" + toolsJson() + "}}");
            return;
        }
        if ("tools/call".equals(method)) {
            respondJson(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + idJson(message)
                    + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"called\"}],"
                    + "\"isError\":false}}");
            return;
        }
        respondJson(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + idJson(message)
                + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}");
    }

    /** 原样回显请求 id（字符串 id 必须带引号，否则回包本身不是合法 JSON）。 */
    private static String idJson(JsonNode message) {
        JsonNode id = message.path("id");
        return id.isMissingNode() || id.isNull() ? "null" : id.toString();
    }

    /** 枚举结果序列化（空集开关优先）。 */
    private String toolsJson() {
        if (emptyToolList) {
            return "[]";
        }
        List<String> serialized = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            try {
                serialized.add(MAPPER.writeValueAsString(tool));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return "[" + String.join(",", serialized) + "]";
    }

    private static Map<String, List<String>> copyHeaders(HttpExchange exchange) {
        Map<String, List<String>> headers = new LinkedHashMap<>(exchange.getRequestHeaders());
        return headers;
    }

    /** 请求是否携带 {@code Authorization: Bearer ...}。 */
    private static boolean hasBearer(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        return authorization != null && authorization.startsWith("Bearer ");
    }

    private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /** 阻塞至假服务器停机——用最朴素的方式模拟「握手无响应」，且不在测试结束前拖住 JVM。 */
    private void awaitStop() {
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}