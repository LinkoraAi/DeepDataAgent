package com.linkroa.deepdataagent.runtime.domain.service;

import com.linkroa.deepdataagent.runtime.domain.model.MountViolationException;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.enums.MountViolationType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionMountPolicy} 挂载规则单测（纯内存、零 IO 的唯一事实源）。
 * <p>覆盖：类型门禁（仅 file 可追加）；{@code fileId} 判重（跨既有挂载与批内自重复）；
 * 挂载路径占用；字节清单缺键的双口径（既有项计 0 / 新增项判不可挂载）；500MB 总量配额
 * （恰好等于上限通过、越界一字节拒绝、非 file 既有项不占配额）；
 * 判定顺序（类型 → 判重 → 路径 → 就绪 → 配额）即状态码优先级；
 * 冲突种类与消息文本（{@link MountViolationException} 为措辞单点）。</p>
 */
class SessionMountPolicyTest {

    /** 既有文件挂载（缺省路径 mounts/&lt;file_id&gt;）。 */
    private static final SessionResource EXISTING_FILE = SessionResource.file("file_1", "mounts/a.txt");

    private static final long MB = 1024L * 1024L;

    // ==================== requireFileOnly：追加项类型门禁 ====================

    @Test
    void should_passSilently_when_requireFileOnly_given_allFileAdditions() {
        // given & when & then（纯文件批次不抛异常）
        SessionMountPolicy.requireFileOnly(List.of(
                SessionResource.file("file_1", null),
                SessionResource.file("file_2", "mounts/b.txt")));
    }

    @Test
    void should_passSilently_when_requireFileOnly_given_nullAdditions() {
        // given & when & then（空批次语义等价无追加，不抛异常）
        SessionMountPolicy.requireFileOnly(null);
        SessionMountPolicy.requireFileOnly(List.of());
    }

    @Test
    void should_throwAppendTypeUnsupported_when_requireFileOnly_given_memoryStoreAddition() {
        // given
        List<SessionResource> additions = List.of(SessionResource.memoryStore("ms_1", "read_only", null));

        // when
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> SessionMountPolicy.requireFileOnly(additions));

