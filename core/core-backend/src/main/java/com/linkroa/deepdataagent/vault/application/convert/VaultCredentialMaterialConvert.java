package com.linkroa.deepdataagent.vault.application.convert;

import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialMaterial;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialRefresh;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialTokenEndpointAuth;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialTokenEndpointAuthType;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 凭证秘密材料 ⇄ 加密信封 JSON 转换器（MapStruct 静态单例，见 design D5）。
 *
 * <p>密文列承载「整包加密」的秘密材料：本转换器负责把解密后的明文与
 * {@link VaultCredentialMaterial} 值对象互转。<b>旧形态兼容</b>——历史行的明文是
 * 单一访问令牌裸串而非信封 JSON，解析时按「只有访问令牌、无刷新配置」承接，
 * 该兼容路径使存量凭证无需数据迁移即可继续注入。</p>
 *
 * <p>信封形态（{@code kind} 标记用于与旧形态区分，避免把恰好是 JSON 对象的令牌误判为信封）：</p>
 * <pre>
 * {"kind":"vault_credential_material","version":1,"access_token":"...",
 *  "refresh":{"client_id":"...","refresh_token":"...","token_endpoint":"...",
 *             "token_endpoint_auth":{"type":"client_secret_basic","client_secret":"..."},
 *             "resource":null,"scope":"..."}}
 * </pre>
 */
@Mapper
public interface VaultCredentialMaterialConvert {

    VaultCredentialMaterialConvert INSTANCE = Mappers.getMapper(VaultCredentialMaterialConvert.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** 信封类型标记（旧形态明文裸串不含该标记）。 */
    String ENVELOPE_KIND = "vault_credential_material";

    /** 信封版本（为未来形态演进留出判别位）。 */
    int ENVELOPE_VERSION = 1;

    /**
     * 解密明文 → 秘密材料值对象（信封 JSON 或旧形态裸串）。
     *
     * @param plaintext 密文解密后的明文
     * @return 秘密材料（旧形态 = 访问令牌 + 无刷新配置）
     * @throws IllegalArgumentException 明文为空，或信封字段不合法
     */
    default VaultCredentialMaterial parse(String plaintext) {
        if (StringUtils.isBlank(plaintext)) {
            throw new IllegalArgumentException("凭证秘密值为空");
        }
        Map<String, Object> envelope = tryReadObject(plaintext);
        if (envelope == null || !ENVELOPE_KIND.equals(envelope.get("kind"))) {
            // 旧形态：整体即访问令牌明文裸串（无刷新配置）
            return new VaultCredentialMaterial(plaintext, null);
        }
        Object refreshNode = envelope.get("refresh");
        return new VaultCredentialMaterial(
                requiredText(envelope.get("access_token"), "access_token"),
                refreshNode == null ? null : toRefresh(refreshNode));
    }

    /**
     * 秘密材料值对象 → 信封 JSON（整包加密后落 {@code ciphertext} 列）。
     *
     * @param material 秘密材料
     * @return 信封 JSON 文本（无刷新配置时省略 {@code refresh} 键）
     */
    default String toEnvelopeJson(VaultCredentialMaterial material) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("kind", ENVELOPE_KIND);
        envelope.put("version", ENVELOPE_VERSION);
        envelope.put("access_token", material.accessToken());
        if (material.hasRefresh()) {
            envelope.put("refresh", toRefreshMap(material.refresh()));
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("凭证秘密材料序列化失败: " + e.getMessage(), e);
        }
    }

    /** 信封 refresh 子对象 → 刷新配置值对象。 */
    private static VaultCredentialRefresh toRefresh(Object node) {
        Map<String, Object> refresh = requireObject(node, "refresh");
        Object authNode = refresh.get("token_endpoint_auth");
        return new VaultCredentialRefresh(
                requiredText(refresh.get("client_id"), "refresh.client_id"),
                requiredText(refresh.get("refresh_token"), "refresh.refresh_token"),
                requiredText(refresh.get("token_endpoint"), "refresh.token_endpoint"),
                authNode == null ? null : toTokenEndpointAuth(authNode),
                optionalText(refresh.get("resource"), "refresh.resource"),
                optionalText(refresh.get("scope"), "refresh.scope"));
    }

    /** 信封 token_endpoint_auth 子对象 → 令牌端点鉴权值对象。 */
    private static VaultCredentialTokenEndpointAuth toTokenEndpointAuth(Object node) {
        Map<String, Object> auth = requireObject(node, "refresh.token_endpoint_auth");
        return new VaultCredentialTokenEndpointAuth(
                VaultCredentialTokenEndpointAuthType.fromValue(
                        optionalText(auth.get("type"), "refresh.token_endpoint_auth.type")),
                optionalText(auth.get("client_secret"), "refresh.token_endpoint_auth.client_secret"));
    }

    /** 刷新配置值对象 → 信封 refresh 子对象（保留 null 值键，落库形态稳定）。 */
    private static Map<String, Object> toRefreshMap(VaultCredentialRefresh refresh) {
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("type", refresh.tokenEndpointAuth().type().getValue());
        auth.put("client_secret", refresh.tokenEndpointAuth().clientSecret());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("client_id", refresh.clientId());
        map.put("refresh_token", refresh.refreshToken());
        map.put("token_endpoint", refresh.tokenEndpoint());
        map.put("token_endpoint_auth", auth);
        map.put("resource", refresh.resource());
        map.put("scope", refresh.scope());
        return map;
    }

    /** 尝试按 JSON 对象读取（失败返回 null，交由调用方走旧形态分支）。 */
    private static Map<String, Object> tryReadObject(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON 对象形态校验（非对象 → 400）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(Object node, String field) {
        if (node instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException(field + " 必须为 JSON 对象");
    }

    /** 必填文本（缺失 / 空白 / 非字符串 → 400）。 */
    private static String requiredText(Object node, String field) {
        String text = optionalText(node, field);
        if (StringUtils.isBlank(text)) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return text;
    }

    /** 可空文本（缺失 / null → null；非字符串 → 400）。 */
    private static String optionalText(Object node, String field) {
        if (node == null) {
            return null;
        }
        if (!(node instanceof String text)) {
            throw new IllegalArgumentException(field + " 必须为字符串");
        }
        return text;
    }
}