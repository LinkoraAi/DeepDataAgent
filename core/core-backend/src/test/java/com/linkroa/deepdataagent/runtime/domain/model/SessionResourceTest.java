package com.linkroa.deepdataagent.runtime.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionResource} 挂载资源值对象不变量单测（本期仅 {@code file} 类型扁平结构）。
 */
class SessionResourceTest {

    // ==================== 类型常量与外部接口字面量对齐 ====================

    @Test
    void should_exposeContractAlignedConstants_when_referenceConstants_given_fileType() {
        // given & when & then（类型标识与缺省挂载前缀须与外部接口字面量逐字一致）
        assertEquals("file", SessionResource.FILE_TYPE);
        assertEquals("mounts", SessionResource.MOUNTS_ROOT);
        assertEquals("mounts/", SessionResource.DEFAULT_MOUNT_PATH_PREFIX);
    }

    // ==================== file：文件挂载 ====================

    @Test
    void should_createFileResource_when_file_given_customMountPath() {
        // when（自定义挂载路径：mounts/ 前缀下的嵌套相对路径）
        SessionResource resource = SessionResource.file("file_1", "mounts/dataset/a.txt");

        // then
        assertEquals(SessionResource.FILE_TYPE, resource.type());
        assertEquals("file_1", resource.fileId());
        assertEquals("mounts/dataset/a.txt", resource.mountPath());
    }

    @Test
    void should_defaultMountPath_when_file_given_nullMountPath() {
        // when（挂载路径留空：落库前归一为缺省路径）
        SessionResource resource = SessionResource.file("file_9", null);

        // then（缺省挂载路径 = mounts/<file_id>）
        assertEquals("mounts/file_9", resource.mountPath());
    }

    @Test
    void should_defaultMountPath_when_file_given_blankMountPath() {
        // when（空白串与 null 同等对待，一律回落缺省路径）
        SessionResource resource = SessionResource.file("file_9", "   ");

        // then
        assertEquals("mounts/file_9", resource.mountPath());
    }

    @Test
    void should_throw_when_file_given_absoluteMountPath() {
        // given & when & then（绝对路径不合法，必须为 mounts/ 前缀相对路径）
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "/data/in.csv"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "/tmp/notes.md"));
    }

    @Test
    void should_throw_when_file_given_mountPathWithoutMountsPrefix() {
        // given & when & then（非 mounts/ 前缀的工作区相对路径拒绝，避免落到挂载根之外）
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "data/a.csv"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mounts"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mountsa/b"));
    }

    @Test
    void should_throw_when_file_given_traversalOrMalformedMountPath() {
        // given & when & then（穿越 / 反斜杠 / 空段 / 结尾斜杠一律拒绝）
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mounts/../secret"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mounts/a\\b"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mounts//a"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mounts/a/"));
    }

    @Test
    void should_reject_when_file_given_dotSegmentMountPath() {
        // given & when & then（`.` 段归一后与既有路径指向同一物理目标，可绕过判重，一律拒绝）
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mounts/a/./b"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mounts/./a"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "mounts/a/."));
    }

    @Test
    void should_throw_when_construct_given_blankFileId() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file(null, null));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("", null));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("  ", "mounts/a.txt"));
    }

    @Test
    void should_throw_when_construct_given_fileIdWithoutFilePrefix() {
        // given & when & then（文件 ID 必须携带 file_ 前缀）
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("not-a-file", null));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("ms_1", null));
    }

    @Test
    void should_throw_when_construct_given_relativeMountPath() {
        // given & when & then（裸相对路径与穿越写法均缺 mounts/ 前缀，一律拒绝）
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "../upload.txt"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "uploads/a.txt"));
        assertThrows(IllegalArgumentException.class, () -> SessionResource.file("file_1", "etc/passwd"));
    }

    // ==================== 通用：类型字段不变量 ====================

    @Test
    void should_throw_when_construct_given_blankType() {
        // given & when & then（类型为空 / 空白一律拒绝）
        assertThrows(IllegalArgumentException.class, () -> resource(null, "file_1", null));
        assertThrows(IllegalArgumentException.class, () -> resource("", "file_1", null));
        assertThrows(IllegalArgumentException.class, () -> resource("  ", "file_1", null));
    }

    @Test
    void should_throw_when_construct_given_unsupportedType() {
        // given & when & then（仅支持 file 类型，未知类型拒绝；类型标识大小写敏感）
        assertThrows(IllegalArgumentException.class, () -> resource("dataset", "file_1", null));
        assertThrows(IllegalArgumentException.class, () -> resource("File", "file_1", null));
        assertThrows(IllegalArgumentException.class, () -> resource("github_repository", "file_1", null));
    }

    // ==================== 资源 ID：sesr_ 前缀自动生成与显式保留 ====================

    @Test
    void should_generateSesrPrefixedId_when_file_given_blankId() {
        // when（工厂不传 id：紧凑构造器自动补齐 sesr_ + 32 位十六进制）
        SessionResource resource = SessionResource.file("file_1", null);

        // then
        assertTrue(resource.id().startsWith(SessionResource.RESOURCE_ID_PREFIX));
        assertEquals(SessionResource.RESOURCE_ID_PREFIX.length() + 32, resource.id().length());
    }

    @Test
    void should_generateDistinctIds_when_file_given_sameArguments() {
        // when（两次同源构造：id 互不相同）
        SessionResource first = SessionResource.file("file_1", null);
        SessionResource second = SessionResource.file("file_1", null);

        // then
        assertTrue(!first.id().equals(second.id()));
    }

    @Test
    void should_keepExplicitId_when_construct_given_nonBlankId() {
        // given & when（显式 id 原样保留，旧 jsonb 行反查后稳定不漂移）
        SessionResource resource = new SessionResource("sesr_fixed0001", SessionResource.FILE_TYPE,
                "file_1", null);

        // then
        assertEquals("sesr_fixed0001", resource.id());
    }

    // ==================== 测试夹具 ====================

    /**
     * 以四组件扁平构造器直接装配资源项（id 留空自动生成，仅开放 type / fileId / mountPath）。
     */
    private SessionResource resource(String type, String fileId, String mountPath) {
        return new SessionResource(null, type, fileId, mountPath);
    }
}