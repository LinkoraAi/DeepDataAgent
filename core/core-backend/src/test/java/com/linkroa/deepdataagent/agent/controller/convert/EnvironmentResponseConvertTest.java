package com.linkroa.deepdataagent.agent.controller.convert;

import com.linkroa.deepdataagent.agent.controller.response.EnvironmentResponse;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EnvironmentResponseConvert} 协议层响应转换单测（8.2/8.4 标准对象头与 packages 双向形态）：
 * 领域模型 → 响应装配（{@code id} / {@code type:"environment"} 标准对象头、config 判别对象六键回显、
 * metadata JSON 文本对象化），以及对外字段名统一 snake_case（{@code archived_at} /
 * {@code created_at} / {@code updated_at}）的序列化锁定。
 */
class EnvironmentResponseConvertTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-17T10:00:00+08:00");

    @Test
    void should_mapStandardHeaderAndConfig_when_toResponse_given_archivedCloudEnvironment() {
        // given（已归档 cloud 环境：config 缺省收敛为 packages 六键形状）
        Environment environment = Environment.restore(1L, "env_c1", "云端环境", "描述",
                EnvironmentConfig.cloudDefault(), "{\"k\":\"v\"}", 1L, NOW, NOW, NOW, "1", "1");

        // when
        EnvironmentResponse response = EnvironmentResponseConvert.INSTANCE.toResponse(environment);

        // then
        assertEquals("env_c1", response.id());
        assertEquals("environment", response.type());
        assertEquals("云端环境", response.name());
        assertEquals("描述", response.description());
        assertEquals("cloud", response.config().type());
        assertEquals("packages", response.config().packages().type());
        assertEquals(List.of(), response.config().packages().apt());
        assertEquals(Map.of("k", "v"), response.metadata());
        assertNotNull(response.archivedAt());
    }

    @Test
    void should_tolerateBlankMetadata_when_toResponse_given_unarchivedEnvironment() {
        // given（未归档环境 + 空白 metadata 文本：收敛为空对象、archived_at 为 null）
        Environment environment = Environment.restore(1L, "env_c2", "云端环境", null,
                EnvironmentConfig.cloudDefault(), "  ", 1L, null, NOW, NOW, "1", "1");

        // when
        EnvironmentResponse response = EnvironmentResponseConvert.INSTANCE.toResponse(environment);

        // then
        assertNull(response.archivedAt());
        assertEquals(Map.of(), response.metadata());
    }

    @Test
    void should_serializeSnakeCaseKeys_when_writeValueAsString_given_environmentResponse() {
        // given
        Environment environment = Environment.restore(1L, "env_c1", "云端环境", null,
                EnvironmentConfig.cloudDefault(), "{}", 1L, NOW, NOW, NOW, "1", "1");

        // when
        String json = MAPPER.writeValueAsString(EnvironmentResponseConvert.INSTANCE.toResponse(environment));

        // then（规格要求 snake_case：archived_at / created_at / updated_at）
        assertTrue(json.contains("\"archived_at\":"));
        assertTrue(json.contains("\"created_at\":"));
        assertTrue(json.contains("\"updated_at\":"));
        assertFalse(json.contains("archivedAt"));
        assertFalse(json.contains("createdAt"));
        assertFalse(json.contains("updatedAt"));
    }
}