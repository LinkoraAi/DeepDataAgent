package com.linkroa.deepdataagent.vault.application.convert;

import com.linkroa.deepdataagent.vault.application.command.StartVaultOAuthCommand;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.util.Map;
import java.util.Set;

/**
 * MCP OAuth 授权命令装配器（MapStruct 静态单例，协议层参数 → 应用命令）。
 *
 * <p>以 {@code Map} 整体接收请求体（普通 record 无法在「键缺省」与「显式 null」之外
 * 还区分「出现了不该出现的键」）：<b>OAuth 传输层字段一律出现即拒</b>——契约明示
 * {@code protocol} / {@code scope} / {@code redirect_uri} MUST NOT 作为该端点的请求字段，
 * 端点与 scopes 由服务端 metadata 发现，回调地址取服务端配置。</p>
 */
@Mapper
public interface VaultOAuthCommandConvert {

    VaultOAuthCommandConvert INSTANCE = Mappers.getMapper(VaultOAuthCommandConvert.class);

    /** 契约明示不得作为本端点请求字段的 OAuth 传输层字段（出现即 400）。 */
    Set<String> FORBIDDEN_REQUEST_FIELDS = Set.of("protocol", "scope", "redirect_uri");

    /** 契约允许的请求字段（其余键一律出现即拒，防止「协议层悄悄接受后又忽略」）。 */
    Set<String> ALLOWED_REQUEST_FIELDS = Set.of("vault_id", "mcp_server_url", "client_id", "client_secret");

    /**
     * 请求体键值 → 发起授权命令。
     *
     * @param fields 请求体键值对象
     * @param origin 发起授权的前端来源（取请求 {@code Origin} 头；缺失为 null）
     * @return 发起授权命令
     * @throws IllegalArgumentException 出现未声明的请求字段（含契约禁止的传输层字段），或必填字段非字符串
     */
    default StartVaultOAuthCommand toStartCommand(Map<String, Object> fields, String origin) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        for (String name : fields.keySet()) {
            if (FORBIDDEN_REQUEST_FIELDS.contains(name)) {
                throw new IllegalArgumentException("请求字段不被接受（端点与 scopes 由服务端 metadata 发现、"
                        + "回调地址取服务端配置）: " + name);
            }
            if (!ALLOWED_REQUEST_FIELDS.contains(name)) {
                throw new IllegalArgumentException("未知请求字段: " + name);
            }
        }
        return new StartVaultOAuthCommand(
                text(fields.get("vault_id"), "vault_id"),
                text(fields.get("mcp_server_url"), "mcp_server_url"),
                text(fields.get("client_id"), "client_id"),
                text(fields.get("client_secret"), "client_secret"),
                origin);
    }

    /** 可空文本字段（缺失 / null → null；非字符串 → 400）。 */
    default String text(Object node, String field) {
        if (node == null) {
            return null;
        }
        if (!(node instanceof String value)) {
            throw new IllegalArgumentException(field + " 必须为字符串");
        }
        return StringUtils.isBlank(value) ? null : value;
    }
}