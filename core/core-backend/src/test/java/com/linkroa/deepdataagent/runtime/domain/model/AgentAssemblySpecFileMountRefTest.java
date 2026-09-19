package com.linkroa.deepdataagent.runtime.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AgentAssemblySpec.FileMountRef} 值对象不变量单测。
 * <p>覆盖紧凑构造器四条校验：fileId 前缀、文件名非空、字节数非负、
 * 挂载路径 {@code mounts/} 前缀（sandbox-workspace-file-mounts D14）。</p>
 */
class AgentAssemblySpecFileMountRefTest {

    @Test
    void should_holdComponents_when_newFileMountRef_given_validValues() {
        // given / when
        AgentAssemblySpec.FileMountRef ref = new AgentAssemblySpec.FileMountRef(
                "file_1", "sales.csv", 2048L, "mounts/dataset/sales.csv");

        // then
        assertEquals("file_1", ref.fileId());
        assertEquals("sales.csv", ref.filename());
        assertEquals(2048L, ref.sizeBytes());
        assertEquals("mounts/dataset/sales.csv", ref.mountPath());
    }

    @Test
    void should_allowZeroSize_when_newFileMountRef_given_metadataFallback() {
        // given / when（元数据缺失回落：文件名以 fileId 顶替、体积记 0，「有记录即有行」）
        AgentAssemblySpec.FileMountRef ref = new AgentAssemblySpec.FileMountRef(
                "file_2", "file_2", 0L, "mounts/file_2");

        // then
        assertEquals(0L, ref.sizeBytes());
    }

    @Test
    void should_reject_when_newFileMountRef_given_blankOrIllegalFileId() {
        // given / when & then（空 / 非 file_ 前缀均拒绝）
        assertThrows(IllegalArgumentException.class, () -> new AgentAssemblySpec.FileMountRef(
                null, "sales.csv", 1L, "mounts/file_1"));
        assertThrows(IllegalArgumentException.class, () -> new AgentAssemblySpec.FileMountRef(
                "  ", "sales.csv", 1L, "mounts/file_1"));
        assertThrows(IllegalArgumentException.class, () -> new AgentAssemblySpec.FileMountRef(
                "doc_1", "sales.csv", 1L, "mounts/doc_1"));
    }

    @Test
    void should_reject_when_newFileMountRef_given_blankFilename() {
        // given / when & then
        assertThrows(IllegalArgumentException.class, () -> new AgentAssemblySpec.FileMountRef(
                "file_1", " ", 1L, "mounts/file_1"));
    }

    @Test
    void should_reject_when_newFileMountRef_given_negativeSize() {
        // given / when & then
        assertThrows(IllegalArgumentException.class, () -> new AgentAssemblySpec.FileMountRef(
                "file_1", "sales.csv", -1L, "mounts/file_1"));
    }

    @Test
    void should_reject_when_newFileMountRef_given_mountPathWithoutMountsPrefix() {
        // given / when & then（缺前缀 / 空 / 恰为 mounts 本身均拒绝——视图与落点口径一致）
        assertThrows(IllegalArgumentException.class, () -> new AgentAssemblySpec.FileMountRef(
                "file_1", "sales.csv", 1L, "uploads/file_1"));
        assertThrows(IllegalArgumentException.class, () -> new AgentAssemblySpec.FileMountRef(
                "file_1", "sales.csv", 1L, null));
        assertThrows(IllegalArgumentException.class, () -> new AgentAssemblySpec.FileMountRef(
                "file_1", "sales.csv", 1L, "mounts"));
    }
}
