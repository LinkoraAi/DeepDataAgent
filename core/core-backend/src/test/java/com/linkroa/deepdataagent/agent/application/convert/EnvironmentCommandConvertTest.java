package com.linkroa.deepdataagent.agent.application.convert;

import com.linkroa.deepdataagent.agent.application.command.CreateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.controller.request.CreateEnvironmentRequest;
import com.linkroa.deepdataagent.agent.controller.request.EnvironmentConfigRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateEnvironmentRequest;
import com.linkroa.deepdataagent.agent.application.query.ListEnvironmentQuery;
import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentCommandConvertTest {

    @Test
    void should_defaultCloudConfig_when_toCreateCommand_given_omittedConfig() {
        // given
        CreateEnvironmentRequest request = new CreateEnvironmentRequest("云端环境", null, null, null);

        // when
        CreateEnvironmentCommand command = EnvironmentCommandConvert.INSTANCE.toCreateCommand(request);

        // then
        assertEquals(EnvironmentType.CLOUD, command.config().type());
        assertTrue(command.config().packages().isEmpty());
        assertEquals("{}", command.metadata());
    }

    @Test
    void should_parseConfigAndMetadata_when_toCreateCommand_given_cloudConfig() {
        // given（packages 为对象映射，仅 apt/pip/npm 三键）
        Map<String, Object> packages = Map.of(
                "apt", List.of("git"),
                "npm", List.of("@types/node@20.11.0"),
                "pip", List.of("pandas==2.2.0"));
        EnvironmentConfigRequest config = new EnvironmentConfigRequest("cloud", packages, "echo ready");
        CreateEnvironmentRequest request = new CreateEnvironmentRequest(
                "数据分析环境", "团队环境", config, Map.of("team", "data"));

        // when
        CreateEnvironmentCommand command = EnvironmentCommandConvert.INSTANCE.toCreateCommand(request);

        // then
        assertEquals("团队环境", command.description());
        assertEquals(EnvironmentType.CLOUD, command.config().type());
        assertEquals(List.of("git"), command.config().packages().apt());
        assertEquals(List.of("pandas==2.2.0"), command.config().packages().pip());
        assertEquals("echo ready", command.config().setupScript());
        assertTrue(command.metadata().contains("\"team\""));
        assertTrue(command.metadata().contains("\"data\""));
    }

    @Test
    void should_rejectUnknownPackageKey_when_toCreateCommand_given_unsupportedKey() {
        // given（cargo/gem/go 为响应保留字段，请求侧出现即 400）
        EnvironmentConfigRequest config = new EnvironmentConfigRequest(
                "cloud", Map.of("cargo", List.of("serde")), null);
        CreateEnvironmentRequest request = new CreateEnvironmentRequest("云端环境", null, config, null);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> EnvironmentCommandConvert.INSTANCE.toCreateCommand(request));
    }

    @Test
    void should_rejectNonArrayPackageValue_when_toCreateCommand_given_scalarValue() {
        // given（packages 键值必须为字符串数组）
        EnvironmentConfigRequest config = new EnvironmentConfigRequest(
                "cloud", Map.of("apt", "git"), null);
        CreateEnvironmentRequest request = new CreateEnvironmentRequest("云端环境", null, config, null);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> EnvironmentCommandConvert.INSTANCE.toCreateCommand(request));
    }

    @Test
    void should_acceptSelfHostedWithScript_when_toCreateCommand_given_selfHostedType() {
        // given
        EnvironmentConfigRequest config = new EnvironmentConfigRequest(
                "self_hosted", null, "apt-get update");
        CreateEnvironmentRequest request = new CreateEnvironmentRequest("自托管环境", null, config, null);

        // when
        CreateEnvironmentCommand command = EnvironmentCommandConvert.INSTANCE.toCreateCommand(request);

        // then
        assertEquals(EnvironmentType.SELF_HOSTED, command.config().type());
        assertEquals("apt-get update", command.config().setupScript());
    }

    @Test
    void should_rejectLegacyType_when_toCreateCommand_given_localType() {
        // given
        EnvironmentConfigRequest config = new EnvironmentConfigRequest("local", null, null);
        CreateEnvironmentRequest request = new CreateEnvironmentRequest("旧类型环境", null, config, null);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> EnvironmentCommandConvert.INSTANCE.toCreateCommand(request));
    }

    @Test
    void should_rejectPackagesOnSelfHosted_when_toCreateCommand_given_selfHostedWithPackages() {
        // given（self_hosted 携带包声明：领域不变量拒绝）
        EnvironmentConfigRequest config = new EnvironmentConfigRequest(
                "self_hosted", Map.of("pip", List.of("requests==2.31.0")), null);
        CreateEnvironmentRequest request = new CreateEnvironmentRequest("自托管环境", null, config, null);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> EnvironmentCommandConvert.INSTANCE.toCreateCommand(request));
    }

    @Test
    void should_bindEnvironmentId_when_toUpdateCommand_given_validRequest() {
        // given
        EnvironmentConfigRequest config = new EnvironmentConfigRequest(
                "cloud", Map.of("apt", List.of("curl")), null);
        UpdateEnvironmentRequest request = new UpdateEnvironmentRequest("云端环境", null, config, null);

        // when
        UpdateEnvironmentCommand command = EnvironmentCommandConvert.INSTANCE.toUpdateCommand("env-9", request);

        // then
        assertEquals("env-9", command.environmentId());
        assertEquals(List.of("curl"), command.config().packages().apt());
    }

    @Test
    void should_defaultCursor_when_toListQuery_given_blankParams() {
        // given（全空白参数：不过滤、游标走缺省 limit 20）
        // when
        ListEnvironmentQuery query = EnvironmentCommandConvert.INSTANCE.toListQuery(
                1L, " ", " ", " ", " ", null, null);

        // then
        assertEquals(1L, query.ownerId());
        assertNull(query.metadataJson());
        assertNull(query.createdAfter());
        assertNull(query.createdBefore());
        assertEquals(20, query.cursor().limit());
    }

    @Test
    void should_parseFilters_when_toListQuery_given_metadataAndTimes() {
        // given（metadata JSON 对象紧凑化 + ISO-8601 时间区间 + 游标参数）
        // when
        ListEnvironmentQuery query = EnvironmentCommandConvert.INSTANCE.toListQuery(
                1L, "{\"team\" : \"data\"}", "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z",
                "50", null, "env_abc");

        // then
        assertEquals("{\"team\":\"data\"}", query.metadataJson());
        assertEquals(OffsetDateTime.parse("2026-01-01T00:00:00Z"), query.createdAfter());
        assertEquals(OffsetDateTime.parse("2026-06-01T00:00:00Z"), query.createdBefore());
        assertEquals(50, query.cursor().limit());
        assertEquals("env_abc", query.cursor().beforeId());
    }

    @Test
    void should_throwBadRequest_when_toListQuery_given_metadataNotObject() {
        // given（JSON 数组非法 → 400）
        // when // then
        assertThrows(IllegalArgumentException.class, () -> EnvironmentCommandConvert.INSTANCE.toListQuery(
                1L, "[1,2]", null, null, null, null, null));
    }

    @Test
    void should_throwBadRequest_when_toListQuery_given_malformedTime() {
        // given（非 ISO-8601 时间文本 → 400）
        // when // then
        assertThrows(IllegalArgumentException.class, () -> EnvironmentCommandConvert.INSTANCE.toListQuery(
                1L, null, "2026/01/01", null, null, null, null));
    }
}
