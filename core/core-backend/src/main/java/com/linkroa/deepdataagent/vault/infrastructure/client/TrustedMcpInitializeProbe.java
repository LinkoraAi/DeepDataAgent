package com.linkroa.deepdataagent.vault.infrastructure.client;

import com.linkroa.deepdataagent.shared.config.EgressProperties;
import com.linkroa.deepdataagent.shared.net.TrustedEgressClient;
import com.linkroa.deepdataagent.vault.application.dto.HttpDiagnosticDTO;
import com.linkroa.deepdataagent.vault.application.dto.McpProbeOutcomeDTO;
import com.linkroa.deepdataagent.vault.application.port.McpInitializeProbePort;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 探测出站实现（{@link McpInitializeProbePort}，见 design D4 / D8 / D16）。
 *
 * <p>由 vault BC 自建的最小 MCP 探测：向 MCP 服务器发起一次 JSON-RPC {@code initialize}
 * 握手（{@code POST} 到 {@code mcp_server_url}），请求头携带当前可信访问令牌。
 * 以协议层是否被接受作为凭证有效性的实时判据——鉴权失败在握手阶段即暴露，
 * 比单纯的可达性探测更贴近真实使用。</p>
 *
 * <p>与 MCP 建连（运行时装配）共用同一份出网信任边界判定：越界目标
 * <b>不发出任何实际网络请求</b>，直接回传「未收到响应」。</p>
 */
@Service
public class TrustedMcpInitializeProbe implements McpInitializeProbePort {

    private static final Logger log = LoggerFactory.getLogger(TrustedMcpInitializeProbe.class);

    /** 探测发起的 MCP 调用名（失败诊断回传该名）。 */
    private static final String INITIALIZE_METHOD = "initialize";

    /** 最小 JSON-RPC 握手报文（无任何凭证材料）。 */
    private static final String INITIALIZE_REQUEST = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
            "capabilities":{},"clientInfo":{"name":"deepdataagent","version":"1.0.0"}}}""";

    @Resource
    private EgressProperties egressProperties;

    @Override
    public McpProbeOutcomeDTO probe(String mcpServerUrl, String accessToken) {
        URI target = parseUri(mcpServerUrl);
        if (target == null) {
            log.debug("MCP 探测目标不可用，跳过探测: reason=非法URL");
            return new McpProbeOutcomeDTO(INITIALIZE_METHOD, null);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        // MCP streamable-http 传输的响应可能是 SSE 流，Accept 须同时声明两种媒体类型
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("Authorization", "Bearer " + accessToken);

        TrustedEgressClient.Request request = new TrustedEgressClient.Request("POST", target, headers,
                HttpRequest.BodyPublishers.ofString(INITIALIZE_REQUEST, StandardCharsets.UTF_8));
        TrustedEgressClient.Result result = TrustedEgressClient.send(request,
                egressProperties.isAllowPrivateNetwork(), egressProperties.getMaxRedirects());
        if (result.failed()) {
            log.debug("MCP 探测未收到响应: reason={}", result.failureReason());
            return new McpProbeOutcomeDTO(INITIALIZE_METHOD, null);
        }
        TrustedEgressClient.Response response = result.response();
        return new McpProbeOutcomeDTO(INITIALIZE_METHOD, new HttpDiagnosticDTO(
                response.statusCode(), response.contentType(),
                response.diagnosticBody(), response.diagnosticBodyTruncated()));
    }

    /** 目标 URL 解析（非法 / 缺失返回 null，交由调用方按「未收到响应」处置）。 */
    private static URI parseUri(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        try {
            return URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}