        // then（冲突种类 + 冲突对象为资源类型 + 消息文本单点口径）
        assertEquals(MountViolationType.APPEND_TYPE_UNSUPPORTED, ex.violation());
        assertEquals(SessionResource.MEMORY_STORE_TYPE, ex.subject());
        assertTrue(ex.getMessage().contains("追加挂载仅支持 file 类型资源"));
        assertTrue(ex instanceof IllegalArgumentException);
    }

    // ==================== validateAppend：合法批次 ====================

    @Test
    void should_passSilently_when_validateAppend_given_newFilesWithinQuota() {
        // given（既有 100MB，追加 200MB）
        Map<String, Long> sizes = Map.of("file_1", 100 * MB, "file_2", 200 * MB);

        // when & then
        SessionMountPolicy.validateAppend(List.of(EXISTING_FILE),
                List.of(SessionResource.file("file_2", "mounts/b.txt")), sizes);
    }

    @Test
    void should_passSilently_when_validateAppend_given_nullAdditionsAndNullExisting() {
        // given & when & then（两侧皆空的退化批次不触发任何规则）
        SessionMountPolicy.validateAppend(null, null, null);
    }

    // ==================== validateAppend：判重与路径占用 ====================

    @Test
    void should_throwDuplicateFile_when_validateAppend_given_fileAlreadyMounted() {
        // given（同一文件换路径再次挂载：判重以 fileId 为准）
        Map<String, Long> sizes = Map.of("file_1", 10 * MB);

        // when
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> SessionMountPolicy.validateAppend(List.of(EXISTING_FILE),
                        List.of(SessionResource.file("file_1", "mounts/other.txt")), sizes));

        // then
        assertEquals(MountViolationType.DUPLICATE_FILE, ex.violation());
        assertEquals("file_1", ex.subject());
        assertTrue(ex.getMessage().contains("文件重复挂载"));
    }

    @Test
    void should_throwDuplicateMountPath_when_validateAppend_given_pathOccupiedByAnotherFile() {
        // given
        Map<String, Long> sizes = Map.of("file_1", 10 * MB, "file_2", 10 * MB);

        // when
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> SessionMountPolicy.validateAppend(List.of(EXISTING_FILE),
                        List.of(SessionResource.file("file_2", "mounts/a.txt")), sizes));

        // then
        assertEquals(MountViolationType.DUPLICATE_MOUNT_PATH, ex.violation());
        assertEquals("mounts/a.txt", ex.subject());
        assertTrue(ex.getMessage().contains("挂载路径已被占用"));
    }

    @Test
    void should_throwDuplicateFile_when_validateAppend_given_sameFileTwiceWithinBatch() {
        // given（创建路径视角：既有为空，批内自重复）
        Map<String, Long> sizes = Map.of("file_9", 10 * MB);

        // when
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> SessionMountPolicy.validateAppend(List.of(),
                        List.of(SessionResource.file("file_9", "mounts/x.txt"),
                                SessionResource.file("file_9", "mounts/y.txt")), sizes));

        // then
        assertEquals(MountViolationType.DUPLICATE_FILE, ex.violation());
    }

    @Test
    void should_throwDuplicateMountPath_when_validateAppend_given_samePathTwiceWithinBatch() {
        // given
        Map<String, Long> sizes = Map.of("file_8", 10 * MB, "file_9", 10 * MB);

        // when
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> SessionMountPolicy.validateAppend(List.of(),
                        List.of(SessionResource.file("file_8", "mounts/dup.txt"),
                                SessionResource.file("file_9", "mounts/dup.txt")), sizes));

        // then
        assertEquals(MountViolationType.DUPLICATE_MOUNT_PATH, ex.violation());
    }

    // ==================== validateAppend：字节清单缺键双口径 ====================

    @Test
    void should_throwFileNotMountable_when_validateAppend_given_sizeMapMissingKeyForAddition() {
        // given（清单缺新增项键 = 应用层已判定其不存在 / 越权 / 未就绪）
        Map<String, Long> sizes = Map.of("file_1", 10 * MB);

        // when
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> SessionMountPolicy.validateAppend(List.of(EXISTING_FILE),
                        List.of(SessionResource.file("file_2", "mounts/b.txt")), sizes));

        // then
        assertEquals(MountViolationType.FILE_NOT_MOUNTABLE, ex.violation());
        assertEquals("file_2", ex.subject());
        assertTrue(ex.getMessage().contains("尚未就绪"));
    }

    @Test
    void should_countExistingAsZero_when_validateAppend_given_existingFileMissingFromSizeMap() {
        // given（既有 file_1 缺键按 0 计，追加 499MB 仍在配额内）
        Map<String, Long> sizes = Map.of("file_2", 499 * MB);

        // when & then（不抛异常即「缺键既有项不阻断、不计额」口径成立）
        SessionMountPolicy.validateAppend(List.of(EXISTING_FILE),
                List.of(SessionResource.file("file_2", "mounts/b.txt")), sizes);
    }

    // ==================== validateAppend：500MB 总量配额 ====================

    @Test
    void should_exposeFiveHundredMegabytes_when_maxMountedTotalBytes_given_constantDefinition() {
        assertEquals(500L * 1024 * 1024, SessionMountPolicy.MAX_MOUNTED_TOTAL_BYTES);
    }

    @Test
    void should_passSilently_when_validateAppend_given_totalExactlyAtQuota() {
        // given（300MB + 200MB = 恰好 500MB：上限语义为「不得超过」）
        Map<String, Long> sizes = Map.of("file_1", 300 * MB, "file_2", 200 * MB);

        // when & then
        SessionMountPolicy.validateAppend(List.of(EXISTING_FILE),
                List.of(SessionResource.file("file_2", "mounts/b.txt")), sizes);
    }

    @Test
    void should_throwQuotaExceeded_when_validateAppend_given_totalExceedsQuotaByOneByte() {
        // given
        long oversize = SessionMountPolicy.MAX_MOUNTED_TOTAL_BYTES - 300 * MB + 1;
        Map<String, Long> sizes = Map.of("file_1", 300 * MB, "file_2", oversize);

        // when
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> SessionMountPolicy.validateAppend(List.of(EXISTING_FILE),
                        List.of(SessionResource.file("file_2", "mounts/b.txt")), sizes));

        // then（越界项被点名，供应用层回显定位）
        assertEquals(MountViolationType.QUOTA_EXCEEDED, ex.violation());
        assertEquals("file_2", ex.subject());
        assertTrue(ex.getMessage().contains("500MB"));
    }

    @Test
    void should_accumulateAcrossBatch_when_validateAppend_given_laterAdditionBreaksQuota() {
        // given（既有 0 + 批内两条：第一条 400MB 通过，第二条 200MB 使累计越界）
        Map<String, Long> sizes = Map.of("file_2", 400 * MB, "file_3", 200 * MB);

        // when
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> SessionMountPolicy.validateAppend(List.of(),
                        List.of(SessionResource.file("file_2", "mounts/b.txt"),
                                SessionResource.file("file_3", "mounts/c.txt")), sizes));

        // then（整批全有或全无：点名第二条）
        assertEquals(MountViolationType.QUOTA_EXCEEDED, ex.violation());
        assertEquals("file_3", ex.subject());
    }

    @Test
    void should_ignoreNonFileResources_when_validateAppend_given_githubAndMemoryStoreMounted() {
        // given（非 file 既有项不参与判重基准与配额求和）
        List<SessionResource> existing = List.of(
                EXISTING_FILE,
                SessionResource.githubRepository("https://github.com/acme/app.git", "ghp_t", "main"),
                SessionResource.memoryStore("ms_1", null, null));
        Map<String, Long> sizes = Map.of("file_1", 300 * MB, "file_2", 200 * MB);

        // when & then（仅 file_1 的 300MB 参与求和，追加 200MB 恰好达标）
        SessionMountPolicy.validateAppend(existing,
                List.of(SessionResource.file("file_2", "mounts/b.txt")), sizes);
    }

    // ==================== 判定顺序即状态码优先级 ====================

    @Test
    void should_preferDuplicateFile_when_validateAppend_given_duplicateFileAlsoMissingMetadata() {
        // given（file_1 既已挂载又缺元数据：判重先于就绪判定）
        Map<String, Long> sizes = Map.of();

        // when
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> SessionMountPolicy.validateAppend(List.of(EXISTING_FILE),
                        List.of(SessionResource.file("file_1", "mounts/b.txt")), sizes));

        // then
        assertEquals(MountViolationType.DUPLICATE_FILE, ex.violation());
    }

    @Test
    void should_preferDuplicatePath_when_validateAppend_given_occupiedPathAlsoMissingMetadata() {
        // given
        Map<String, Long> sizes = Map.of();

        // when
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> SessionMountPolicy.validateAppend(List.of(EXISTING_FILE),
                        List.of(SessionResource.file("file_2", "mounts/a.txt")), sizes));

        // then
        assertEquals(MountViolationType.DUPLICATE_MOUNT_PATH, ex.violation());
    }

    @Test
    void should_preferReady_when_validateAppend_given_unreadyFileWouldAlsoBreakQuota() {
        // given（file_2 缺元数据且清单内其余项已近配额：就绪判定先于配额累加）
        Map<String, Long> sizes = Map.of("file_1", 499 * MB);

        // when
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> SessionMountPolicy.validateAppend(List.of(EXISTING_FILE),
                        List.of(SessionResource.file("file_2", "mounts/b.txt")), sizes));

        // then
        assertEquals(MountViolationType.FILE_NOT_MOUNTABLE, ex.violation());
    }

    @Test
    void should_preferTypeGate_when_validateAppend_given_nonFileAdditionWithoutMetadata() {
        // given（类型非法与元数据缺失同时成立）
        Map<String, Long> sizes = Map.of();

        // when
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> SessionMountPolicy.validateAppend(List.of(),
                        List.of(SessionResource.githubRepository("https://github.com/acme/app.git", "ghp_t", null)),
                        sizes));

        // then
        assertEquals(MountViolationType.APPEND_TYPE_UNSUPPORTED, ex.violation());
    }

    @Test
    void should_reportFirstOffendingAddition_when_validateAppend_given_multipleViolationsInOrder() {
        // given（第一条路径占用、第二条文件重复：按批次顺序先报第一条）
        Map<String, Long> sizes = Map.of("file_2", 10 * MB, "file_1", 10 * MB);

        // when
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> SessionMountPolicy.validateAppend(List.of(EXISTING_FILE),
                        List.of(SessionResource.file("file_2", "mounts/a.txt"),
                                SessionResource.file("file_1", "mounts/c.txt")), sizes));

        // then
        assertEquals(MountViolationType.DUPLICATE_MOUNT_PATH, ex.violation());
        assertEquals("mounts/a.txt", ex.subject());
    }
}
