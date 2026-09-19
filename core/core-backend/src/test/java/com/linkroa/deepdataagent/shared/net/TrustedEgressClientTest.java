package com.linkroa.deepdataagent.shared.net;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TrustedEgressClient} 出网执行器单测（design D8）。
 *
 * <p>覆盖：越界目标零网络 I/O 拒绝、每跳单次解析、重定向逐跳复检（含下一跳无法解析与跳数超限）、
 * 303 降级为 GET、响应体脱敏与长度截断。本地环回假服务器 + 解析器缝使全部用例离线可跑
 * （环回经白名单开关放行，与内网自建 MCP 场景同口径）。</p>
 */
class TrustedEgressClientTest {

    private HttpServer server;
    private int port;

    /** 假服务器收到的请求记录（断言方法 / 路径 / 鉴权头 / 请求体）。 */
    private record Received(String method, String path, String authorization, String body) {
    }

    /** 假服务器路由（状态码 + Location + Content-Type + 响应体）。 */
    private record Route(int statusCode, String location, String contentType, String body) {
    }

    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        received.clear();
        routes.clear();
        server = startRecordingServer();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** 启动一台记录请求的假服务器（同一路由表与请求记录，供多源跳转用例复用）。 */
    private HttpServer startRecordingServer() throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        httpServer.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
            Route route = routes.get(exchange.getRequestURI().getPath());
            if (route == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if (route.contentType() != null) {
                exchange.getResponseHeaders().set("Content-Type", route.contentType());
            }
            if (route.location() != null) {
                exchange.getResponseHeaders().set("Location", route.location());
            }
            byte[] payload = route.body() == null ? new byte[0] : route.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(route.statusCode(), payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                exchange.getResponseBody().write(payload);
            }
            exchange.close();
        });
        httpServer.start();
        return httpServer;
    }

    @Test
    void should_rejectWithoutNetworkIo_when_send_given_targetBeyondTrustBoundary() {
        // given（私网 / 云元数据目标，白名单关闭；IP 字面量判定无需解析）
        // when
        TrustedEgressClient.Result privateTarget = TrustedEgressClient.send(
                get("http://10.0.0.5/mcp"), false, 5);
        TrustedEgressClient.Result metadataTarget = TrustedEgressClient.send(
                get("http://169.254.169.254/latest/meta-data/"), false, 5);

        // then（拒绝且零网络 I/O）
        assertTrue(privateTarget.failed());
        assertTrue(privateTarget.failureReason().contains("超出信任边界"));
        assertTrue(metadataTarget.failed());
        assertTrue(metadataTarget.failureReason().contains("超出信任边界"));
        assertTrue(received.isEmpty());
    }

    @Test
    void should_returnMaskedTruncatedResponse_when_send_given_reachableTarget() {
        // given（可预期响应：含 sk-* 形态秘密 + 超长响应体）
        routes.put("/mcp", new Route(200, null, "application/json",
                "{\"access_token\":\"sk-secret123456\"}" + "x".repeat(4096)));

        // when
        TrustedEgressClient.Result result = TrustedEgressClient.send(get(baseUri() + "/mcp"), true, 5);

        // then（诊断体脱敏 + 截断 + 跳数为 0；原文保留供上层解析）
        assertFalse(result.failed());
        assertEquals(200, result.response().statusCode());
        assertEquals("application/json", result.response().contentType());
        assertTrue(result.response().diagnosticBodyTruncated());
        assertEquals(2048, result.response().diagnosticBody().length());
        assertTrue(result.response().diagnosticBody().contains("sk-***"));
        assertFalse(result.response().diagnosticBody().contains("sk-secret123456"));
        assertTrue(result.response().body().contains("sk-secret123456"));
        assertEquals(0, result.response().redirects());
        assertEquals(URI.create(baseUri() + "/mcp"), result.response().finalUri());
    }

    @Test
    void should_resolveOncePerHop_when_send_given_redirectChain() {
        // given（两跳链路：/start → 302 → /next，解析器计数）
        routes.put("/start", new Route(302, "/next", null, null));
        routes.put("/next", new Route(200, null, "text/plain", "ok"));
        AtomicInteger resolutions = new AtomicInteger();

        // when
        TrustedEgressClient.Result result = TrustedEgressClient.send(get(baseUri() + "/start"), true, 5,
                host -> {
                    resolutions.incrementAndGet();
                    return InetAddress.getAllByName(host);
                });

        // then（每跳复检：解析 2 次、跟随 1 跳、终态 URI 为 /next）
        assertFalse(result.failed());
        assertEquals(2, resolutions.get());
        assertEquals(1, result.response().redirects());
        assertEquals(URI.create(baseUri() + "/next"), result.response().finalUri());
        assertEquals(List.of("/start", "/next"), received.stream().map(Received::path).toList());
    }

    @Test
    void should_notFollowRedirect_when_send_given_nextHopUnresolvable() {
        // given（重定向目标是无法解析的主机：下一跳复检失败即中止，不再跟随）
        routes.put("/start", new Route(302, "http://broken.invalid/next", null, null));

        // when
        TrustedEgressClient.Result result = TrustedEgressClient.send(get(baseUri() + "/start"), true, 5,
                host -> {
                    if (host.endsWith(".invalid")) {
                        throw new java.net.UnknownHostException(host);
                    }
                    return InetAddress.getAllByName(host);
                });

        // then（仅首跳被请求，重定向未被跟随）
        assertTrue(result.failed());
        assertTrue(result.failureReason().contains("无法解析"));
        assertEquals(List.of("/start"), received.stream().map(Received::path).toList());
    }

    @Test
    void should_fail_when_send_given_redirectHopsExceedLimit() {
        // given（自环重定向 + 跳数上限 2）
        routes.put("/loop", new Route(302, "/loop", null, null));

        // when
        TrustedEgressClient.Result result = TrustedEgressClient.send(get(baseUri() + "/loop"), true, 2);

        // then（跳数超限即失败，不无限跟随）
        assertTrue(result.failed());
        assertTrue(result.failureReason().contains("重定向跳数超出上限"));
        assertEquals(3, received.size());
    }

    @Test
    void should_downgradeToGetWithoutBody_when_send_given_303RedirectFromPost() {
        // given（303 语义：按浏览器约定降级为 GET 且不带请求体；同源跳转保留鉴权头）
        routes.put("/post", new Route(303, "/result", null, null));
        routes.put("/result", new Route(200, null, "application/json", "{}"));
        TrustedEgressClient.Request request = new TrustedEgressClient.Request("POST",
                URI.create(baseUri() + "/post"), Map.of("Authorization", "Bearer plain-token"),
                HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\"}"));

        // when
        TrustedEgressClient.Result result = TrustedEgressClient.send(request, true, 5);

        // then
        assertFalse(result.failed());
        assertEquals(1, result.response().redirects());
        assertEquals("POST", received.get(0).method());
        assertEquals("Bearer plain-token", received.get(0).authorization());
        assertEquals("GET", received.get(1).method());
        assertEquals("", received.get(1).body());
        assertEquals("Bearer plain-token", received.get(1).authorization());
    }

    @Test
    void should_dropAuthorization_when_send_given_crossOriginRedirect() throws IOException {
        // given（跨源跳转：目标端口不同即跨源，即使同一主机也不得顺带鉴权头）
        HttpServer other = startRecordingServer();
        int otherPort = other.getAddress().getPort();
        routes.put("/cross", new Route(302, "http://127.0.0.1:" + otherPort + "/result", null, null));
        routes.put("/result", new Route(200, null, "application/json", "{}"));
        TrustedEgressClient.Request request = new TrustedEgressClient.Request("GET",
                URI.create(baseUri() + "/cross"), Map.of("Authorization", "Bearer plain-token"),
                HttpRequest.BodyPublishers.noBody());

        // when
        TrustedEgressClient.Result result;
        try {
            result = TrustedEgressClient.send(request, true, 5);
        } finally {
            other.stop(0);
        }

        // then
        assertFalse(result.failed());
        assertEquals("Bearer plain-token", received.get(0).authorization());
        assertNull(received.get(1).authorization());
    }

    /** 构造 GET 请求（无请求体）。 */
    private static TrustedEgressClient.Request get(String uri) {
        return new TrustedEgressClient.Request("GET", URI.create(uri), Map.of(),
                HttpRequest.BodyPublishers.noBody());
    }

    private String baseUri() {
        return "http://127.0.0.1:" + port;
    }
}