package com.linkroa.deepdataagent.vault.application.convert;

import com.linkroa.deepdataagent.vault.application.command.CreateVaultCommand;
import com.linkroa.deepdataagent.vault.application.command.UpdateVaultCredentialCommand;
import com.linkroa.deepdataagent.vault.application.query.ListVaultQuery;
import com.linkroa.deepdataagent.vault.controller.request.CreateVaultRequest;
import com.linkroa.deepdataagent.vault.controller.request.SearchVaultsRequest;
import com.linkroa.deepdataagent.vault.controller.request.UpdateVaultCredentialRequest;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialTokenEndpointAuthType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VaultCommandConvert} 保管库请求转换单测（6.6 管理面：游标列表参数裁决 +
 * 创建白名单与 {@code credentials} 拒收 + 搜索端点仅 JSON Body 参数 +
 * 凭证更新 merge 补丁三态裁决：身份字段 / 跨类型 / 白名单 / 显式 null 清除）。
 */
class VaultCommandConvertTest {

    @Test
    void should_defaultActiveOnly_when_toListQuery_given_noFilters() {
        // given（name 与 include_archived 均缺省：默认仅未归档，游标走缺省 20）
        // when
        ListVaultQuery query = VaultCommandConvert.INSTANCE.toListQuery(
                1L, null, null, null, null, null, null);

        // then
        assertEquals(1L, query.ownerId());
        assertNull(query.name());
        assertNull(query.metadataJson());
        assertEquals(Boolean.FALSE, query.archived());
        assertEquals(20, query.cursor().limit());
        assertNull(query.cursor().afterId());
        assertNull(query.cursor().beforeId());
    }

    @Test
    void should_resolveNameAndCursor_when_toListQuery_given_nameAndAfterId() {
        // given（name 模糊条件 + 游标 after_id + 显式 limit）
        // when
        ListVaultQuery query = VaultCommandConvert.INSTANCE.toListQuery(
                1L, "  数据  ", null, "5", "vault_x", null, null);

        // then（名称去空白后透传；limit 与游标原样）
        assertEquals("数据", query.name());
        assertEquals(5, query.cursor().limit());
        assertEquals("vault_x", query.cursor().afterId());
    }

    @Test
    void should_resolveUnlimited_when_toListQuery_given_includeArchivedTrue() {
        // given（include_archived=true → 归档态不限）
        // when
        ListVaultQuery query = VaultCommandConvert.INSTANCE.toListQuery(
                1L, null, "true", null, null, null, null);

        // then
        assertNull(query.archived());
    }

    @Test
    void should_treatPageAsAfterId_when_toListQuery_given_pageOnly() {
        // given（page 为向后翻页游标，等价 after_id）
        // when
        ListVaultQuery query = VaultCommandConvert.INSTANCE.toListQuery(
                1L, null, null, null, null, null, "vault_p");

        // then
        assertEquals("vault_p", query.cursor().afterId());
        assertNull(query.cursor().beforeId());
    }

    @Test
    void should_throwBadRequest_when_toListQuery_given_pageWithAfterId() {
        // given（page 与 after_id 互斥）
        // when // then
        assertThrows(IllegalArgumentException.class, () -> VaultCommandConvert.INSTANCE.toListQuery(
                1L, null, null, null, "vault_a", null, "vault_p"));
    }

    @Test
    void should_throwBadRequest_when_toListQuery_given_pageWithBeforeId() {
        // given（page 与 before_id 互斥）
        // when // then
        assertThrows(IllegalArgumentException.class, () -> VaultCommandConvert.INSTANCE.toListQuery(
                1L, null, null, null, null, "vault_b", "vault_p"));
    }

    // ==================== toCreateCommand（创建 Vault：白名单 + credentials 拒收） ====================

