package com.linkroa.deepdataagent.vault.controller.convert;

import com.linkroa.deepdataagent.vault.application.dto.VaultCredentialViewDTO;
import com.linkroa.deepdataagent.vault.controller.response.VaultCredentialResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VaultCredentialResponseConvert} 协议层响应转换单测。
 * <p>覆盖契约形状（决策：凭证只写不读）：入口为应用层脱敏视图 {@link VaultCredentialViewDTO}，
 * 协议层不接触密文；响应侧 {@code display_name} 固定 null、无刷新配置时整键省略、
 * metadata 由 JSON 文本对象化（空白 / 非法收敛为空对象），且响应形状内不存在任何密文组件。</p>
 */
class VaultCredentialResponseConvertTest {

    private static final OffsetDateTime EXPIRES_AT = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));

    @Test
    void should_mapFullOAuthView_when_toResponse_given_mcpOAuthWithRefresh() {
        // given（mcp_oauth 完整视图：含到期时间与刷新配置，鉴权方式非空）
        VaultCredentialViewDTO view = new VaultCredentialViewDTO(
                "vcred_1", "vault_1", "mcp_oauth", "https://mcp.example.com/sse", EXPIRES_AT,
                new VaultCredentialViewDTO.Refresh(
                        "client-1", "https://idp.example.com/token",
                        "https://mcp.example.com", "read write", "client_secret_basic"),
                "{\"team\":\"data\"}", null, EXPIRES_AT, EXPIRES_AT);

        // when
        VaultCredentialResponse response = VaultCredentialResponseConvert.INSTANCE.toResponse(view);

        // then（身份 / 类型字段原样，密文成分一律不映射）
        assertEquals("vcred_1", response.id());
        assertEquals("vault_1", response.vaultId());
        assertEquals(VaultCredentialResponse.TYPE, response.type());
        assertEquals("vault_credential", response.type());
        assertNull(response.displayName());
        assertEquals("mcp_oauth", response.auth().type());
        assertEquals("https://mcp.example.com/sse", response.auth().mcpServerUrl());
        assertEquals(EXPIRES_AT, response.auth().expiresAt());
        assertEquals("client-1", response.auth().refresh().clientId());
        assertEquals("https://idp.example.com/token", response.auth().refresh().tokenEndpoint());
        assertEquals("https://mcp.example.com", response.auth().refresh().resource());
        assertEquals("read write", response.auth().refresh().scope());
        assertEquals("client_secret_basic", response.auth().refresh().tokenEndpointAuth().type());
    }

    @Test
    void should_omitRefresh_when_toResponse_given_viewWithoutRefresh() {
        // given（refresh 为 null：响应该子对象整键省略）
        VaultCredentialViewDTO view = new VaultCredentialViewDTO(
                "vcred_1", "vault_1", "mcp_oauth", "https://mcp.example.com/sse", EXPIRES_AT,
                null, null, null, EXPIRES_AT, EXPIRES_AT);

        // when
        VaultCredentialResponse response = VaultCredentialResponseConvert.INSTANCE.toResponse(view);

        // then
        assertNull(response.auth().refresh());
        assertEquals(EXPIRES_AT, response.auth().expiresAt());
    }

    @Test
    void should_mapStaticBearer_when_toResponse_given_staticBearerView() {
        // given（static_bearer 视图：无刷新配置、无到期时间）
        VaultCredentialViewDTO view = new VaultCredentialViewDTO(
                "cr_1", "vault_1", "static_bearer", "https://mcp.example.com/sse", null,
                null, null, null, EXPIRES_AT, EXPIRES_AT);

        // when
        VaultCredentialResponse response = VaultCredentialResponseConvert.INSTANCE.toResponse(view);

        // then
        assertEquals("static_bearer", response.auth().type());
        assertNull(response.auth().refresh());
        assertNull(response.auth().expiresAt());
    }

    @Test
    void should_mapMetadataObject_when_toResponse_given_validJsonMetadata() {
        // given
        VaultCredentialViewDTO view = new VaultCredentialViewDTO(
                "vcred_1", "vault_1", "static_bearer", "https://mcp.example.com/sse", null,
                null, "{\"team\":\"data\"}", null, EXPIRES_AT, EXPIRES_AT);

        // when
        VaultCredentialResponse response = VaultCredentialResponseConvert.INSTANCE.toResponse(view);

        // then（JSON 文本对象化为 key/value 对象）
        assertEquals(Map.of("team", "data"), response.metadata());
    }

    @Test
    void should_returnEmptyMap_when_toResponse_given_blankMetadata() {
        // given（null 与空白 metadata 均视为无 metadata）
        VaultCredentialViewDTO nullMetadata = new VaultCredentialViewDTO(
                "vcred_1", "vault_1", "static_bearer", "https://mcp.example.com/sse", null,
                null, null, null, EXPIRES_AT, EXPIRES_AT);
        VaultCredentialViewDTO blankMetadata = new VaultCredentialViewDTO(
                "vcred_1", "vault_1", "static_bearer", "https://mcp.example.com/sse", null,
                null, "   ", null, EXPIRES_AT, EXPIRES_AT);

        // when
        VaultCredentialResponse nullResponse = VaultCredentialResponseConvert.INSTANCE.toResponse(nullMetadata);
        VaultCredentialResponse blankResponse = VaultCredentialResponseConvert.INSTANCE.toResponse(blankMetadata);

        // then
        assertTrue(nullResponse.metadata().isEmpty());
        assertTrue(blankResponse.metadata().isEmpty());
    }

    @Test
    void should_returnEmptyMap_when_toResponse_given_invalidMetadataText() {
        // given（脏值不得让读侧失败）
        VaultCredentialViewDTO view = new VaultCredentialViewDTO(
                "vcred_1", "vault_1", "static_bearer", "https://mcp.example.com/sse", null,
                null, "not-json", null, EXPIRES_AT, EXPIRES_AT);

        // when
        VaultCredentialResponse response = VaultCredentialResponseConvert.INSTANCE.toResponse(view);

        // then（非法文本收敛为空对象）
        assertTrue(response.metadata().isEmpty());
    }

    @Test
    void should_exposeNoSecretComponents_when_toResponse_given_credentialResponseShape() {
        // given（凭证只写不读：响应形状内的 record 组件即对外出口面）
        // when
        List<String> refreshComponents = componentNames(VaultCredentialResponse.Refresh.class);
        List<String> tokenEndpointAuthComponents = componentNames(VaultCredentialResponse.TokenEndpointAuth.class);
        List<String> authComponents = componentNames(VaultCredentialResponse.Auth.class);
        List<String> topLevelComponents = componentNames(VaultCredentialResponse.class);

        // then（刷新配置只保留五个非密身份 / 参数组件，refresh_token 与 client_secret MUST NOT 出现）
        assertEquals(
                List.of("clientId", "tokenEndpoint", "resource", "scope", "tokenEndpointAuth"),
                refreshComponents);
        // 令牌端点鉴权方式只下发方式本身，密钥不返回
        assertEquals(List.of("type"), tokenEndpointAuthComponents);
        // 鉴权信息与顶层响应均无密文字段
        assertTrue(authComponents.stream().noneMatch(name -> name.toLowerCase().contains("cipher")));
        assertTrue(topLevelComponents.stream().noneMatch(name -> name.toLowerCase().contains("cipher")));
        assertTrue(topLevelComponents.stream().noneMatch(name -> name.toLowerCase().contains("token")));
    }

    /** 读取 record 组件名（按声明顺序），即对外响应形状的唯一字段面。 */
    private static List<String> componentNames(Class<? extends Record> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}