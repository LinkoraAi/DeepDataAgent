package com.linkroa.deepdataagent.runtime.domain.service;

import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.McpConnection;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.VaultCredentialRef;
import com.linkroa.deepdataagent.runtime.domain.model.McpConnectionCredential;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 凭据解析器：把「版本声明的 MCP 连接」与「本轮材料化的保管库凭证」按 Target
 * 精确匹配，产出逐连接的鉴权请求头（{@link McpConnectionCredential}）。
 *
 * <p><b>安全定位</b>：本解析器是保管库明文进入 MCP 协议栈的<b>唯一通道</b>——
 * 凭据经 JVM 侧装配的 MCP 客户端请求头注入，<b>永不写入 Docker 沙箱环境变量</b>
 * （原 {@code VAULT_MCP_CREDENTIALS} 聚合注入已废除）。产出的明文 header 只被
 * {@code AgentscopeHarnessAgentFactory} 消费一次，不落库、不进日志、不进响应。</p>
 *
 * <p><b>匹配规则（唯一口径：仅 URL）</b>：</p>
 * <ul>
 *   <li>凭证 {@code target} 与连接的<b>完整 URL</b> 规范化后全等即命中；规范化仅去首尾空白与
 *       <b>尾部多余 {@code /}</b>，path 大小写敏感；</li>
 *   <li><b>服务器名称不参与匹配</b>：凭证与连接仅按 URL 关联（公开契约口径——凭证「绑定到具体
 *       MCP 服务器 URL」），不同 URL 同名不得误命中；</li>
 *   <li><b>禁止前缀匹配</b>：{@code https://mcp.example.com} 不得命中
 *       {@code https://mcp.example.com/v2/sse} 这类更长的 URL；</li>
 *   <li>仅 {@code static_bearer} / {@code mcp_oauth} 型凭证参与匹配并注入
 *       {@code Authorization: Bearer <token>}；{@code environment_variable} 型
 *       <b>不自动注入</b>（其 target 是变量名，且按平台红线绝不批量导出到数据面）；</li>
 *   <li>同一连接命中多条可注入凭证 → 无法裁决用哪条，抛 {@link IllegalStateException}
 *       （消息含连接名与冲突凭证的脱敏引用，<b>绝不含 token 明文</b>）。</li>
 * </ul>
 *
 * <p>鉴权类型字域以字符串常量比对（与 vault BC {@code VaultCredentialAuthType} 的
 * 已格式化小写值一致），runtime 领域层不跨 BC import 他 BC 枚举。</p>
 */
@Service
public class McpCredentialResolver {

    /** 静态 Bearer Token 鉴权类型字域（对齐 vault BC 发布值）。 */
    static final String AUTH_TYPE_STATIC_BEARER = "static_bearer";
    /** MCP OAuth 鉴权类型字域（对齐 vault BC 发布值）。 */
    static final String AUTH_TYPE_MCP_OAUTH = "mcp_oauth";

    /** 鉴权请求头名。 */
    static final String HEADER_AUTHORIZATION = "Authorization";

    /** Bearer 方案前缀（含尾随空格）。 */
    static final String BEARER_PREFIX = "Bearer ";

    /**
     * 解析全部 MCP 连接的鉴权材料：每个连接产出一条（无凭证命中时 header 为空）。
     *
     * @param connections 版本声明的 MCP 连接清单（可空 = 无连接，直接返回空列表）
     * @param credentials 本轮材料化的保管库凭证引用（含明文 token，仅内存；可空）
     * @return 逐连接鉴权材料（顺序与入参连接一致）
     * @throws IllegalStateException 同一连接命中多条可注入凭证，或命中凭证缺失明文 token
     */
    public List<McpConnectionCredential> resolve(List<McpConnection> connections,
                                                 List<VaultCredentialRef> credentials) {
        if (connections == null || connections.isEmpty()) {
            return List.of();
        }
        List<VaultCredentialRef> candidates = credentials == null
                ? List.of()
                : credentials.stream().filter(this::autoInjectable).toList();
        List<McpConnectionCredential> resolved = new ArrayList<>(connections.size());
        for (McpConnection connection : connections) {
            List<VaultCredentialRef> matched = candidates.stream()
                    .filter(ref -> matches(ref, connection))
                    .toList();
            if (matched.size() > 1) {
                throw new IllegalStateException("MCP 连接 [%s] 命中 %d 条保管库凭证，无法裁决注入哪条鉴权头: %s"
                        .formatted(connection.name(), matched.size(), describeMatched(matched)));
            }
            resolved.add(new McpConnectionCredential(
                    connection.name(),
                    connection.url(),
                    matched.isEmpty() ? Map.of() : bearerHeaders(matched.get(0), connection)));
        }
        return List.copyOf(resolved);
    }

    /** 凭证是否具备按 Target 自动注入鉴权头的语义（仅 static_bearer / mcp_oauth）。 */
    private boolean autoInjectable(VaultCredentialRef ref) {
        return AUTH_TYPE_STATIC_BEARER.equalsIgnoreCase(ref.authType())
                || AUTH_TYPE_MCP_OAUTH.equalsIgnoreCase(ref.authType());
    }

    /** 凭证 Target 与连接完整 URL 规范化后全等即命中（仅 URL 口径，服务器名称不参与匹配；严禁前缀匹配）。 */
    private boolean matches(VaultCredentialRef ref, McpConnection connection) {
        return normalize(ref.target()).equals(normalize(connection.url()));
    }

    /**
     * 匹配用规范化：去首尾空白与尾部多余 {@code /}；大小写敏感（path 语义由服务端区分）。
     */
    private static String normalize(String value) {
        String result = value.trim();
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** 构造 Bearer 鉴权头；命中凭证缺失明文视为装配不变量破坏，快速失败。 */
    private Map<String, String> bearerHeaders(VaultCredentialRef ref, McpConnection connection) {
        if (StringUtils.isBlank(ref.token())) {
            throw new IllegalStateException("MCP 连接 [%s] 命中的保管库凭证 [%s/%s] 缺失解密明文，无法注入鉴权头"
                    .formatted(connection.name(), ref.vaultId(), ref.credentialId()));
        }
        return Map.of(HEADER_AUTHORIZATION, BEARER_PREFIX + ref.token());
    }

    /** 冲突凭证描述：借 {@link VaultCredentialRef} 自带脱敏 toString，绝不泄露 token 明文。 */
    private static String describeMatched(List<VaultCredentialRef> matched) {
        return matched.stream()
                .map(VaultCredentialRef::toString)
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
