package com.linkroa.deepdataagent.file.controller.convert;

import com.linkroa.deepdataagent.file.controller.response.FileResponse;
import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.model.FileScope;
import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;
import com.linkroa.deepdataagent.file.domain.model.enums.FileStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileResponseConvert} 协议层响应转换单测（5.6 上传端点对齐 File 对象形态）：
 * 领域聚合 → 响应逐字段映射（scope 嵌套、purpose/status 输出契约词汇），以及对外字段名统一
 * snake_case（{@code id} / {@code size_bytes} / {@code mime_type} / {@code created_at} /
 * {@code updated_at}）的序列化锁定。
 */
class FileResponseConvertTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-17T10:00:00+08:00");

    @Test
    void should_mapDomainFieldsToResponse_when_toResponse_given_scopedFile() {
        // given（会话产出物：scope 关联会话、downloadable 由 purpose 派生）
        File file = File.restore(1L, "file_1", 1L, "out.csv", "text/csv", 5L,
                FilePurpose.TOOL_OUTPUT, FileStatus.READY, true,
                new FileScope("sess_1", FileScope.TYPE_SESSION), "{}", "0".repeat(64), NOW, NOW);

        // when
        FileResponse response = FileResponseConvert.INSTANCE.toResponse(file);

        // then
        assertEquals("file_1", response.fileId());
        assertEquals("file", response.type());
        assertEquals("out.csv", response.filename());
        assertEquals("text/csv", response.mimeType());
        assertEquals(5L, response.sizeBytes());
        assertEquals("tool_output", response.purpose());
        assertEquals("ready", response.status());
        assertTrue(response.downloadable());
        assertEquals("sess_1", response.scope().id());
        assertEquals("session", response.scope().type());
    }

    @Test
    void should_mapNullScope_when_toResponse_given_unscopedFile() {
        // given（未关联会话的上传件：scope 透传 null）
        File file = File.restore(1L, "file_2", 1L, "notes.md", "text/markdown", 3L,
                FilePurpose.USER_UPLOAD, FileStatus.READY, false,
                null, "{}", "0".repeat(64), NOW, NOW);

        // when
        FileResponse response = FileResponseConvert.INSTANCE.toResponse(file);

        // then
        assertNull(response.scope());
        assertFalse(response.downloadable());
    }

    @Test
    void should_serializeSnakeCaseKeys_when_writeValueAsString_given_fileResponse() {
        // given
        File file = File.restore(1L, "file_1", 1L, "out.csv", "text/csv", 5L,
                FilePurpose.USER_UPLOAD, FileStatus.READY, false,
                null, "{}", "0".repeat(64), NOW, NOW);
        FileResponse response = FileResponseConvert.INSTANCE.toResponse(file);

        // when
        String json = MAPPER.writeValueAsString(response);

        // then（规格要求 snake_case：id / size_bytes / mime_type / created_at / updated_at）
        assertTrue(json.contains("\"id\":\"file_1\""));
        assertTrue(json.contains("\"size_bytes\":5"));
        assertTrue(json.contains("\"mime_type\":\"text/csv\""));
        assertTrue(json.contains("\"created_at\":"));
        assertTrue(json.contains("\"updated_at\":"));
        assertFalse(json.contains("fileId"));
        assertFalse(json.contains("sizeBytes"));
        assertFalse(json.contains("mimeType"));
        assertFalse(json.contains("createdAt"));
    }
}