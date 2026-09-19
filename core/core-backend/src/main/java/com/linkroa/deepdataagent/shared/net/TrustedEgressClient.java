package com.linkroa.deepdataagent.shared.net;

import com.linkroa.deepdataagent.shared.security.SecretMasker;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 出网请求执行器（共享技术能力，不承载业务逻辑，见 design D8）。
 *
 * <p>平台主动发起的外部 HTTP 请求（凭证校验探测、OAuth discovery / 刷新调用等）统一经本类出网：
 * 每跳目标先过 {@link EgressTrustPolicy} 的信任边界判定（单次解析、全部地址逐地址校验），
 * 判定失败<b>不发出任何网络请求</b>；响应原文按 {@link Response#body()} 返回，
 * 供上层解析必需字段，对外诊断一律取 {@link Response#diagnosticBody()}（已脱敏 + 已截断）。</p>
 *
 * <p><b>重定向</b>：不交由 JDK 自动跟随（自动跟随时绕过信任边界），改为手动逐跳处理——
 * 每一跳重新走同一次解析 + 边界复检，跳数超过 {@code maxRedirects} 即失败；
 * 303（以及 301/302 收到 POST）按浏览器语义降级为 GET 且不带请求体；跨源跳转（协议 / 主机 / 端口
 * 任一不同）不带 {@code Authorization} 头，避免把凭证明文送往重定向目标。</p>
 *
 * <p><b>残余竞态（记录在案，口径已对齐）</b>：本类以「判定阶段单次解析 + 该次解析的全部地址逐个过边界 +
 * 每跳复检」收敛 SSRF 面，<b>而非</b>「以已校验 IP 建连」——JDK {@code HttpClient} 不提供 DNS 注入缝，
 * 把 URI 改写为已校验的 IP 字面量会破坏 TLS SNI 与虚拟主机路由（{@code Host} 属受限头，默认不可显式设置），
 * 换来的是可用性回归而非安全增量。故建连仍由 JDK 以原始主机名发起、JDK 内部另有一次解析，判定与建连之间
 * 存在一个不可消除的窗口：明文 HTTP 目标的内网地址已在判定阶段拒绝（改指向后仍是内网字面量，会被下一跳
 * 或下一次判定拦截），HTTPS 目标另有证书域名校验兜底（域名被改指向内网时证书不匹配即握手失败）。</p>
 */
public final class TrustedEgressClient {

    private static final Logger log = LoggerFactory.getLogger(TrustedEgressClient.class);

    /** 连接超时 */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /** 整个请求（含响应）超时 */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    /** 诊断回传的响应体字符上限（超出截断并置 {@code bodyTruncated}） */
    private static final int MAX_BODY_CHARS = 2048;
    /** 参与逐跳处理的重定向状态码 */
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);

    /**
     * 复用同一客户端：{@code HttpClient} 线程安全，每次新建都会各自拉起选择器线程与连接池
     * （连接池又按 host:port 复用，语义无差异），属纯开销；重定向恒由本类手动逐跳处理。
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private TrustedEgressClient() {
    }

    /**
     * 出网请求（方法 + 目标 + 头 + 体；头中不得含受限头如 {@code Host}）。
     */
    public record Request(String method, URI uri, Map<String, String> headers,
                          HttpRequest.BodyPublisher body) {

        public Request {
            if (StringUtils.isBlank(method)) {
                throw new IllegalArgumentException("请求方法不能为空");
            }
            if (uri == null) {
                throw new IllegalArgumentException("请求目标不能为空");
            }
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? HttpRequest.BodyPublishers.noBody() : body;
        }
    }

    /**
     * 出网响应（报文已被信任边界判定把关）。
     *
     * <p><b>原文与诊断分离</b>：{@link #body()} 为响应<b>原文</b>，仅供上层解析必需字段
     * （如刷新返回的令牌），MUST NOT 直接落日志或进入响应；对外诊断一律用
     * {@link #diagnosticBody()} + {@link #diagnosticBodyTruncated()}（已脱敏 + 已截断）。</p>
     *
     * <p>两个诊断访问器共享同一份<b>懒计算</b>结果（脱敏是一次正则遍历，两者成对调用，
     * 重复计算无意义）。</p>
     */
    public static final class Response {

        private final int statusCode;
        private final String contentType;
        private final String body;
        private final URI finalUri;
        private final int redirects;
        /** 诊断视图（脱敏 + 截断）：与 {@link #body()} 分离，仅诊断入口产出。 */
        private volatile Diagnostic diagnostic;

        /**
         * @param statusCode  HTTP 状态码
         * @param contentType 响应 Content-Type（缺失为 null）
         * @param body        响应原文（缺失为 null）
         * @param finalUri    最终跳目标（未跟随重定向时即原始目标）
         * @param redirects   实际跟随的跳数
         */
        Response(int statusCode, String contentType, String body, URI finalUri, int redirects) {
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.body = body;
            this.finalUri = finalUri;
            this.redirects = redirects;
        }

        /** HTTP 状态码。 */
        public int statusCode() {
            return statusCode;
        }

        /** 响应 Content-Type（缺失为 null）。 */
        public String contentType() {
            return contentType;
        }

        /** 响应原文（缺失为 null）。 */
        public String body() {
            return body;
        }

        /** 最终跳目标（未跟随重定向时即原始目标）。 */
        public URI finalUri() {
            return finalUri;
        }

        /** 实际跟随的跳数。 */
        public int redirects() {
            return redirects;
        }

        /**
         * 诊断用响应体：形态脱敏（{@link SecretMasker#maskSecrets}）+ 字符上限截断。
         */
        public String diagnosticBody() {
            return diagnostic().body();
        }

        /**
         * 诊断用响应体是否因超长被截断。
         */
        public boolean diagnosticBodyTruncated() {
            return diagnostic().truncated();
        }

        /** 诊断视图（懒计算一次；并发下最坏重复计算一次，结果恒定）。 */
        private Diagnostic diagnostic() {
            Diagnostic current = diagnostic;
            if (current == null) {
                String masked = SecretMasker.maskSecrets(body);
                boolean truncated = masked != null && masked.length() > MAX_BODY_CHARS;
                current = new Diagnostic(truncated ? masked.substring(0, MAX_BODY_CHARS) : masked, truncated);
                diagnostic = current;
            }
            return current;
        }

        /** 诊断视图载体（单引用一次性发布，避免两个访问器读到不一致的组合）。 */
        private record Diagnostic(String body, boolean truncated) {
        }
    }

    /**
     * 出网结果：{@link #response} 非空为收到响应；为空表示未收到任何响应
     * （超出信任边界、目标无法解析、连接错误或超时），原因见 {@link #failureReason}。
     */
    public record Result(Response response, String failureReason) {

        /** 是否未收到任何响应。 */
        public boolean failed() {
            return response == null;
        }

        static Result of(Response response) {
            return new Result(response, null);
        }

        static Result failure(String reason) {
            return new Result(null, reason);
        }
    }

    /**
     * 发起出网请求（生产入口）。
     *
     * @param request             出网请求
     * @param allowPrivateNetwork 白名单开关（{@code app.egress.allow-private-network}）
     * @param maxRedirects        重定向最大跳数（逐跳复检）
     * @return 响应或失败原因（绝不抛异常，越界 / 不可达一律以失败结果返回，供上层判定）
     */
    public static Result send(Request request, boolean allowPrivateNetwork, int maxRedirects) {
        return send(request, allowPrivateNetwork, maxRedirects, InetAddress::getAllByName);
    }

    /**
     * 发起出网请求（自定义解析器入口，供离线断言单次解析与逐跳复检）。
     *
     * @param request             出网请求
     * @param allowPrivateNetwork 白名单开关
     * @param maxRedirects        重定向最大跳数
     * @param resolver            域名解析器（每跳调用一次）
     * @return 响应或失败原因
     */
    static Result send(Request request, boolean allowPrivateNetwork, int maxRedirects,
                       EgressTrustPolicy.HostResolver resolver) {
        URI origin = request.uri();
        URI current = origin;
        String method = request.method();
        HttpRequest.BodyPublisher body = request.body();
        Map<String, String> headers = new LinkedHashMap<>(request.headers());
        for (int redirects = 0; redirects <= maxRedirects; redirects++) {
            try {
                // 每跳独立复检：单次解析 + 全部地址过边界；任一越界即拒绝（不发出任何请求）。
                // 该解析结果只服务判定本身——建连仍以原始主机名发起（残余窗口见类 javadoc）
                EgressTrustPolicy.resolveTrusted(current.getHost(), allowPrivateNetwork, resolver);
            } catch (IllegalStateException e) {
                log.debug("出网目标超出信任边界或无法解析，已拒绝: uri={}, reason={}", current, e.getMessage());
                return Result.failure(e.getMessage());
            }
            HttpResponse<byte[]> httpResponse;
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(current)
                        .timeout(REQUEST_TIMEOUT)
                        .method(method, body);
                headers.forEach(builder::setHeader);
                httpResponse = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (Exception e) {
                log.debug("出网请求未收到响应: uri={}, reason={}", current, e.getMessage());
                return Result.failure("目标不可达或请求超时: " + e.getMessage());
            }
            int statusCode = httpResponse.statusCode();
            String location = httpResponse.headers().firstValue("location").orElse(null);
            if (REDIRECT_STATUSES.contains(statusCode) && StringUtils.isNotBlank(location)) {
                if (redirects == maxRedirects) {
                    return Result.failure("重定向跳数超出上限: " + maxRedirects);
                }
                URI target = current.resolve(location);
                if (statusCode == 303
                        || ((statusCode == 301 || statusCode == 302) && "POST".equalsIgnoreCase(method))) {
                    method = "GET";
                    body = HttpRequest.BodyPublishers.noBody();
                }
                if (crossOrigin(origin, target)) {
                    // 跨源跳转 MUST NOT 顺带把鉴权头（凭证明文）送往新目标
                    headers.keySet().removeIf(name -> name.equalsIgnoreCase("Authorization"));
                }
                current = target;
                continue;
            }
            return Result.of(toResponse(statusCode, httpResponse, current, redirects));
        }
        return Result.failure("重定向跳数超出上限: " + maxRedirects);
    }

    /**
     * 出网响应构造（原文原样承载；脱敏与截断在 {@link Response#diagnosticBody()} 按需执行）。
     */
    private static Response toResponse(int statusCode, HttpResponse<byte[]> httpResponse,
                                       URI finalUri, int redirects) {
        byte[] raw = httpResponse.body();
        return new Response(statusCode,
                httpResponse.headers().firstValue("content-type").orElse(null),
                raw == null ? null : new String(raw, StandardCharsets.UTF_8),
                finalUri, redirects);
    }

    /**
     * 是否跨源跳转（协议 / 主机 / 端口任一不同即为跨源）。
     */
    private static boolean crossOrigin(URI origin, URI target) {
        return !origin.getScheme().equalsIgnoreCase(target.getScheme())
                || !origin.getHost().equalsIgnoreCase(target.getHost())
                || origin.getPort() != target.getPort();
    }
}