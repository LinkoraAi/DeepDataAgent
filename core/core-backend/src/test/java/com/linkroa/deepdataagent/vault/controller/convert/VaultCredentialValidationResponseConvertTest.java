package com.linkroa.deepdataagent.vault.controller.convert;

import com.linkroa.deepdataagent.vault.application.dto.HttpDiagnosticDTO;
import com.linkroa.deepdataagent.vault.application.service.VaultApplicationService.CredentialValidationResult;
import com.linkroa.deepdataagent.vault.application.service.VaultApplicationService.McpProbe;
import com.linkroa.deepdataagent.vault.application.service.VaultApplicationService.RefreshDiagnostic;
import com.linkroa.deepdataagent.vault.controller.response.VaultCredentialValidationResponse;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialRefreshStatus;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialValidationStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VaultCredentialValidationResponseConvert} 协议层响应转换单测。
 * <p>覆盖契约形状（design D4）：三态 / 四态枚举转小写取值、{@code type} 取固定标识、
 * 响应诊断逐字段映射，以及「{@code mcp_probe} 仅探测失败时出现」的省略契约
 * （成功时 MUST 整键省略，前端以「是否存在」判定探测失败）。</p>
 */
class VaultCredentialValidationResponseConvertTest {

    private static final OffsetDateTime VALIDATED_AT =
            OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));

    @Test
    void should_mapPassedShapeWithoutProbe_when_toResponse_given_validResult() {
        // given（探测通过：剥离 mcp_probe，刷新为 no_refresh_token 且无响应诊断）
        CredentialValidationResult result = new CredentialValidationResult("cr_1", "vault_1",
                VaultCredentialValidationStatus.VALID, VALIDATED_AT, false,
                new RefreshDiagnostic(VaultCredentialRefreshStatus.NO_REFRESH_TOKEN, null), null);

        // when
        VaultCredentialValidationResponse response = VaultCredentialValidationResponseConvert.INSTANCE.toResponse(result);

        // then
        assertEquals("cr_1", response.credentialId());
        assertEquals("vault_1", response.vaultId());
        assertEquals("vault_credential_validation", response.type());
        assertEquals("valid", response.status());
        assertEquals(VALIDATED_AT, response.validatedAt());
        assertFalse(response.hasRefreshToken());
        assertEquals("no_refresh_token", response.refresh().status());
        assertNull(response.refresh().httpResponse());
        assertNull(response.mcpProbe());
    }

    @Test
    void should_omitNullDiagnosticKeys_when_serialize_given_passedResult() {
        // given
        CredentialValidationResult result = new CredentialValidationResult("cr_1", "vault_1",
                VaultCredentialValidationStatus.VALID, VALIDATED_AT, false,
                new RefreshDiagnostic(VaultCredentialRefreshStatus.NO_REFRESH_TOKEN, null), null);
        VaultCredentialValidationResponse response = VaultCredentialValidationResponseConvert.INSTANCE.toResponse(result);
        ObjectMapper mapper = JsonMapper.builder().build();

        // when
        String json = mapper.writeValueAsString(response);

        // then（成功时 mcp_probe 与无响应的 http_response 均为整键省略，不含明文）
        assertFalse(json.contains("mcp_probe"));
        assertFalse(json.contains("http_response"));
        assertTrue(json.contains("\"status\":\"valid\""));
        assertTrue(json.contains("\"has_refresh_token\":false"));
    }

    @Test
    void should_carryProbeDiagnostic_when_toResponse_given_invalidResultWithProbe() {
        // given（探测被确定性拒绝：mcp_probe 出现，含失败的调用名与脱敏截断的响应诊断）
        CredentialValidationResult result = new CredentialValidationResult("cr_1", "vault_1",
                VaultCredentialValidationStatus.INVALID, VALIDATED_AT, true,
                new RefreshDiagnostic(VaultCredentialRefreshStatus.FAILED,
                        httpResponse(400, "application/json", "{\"error\":\"invalid_grant\"}")),
                new McpProbe("initialize", httpResponse(401, "application/json", "{\"error\":\"invalid_token\"}")));

        // when
        VaultCredentialValidationResponse response = VaultCredentialValidationResponseConvert.INSTANCE.toResponse(result);

        // then
        assertEquals("invalid", response.status());
        assertTrue(response.hasRefreshToken());
        assertEquals("failed", response.refresh().status());
        assertEquals(400, response.refresh().httpResponse().statusCode());
        assertEquals("initialize", response.mcpProbe().method());
        assertEquals(401, response.mcpProbe().httpResponse().statusCode());
        assertEquals("application/json", response.mcpProbe().httpResponse().contentType());
        assertEquals("{\"error\":\"invalid_token\"}", response.mcpProbe().httpResponse().body());
        assertFalse(response.mcpProbe().httpResponse().bodyTruncated());
    }

    @Test
    void should_serializeProbeAndDiagnostics_when_serialize_given_unknownResultWithProbe() {
        // given（探测未收到响应：unknown，mcp_probe 出现但无 http_response；响应体截断标记如实透传）
        CredentialValidationResult result = new CredentialValidationResult("cr_1", "vault_1",
                VaultCredentialValidationStatus.UNKNOWN, VALIDATED_AT, true,
                new RefreshDiagnostic(VaultCredentialRefreshStatus.CONNECT_ERROR, null),
                new McpProbe("initialize", new HttpDiagnosticDTO(503, "text/plain", "service unavail…", true)));
        VaultCredentialValidationResponse response = VaultCredentialValidationResponseConvert.INSTANCE.toResponse(result);
        ObjectMapper mapper = JsonMapper.builder().build();

        // when
        String json = mapper.writeValueAsString(response);

        // then（四态中的 connect_error 与摘要字段按契约蛇形落地）
        assertTrue(json.contains("\"status\":\"unknown\""));
        assertTrue(json.contains("\"status\":\"connect_error\""));
        assertTrue(json.contains("\"body_truncated\":true"));
        assertTrue(json.contains("\"mcp_probe\""));
        assertTrue(json.contains("\"method\":\"initialize\""));
        assertNull(response.refresh().httpResponse());
        assertNotNull(response.mcpProbe());
    }

    /** 脱敏诊断夹具（响应体已由基础设施脱敏截断）。 */
    private static HttpDiagnosticDTO httpResponse(int statusCode, String contentType, String body) {
        return new HttpDiagnosticDTO(statusCode, contentType, body, false);
    }
}