    @Test
    void should_assembleCommand_when_toCreateCommand_given_displayNameAndMetadata() {
        // given（display_name 必填并裁剪首尾空白；metadata 经共用校验后转紧凑 JSON 文本）
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("display_name", "  生产库  ");
        fields.put("metadata", new LinkedHashMap<>(Map.of("team", "data")));

        // when
        CreateVaultCommand command = VaultCommandConvert.INSTANCE.toCreateCommand(new CreateVaultRequest(fields));

        // then
        assertEquals("生产库", command.displayName());
        assertEquals("{\"team\":\"data\"}", command.metadata());
    }

    @Test
    void should_throwBadRequest_when_toCreateCommand_given_credentialsField() {
        // given（契约明令 MUST NOT 接受 credentials：只能经 credentials 子资源逐个添加）
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("display_name", "生产库");
        fields.put("credentials", List.of());

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> VaultCommandConvert.INSTANCE.toCreateCommand(new CreateVaultRequest(fields)));
    }

    @Test
    void should_throwBadRequest_when_toCreateCommand_given_unknownTopLevelField() {
        // given（顶层白名单外键 → 400，防拼写错误被静默丢弃）
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("display_name", "生产库");
        fields.put("type", "vault");

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> VaultCommandConvert.INSTANCE.toCreateCommand(new CreateVaultRequest(fields)));
    }

    @Test
    void should_throwBadRequest_when_toCreateCommand_given_missingDisplayName() {
        // given（display_name 必填：缺失即 400，不得落一个无名保管库）
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("metadata", new LinkedHashMap<>(Map.of("team", "data")));

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> VaultCommandConvert.INSTANCE.toCreateCommand(new CreateVaultRequest(fields)));
    }

    @Test
    void should_throwBadRequest_when_toCreateCommand_given_metadataValueOverBound() {
        // given（写入路径与搜索路径共用同一校验：metadata value 超过 512 字符 → 400）
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("display_name", "生产库");
        fields.put("metadata", new LinkedHashMap<>(Map.of("note", "v".repeat(513))));

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> VaultCommandConvert.INSTANCE.toCreateCommand(new CreateVaultRequest(fields)));
    }

    // ==================== toSearchQuery（搜索端点：仅 JSON Body 参数） ====================

    @Test
    void should_parseMetadataJson_when_toSearchQuery_given_objectText() {
        // given（metadata 对象 → 紧凑文本供 JSONB @> 精确匹配）
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("metadata", new LinkedHashMap<>(Map.of("team", "data")));

        // when
        ListVaultQuery query = VaultCommandConvert.INSTANCE.toSearchQuery(1L, new SearchVaultsRequest(fields));

        // then
        assertEquals("{\"team\":\"data\"}", query.metadataJson());
    }

    @Test
    void should_throwBadRequest_when_toSearchQuery_given_metadataNotObject() {
        // given（metadata 为 JSON 数组非法 → 400）
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("metadata", List.of(1, 2));

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> VaultCommandConvert.INSTANCE.toSearchQuery(1L, new SearchVaultsRequest(fields)));
    }

    @Test
    void should_throwBadRequest_when_toSearchQuery_given_unknownField() {
        // given（白名单外的键：列表端点才有的 status 不得出现在搜索体）
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "active");

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> VaultCommandConvert.INSTANCE.toSearchQuery(1L, new SearchVaultsRequest(fields)));
    }

    @Test
    void should_throwBadRequest_when_toListQuery_given_badIncludeArchivedFlag() {
        // given（布尔参数严格解析：非 true/false → 400）
        // when // then
        assertThrows(IllegalArgumentException.class, () -> VaultCommandConvert.INSTANCE.toListQuery(
                1L, null, "yes", null, null, null, null));
    }

    @Test
    void should_throwBadRequest_when_toListQuery_given_limitOverUpperBound() {
        // given（limit 上限 100：越界返回 400）
        // when // then
        assertThrows(IllegalArgumentException.class, () -> VaultCommandConvert.INSTANCE.toListQuery(
                1L, null, null, "101", null, null, null));
    }

    // ==================== toUpdateCredentialCommand（merge 补丁三态裁决） ====================

    @Test
    void should_assembleTokenPatch_when_toUpdateCredentialCommand_given_staticBearerTokenOnly() {
        // given（static_bearer 仅可更新 token；未提交的秘密材料字段保持 present=false）
        Map<String, Object> fields = patch("auth", patch("type", "static_bearer", "token", "sk-new"));

        // when
        UpdateVaultCredentialCommand command = convert(fields);

        // then
        assertEquals("vault_1", command.vaultId());
        assertEquals("cr_1", command.credentialId());
        assertEquals("static_bearer", command.authType());
        assertTrue(command.tokenPresent());
        assertEquals("sk-new", command.token());
        assertFalse(command.accessTokenPresent());
        assertFalse(command.metadataPresent());
    }

    @Test
    void should_assembleOAuthPatch_when_toUpdateCredentialCommand_given_accessTokenExpiresAtAndRefresh() {
        // given（mcp_oauth 可更新 access_token / expires_at / refresh.{refresh_token,scope,token_endpoint_auth}）
        Map<String, Object> fields = patch("auth", patch(
                "type", "mcp_oauth",
                "access_token", "at-new",
                "expires_at", "2026-09-16T10:00:00Z",
                "refresh", patch(
                        "refresh_token", "rt-new",
                        "scope", "read",
                        "token_endpoint_auth", patch("type", "client_secret_post", "client_secret", "cs-new"))),
                "metadata", patch("env", "prod"));

        // when
        UpdateVaultCredentialCommand command = convert(fields);

        // then
        assertEquals("mcp_oauth", command.authType());
        assertEquals("at-new", command.accessToken());
        assertEquals(OffsetDateTime.parse("2026-09-16T10:00:00Z"), command.expiresAt());
        assertEquals("rt-new", command.refreshToken());
        assertEquals("read", command.refreshScope());
        assertEquals(VaultCredentialTokenEndpointAuthType.CLIENT_SECRET_POST,
                command.refreshTokenEndpointAuth().type());
        assertEquals("cs-new", command.refreshTokenEndpointAuth().clientSecret());
        assertTrue(command.refreshPresent());
        assertEquals("{\"env\":\"prod\"}", command.metadataMerge());
    }

    @Test
    void should_markExplicitClear_when_toUpdateCredentialCommand_given_nullExpiresAtAndScope() {
        // given（显式 null = 清除：expires_at 置空、refresh.scope 置空）
        Map<String, Object> fields = patch("auth", patch(
                "type", "mcp_oauth",
                "expires_at", null,
                "refresh", patch("scope", null)));

        // when
        UpdateVaultCredentialCommand command = convert(fields);

        // then（present=true 且值为 null，应用层据此清除而非保持原值）
        assertTrue(command.expiresAtPresent());
        assertNull(command.expiresAt());
        assertTrue(command.refreshScopePresent());
        assertNull(command.refreshScope());
    }

    @Test
    void should_markMetadataReset_when_toUpdateCredentialCommand_given_nullMetadata() {
        // given（metadata 整体显式 null = 重置为 {}，以 merge 文本 null + present=true 承载）
        Map<String, Object> fields = patch("metadata", null);

        // when
        UpdateVaultCredentialCommand command = convert(fields);

        // then
        assertTrue(command.metadataPresent());
        assertNull(command.metadataMerge());
    }

    @Test
    void should_keepKeyDeletionMarker_when_toUpdateCredentialCommand_given_metadataKeyNull() {
        // given（metadata 单 key 传 null = 键级删除标记，增量文本保留该 null 值键）
        Map<String, Object> fields = patch("metadata", patch("a", null));

        // when
        UpdateVaultCredentialCommand command = convert(fields);

        // then
        assertEquals("{\"a\":null}", command.metadataMerge());
    }

    @Test
    void should_throwBadRequest_when_toUpdateCredentialCommand_given_mcpServerUrlIdentityField() {
        // given（身份字段 mcp_server_url 出现即拒 —— 改绑能力已撤除）
        Map<String, Object> fields = patch("auth", patch(
                "type", "static_bearer", "token", "sk-new", "mcp_server_url", "https://evil.example.com/sse"));

        // when // then
        assertThrows(IllegalArgumentException.class, () -> convert(fields));
    }

    @Test
    void should_throwBadRequest_when_toUpdateCredentialCommand_given_refreshIdentityFields() {
        // given（refresh.client_id 与 refresh.token_endpoint 为身份字段，出现即拒）
        Map<String, Object> clientIdPatch = patch("auth", patch("type", "mcp_oauth",
                "refresh", patch("client_id", "client-2", "refresh_token", "rt-new")));
        Map<String, Object> tokenEndpointPatch = patch("auth", patch("type", "mcp_oauth",
                "refresh", patch("token_endpoint", "https://evil.example.com/token")));

        // when // then
        assertThrows(IllegalArgumentException.class, () -> convert(clientIdPatch));
        assertThrows(IllegalArgumentException.class, () -> convert(tokenEndpointPatch));
    }

    @Test
    void should_throwBadRequest_when_toUpdateCredentialCommand_given_authWithoutType() {
        // given（auth 提供但缺 type：无从识别本次提交的类型 → 400）
        Map<String, Object> fields = patch("auth", patch("token", "sk-new"));

        // when // then
        assertThrows(IllegalArgumentException.class, () -> convert(fields));
    }

    @Test
    void should_throwBadRequest_when_toUpdateCredentialCommand_given_unknownAuthType() {
        // given（auth.type 值域外 → 400）
        Map<String, Object> fields = patch("auth", patch("type", "api_key", "token", "sk-new"));

        // when // then
        assertThrows(IllegalArgumentException.class, () -> convert(fields));
    }

    @Test
    void should_throwBadRequest_when_toUpdateCredentialCommand_given_crossTypeFields() {
        // given（跨类型误用：static_bearer 提交 access_token / mcp_oauth 提交 token，均 400）
        Map<String, Object> accessTokenOnStatic = patch("auth", patch("type", "static_bearer", "access_token", "at-1"));
        Map<String, Object> tokenOnOauth = patch("auth", patch("type", "mcp_oauth", "token", "sk-1"));

        // when // then
        assertThrows(IllegalArgumentException.class, () -> convert(accessTokenOnStatic));
        assertThrows(IllegalArgumentException.class, () -> convert(tokenOnOauth));
    }

    @Test
    void should_throwBadRequest_when_toUpdateCredentialCommand_given_tokenEndpointAuthMissingSecret() {
        // given（token_endpoint_auth 非 none 必须带 client_secret，缺失即 400）
        Map<String, Object> fields = patch("auth", patch("type", "mcp_oauth",
                "refresh", patch("token_endpoint_auth", patch("type", "client_secret_basic"))));

        // when // then
        assertThrows(IllegalArgumentException.class, () -> convert(fields));
    }

    @Test
    void should_throwBadRequest_when_toUpdateCredentialCommand_given_unknownTopLevelField() {
        // given（顶层白名单外键 → 400，防拼写错误被静默忽略）
        Map<String, Object> fields = patch("display_name", "x");

        // when // then
        assertThrows(IllegalArgumentException.class, () -> convert(fields));
    }

    @Test
    void should_throwBadRequest_when_toUpdateCredentialCommand_given_emptyPatch() {
        // given（空补丁：既无 auth 也无 metadata → 400，避免无意义写库）
        // when // then
        assertThrows(IllegalArgumentException.class, () -> convert(Map.of()));
    }

    /** 更新请求 → 更新命令（归属标识固定）。 */
    private static UpdateVaultCredentialCommand convert(Map<String, Object> fields) {
        return VaultCommandConvert.INSTANCE.toUpdateCredentialCommand(
                "vault_1", "cr_1", new UpdateVaultCredentialRequest(fields));
    }

    /** 键值构造（允许显式 null 值，{@code Map.of} 拒绝 null 故用 LinkedHashMap）。 */
    private static Map<String, Object> patch(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
