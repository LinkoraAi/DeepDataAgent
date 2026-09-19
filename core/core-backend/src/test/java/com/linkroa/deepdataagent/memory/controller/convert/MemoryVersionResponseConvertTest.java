package com.linkroa.deepdataagent.memory.controller.convert;

import com.linkroa.deepdataagent.memory.controller.response.MemoryVersionResponse;
import com.linkroa.deepdataagent.memory.domain.model.MemoryVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryVersionResponseConvert} 协议层响应转换单测。
 * <p>覆盖同名字段映射、动作枚举转小写取值，以及 redact 隐私契约：
 * 已脱敏版本序列化后 MUST NOT 含 content / contentSha256 字段（NON_NULL 省略）。</p>
 */
class MemoryVersionResponseConvertTest {

    @Test
    void should_mapAllFieldsWithLowercaseAction_when_toResponse_given_createdVersion() {
        // given
        MemoryVersion version = MemoryVersion.created("memver_1", "ms_1", "mem_1", "notes/a", 1, "初始内容");

        // when
        MemoryVersionResponse response = MemoryVersionResponseConvert.INSTANCE.toResponse(version);

        // then
        assertEquals("memver_1", response.versionId());
        assertEquals("ms_1", response.storeId());
        assertEquals("mem_1", response.entryId());
        assertEquals("notes/a", response.entryPath());
        assertEquals(1, response.version());
        assertEquals("created", response.action());
        assertEquals("初始内容", response.content());
        assertEquals(version.size(), response.size());
        assertEquals(version.contentSha256(), response.contentSha256());
        assertFalse(response.redacted());
        assertNull(response.redactedAt());
        assertNotNull(response.createdAt());
    }

    @Test
    void should_clearContentAndShaKeepSize_when_toResponse_given_redactedVersion() {
        // given
        MemoryVersion redacted =
                MemoryVersion.created("memver_1", "ms_1", "mem_1", "notes/a", 1, "敏感内容").redact();

        // when
        MemoryVersionResponse response = MemoryVersionResponseConvert.INSTANCE.toResponse(redacted);

        // then（脱敏后内容与校验值清除，字节数保留）
        assertNull(response.content());
        assertNull(response.contentSha256());
        assertTrue(response.redacted());
        assertEquals(redacted.size(), response.size());
        assertNotNull(response.redactedAt());
    }

    @Test
    void should_omitContentFieldsInJson_when_serialize_given_redactedResponse() {
        // given
        MemoryVersion redacted =
                MemoryVersion.created("memver_1", "ms_1", "mem_1", "notes/a", 1, "敏感内容").redact();
        MemoryVersionResponse response = MemoryVersionResponseConvert.INSTANCE.toResponse(redacted);
        ObjectMapper mapper = JsonMapper.builder().build();

        // when
        String json = mapper.writeValueAsString(response);

        // then（redact 契约：响应不得含 content / contentSha256 字段，size 与标记保留）
        assertFalse(json.contains("\"content\":"));
        assertFalse(json.contains("\"contentSha256\":"));
        assertTrue(json.contains("\"redacted\":true"));
        assertTrue(json.contains("\"size\":"));
    }
}
