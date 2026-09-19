package com.linkroa.deepdataagent.runtime.domain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ArtifactDeliveredSignal} 产物交付信号单测（领域值对象不变量，5.4）。
 * <p>覆盖：合法装配原样承载 / 会话 ID / 文件 ID / 文件名 / 内容类型空白与负字节数拒绝。</p>
 */
class ArtifactDeliveredSignalTest {

    @Test
    void should_carryDeliveredFields_when_constructed_given_validValues() {
        // given（登记成功后的交付事实）

        // when
        ArtifactDeliveredSignal signal =
                new ArtifactDeliveredSignal("sess_1", "file_1", "report.md", 12L, "text/markdown");

        // then（字段原样承载，不做派生）
        assertEquals("sess_1", signal.sessionId());
        assertEquals("file_1", signal.fileId());
        assertEquals("report.md", signal.originalFilename());
        assertEquals(12L, signal.sizeBytes());
        assertEquals("text/markdown", signal.contentType());
    }

    @Test
    void should_rejectBlankIdentity_when_constructed_given_blankSessionOrFileOrFilenameOrContentType() {
        // given（身份与内容类型字段均不可空白）

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactDeliveredSignal(" ", "file_1", "a.md", 1L, "text/markdown"));
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactDeliveredSignal("sess_1", "", "a.md", 1L, "text/markdown"));
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactDeliveredSignal("sess_1", "file_1", null, 1L, "text/markdown"));
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactDeliveredSignal("sess_1", "file_1", "a.md", 1L, " "));
    }

    @Test
    void should_rejectNegativeSize_when_constructed_given_negativeSizeBytes() {
        // given（字节数不可为负）

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactDeliveredSignal("sess_1", "file_1", "a.md", -1L, "text/markdown"));
    }
}