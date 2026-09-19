package com.linkroa.deepdataagent.runtime.application.service.assembly;

import com.linkroa.deepdataagent.file.api.FileApi;
import com.linkroa.deepdataagent.file.api.dto.FileMountMetaDTO;
import com.linkroa.deepdataagent.file.application.dto.FileMountMaterializationDTO;
import com.linkroa.deepdataagent.file.application.port.FileMountMaterializationPort;
import com.linkroa.deepdataagent.runtime.domain.factory.SessionWorkspacePort;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.shared.exception.FileContentIntegrityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionMountMaterializer} 物化编排单测：创建 / 追加批内物化与补偿删除、
 * 装配期 reconcile 幂等对账（补缺 / 回落行 / 不动孤儿副本 / 完整性异常上抛）、
 * 单副本释放与命名空间清理委托。
 */
@ExtendWith(MockitoExtension.class)
class SessionMountMaterializerTest {

    @Mock private FileMountMaterializationPort fileMountMaterializationPort;
    @Mock private FileApi fileApi;
    @Mock private SessionWorkspacePort sessionWorkspacePort;

    @TempDir
    Path tempDir;

    private SessionMountMaterializer materializer;

    @BeforeEach
    void setUp() {
        materializer = new SessionMountMaterializer();
        ReflectionTestUtils.setField(materializer, "fileMountMaterializationPort", fileMountMaterializationPort);
        ReflectionTestUtils.setField(materializer, "fileApi", fileApi);
        ReflectionTestUtils.setField(materializer, "sessionWorkspacePort", sessionWorkspacePort);
        // 宿主落点派生：mounts/<rest> → <tempDir>/mounts/<rest>（与真实布局同构，走真实文件系统语义）
        lenient().when(sessionWorkspacePort.mountTarget(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> tempDir.resolve(invocation.getArgument(2, String.class)));
    }

    // ==================== 物化（创建 / 追加） ====================

    @Test
    void should_callPortPerFileResourceAtDerivedTarget_when_materialize_given_mixedResources() {
        // given（file + memory_store 混合批次：仅 file 项物化，落点按挂载路径派生）
        AgentSession session = sessionWith(
                SessionResource.file("file_1", null),
                SessionResource.memoryStore("ms_1", null, null),
                SessionResource.file("file_2", "mounts/dataset/a.csv"));
        stubMaterializeSuccess("file_1");
        stubMaterializeSuccess("file_2");

        // when
        materializer.materialize(session, session.resources());

        // then（两个 file 项各一次端口调用，非 file 项零触达）
        verify(fileMountMaterializationPort).materialize("file_1", 1L, tempDir.resolve("mounts/file_1"));
        verify(fileMountMaterializationPort)
                .materialize("file_2", 1L, tempDir.resolve("mounts/dataset/a.csv"));
    }

    @Test
    void should_compensatePlacedCopies_when_materialize_given_secondItemUnmaterializable() {
        // given（第一项物化成功落盘、第二项门禁拒绝：整批拒绝并补偿删除本轮已物化副本）
        AgentSession session = sessionWith(
                SessionResource.file("file_1", null), SessionResource.file("file_2", null));
        stubMaterializeSuccess("file_1");
        when(fileMountMaterializationPort.materialize(eq("file_2"), anyLong(), any(Path.class)))
                .thenReturn(Optional.empty());

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> materializer.materialize(session, session.resources()));
        assertFalse(Files.exists(tempDir.resolve("mounts/file_1")), "本轮已物化副本必须被补偿删除");
    }

    @Test
    void should_compensateAndPropagate_when_materialize_given_integrityMismatch() {
        // given（摘要不符异常：照常上抛（不允许静默跳过），且先前成功项被补偿）
        AgentSession session = sessionWith(
                SessionResource.file("file_1", null), SessionResource.file("file_2", null));
        stubMaterializeSuccess("file_1");
        when(fileMountMaterializationPort.materialize(eq("file_2"), anyLong(), any(Path.class)))
                .thenThrow(new FileContentIntegrityException("挂载物化 SHA-256 校验不一致: file_2"));

        // when & then
        assertThrows(FileContentIntegrityException.class,
                () -> materializer.materialize(session, session.resources()));
        assertFalse(Files.exists(tempDir.resolve("mounts/file_1")));
    }

    @Test
    void should_doNothing_when_materializeAll_given_noFileMounts() {
        // given（纯 github 挂载会话：物化零触达，不创建宿主目录）
        AgentSession session = sessionWith(
                SessionResource.githubRepository("https://github.com/o/r", "tok", null));

        // when
        materializer.materializeAll(session);

        // then
        verify(fileMountMaterializationPort, never()).materialize(anyString(), any(), any(Path.class));
    }

    // ==================== 装配期对账（reconcile） ====================

    @Test
    void should_trustInPlaceCopyWithoutRematerialize_when_reconcile_given_copyExists() {
        // given（副本在位：不重物化、不重算摘要（D20），视图取轻量元数据）
        AgentSession session = sessionWith(SessionResource.file("file_1", null));
        createHostCopy("mounts/file_1");
        when(fileApi.findReadyMountMeta("file_1", 1L))
                .thenReturn(Optional.of(new FileMountMetaDTO("file_1", "sales.csv", 2048L)));

        // when
        List<AgentAssemblySpec.FileMountRef> view = materializer.reconcile(session, 1L);

        // then（视图字段来自元数据面而非磁盘 stat）
        assertEquals(1, view.size());
        assertEquals(new AgentAssemblySpec.FileMountRef("file_1", "sales.csv", 2048L, "mounts/file_1"),
                view.get(0));
        verify(fileMountMaterializationPort, never()).materialize(anyString(), any(), any(Path.class));
    }

    @Test
    void should_supplementMissingCopy_when_reconcile_given_copyDeletedExternally() {
        // given（宿主副本被外部清理：缺失才补物化，视图照常出行）
        AgentSession session = sessionWith(SessionResource.file("file_1", null));
        when(fileMountMaterializationPort.materialize(eq("file_1"), anyLong(), any(Path.class)))
                .thenAnswer(invocation -> stubSuccess(invocation.getArgument(2)));
        when(fileApi.findReadyMountMeta("file_1", 1L))
                .thenReturn(Optional.of(new FileMountMetaDTO("file_1", "a.txt", 1024L)));

        // when
        List<AgentAssemblySpec.FileMountRef> view = materializer.reconcile(session, 1L);

        // then（补物化一次 + 视图就位）
        verify(fileMountMaterializationPort).materialize("file_1", 1L, tempDir.resolve("mounts/file_1"));
        assertEquals("a.txt", view.get(0).filename());
        assertTrue(Files.exists(tempDir.resolve("mounts/file_1")));
    }

    @Test
    void should_fallbackRowWithFileIdNameAndZeroBytes_when_reconcile_given_metadataRecordDeleted() {
        // given（文件记录已删：补物化不可行、元数据不可得 → 「有记录即有行」回落）
        AgentSession session = sessionWith(SessionResource.file("file_1", null));
        when(fileMountMaterializationPort.materialize(eq("file_1"), anyLong(), any(Path.class)))
                .thenReturn(Optional.empty());
        when(fileApi.findReadyMountMeta("file_1", 1L)).thenReturn(Optional.empty());

        // when
        List<AgentAssemblySpec.FileMountRef> view = materializer.reconcile(session, 1L);

        // then（fileId 回落文件名、体积记 0）
        assertEquals(List.of(new AgentAssemblySpec.FileMountRef(
                "file_1", "file_1", 0L, "mounts/file_1")), view);
    }

    @Test
    void should_keepOrphanCopyUntouched_when_reconcile_given_residueWithoutRecord() {
        // given（无挂载记录的孤儿副本（并发追加中间态 / 崩溃残留）：对账不清理（D20）、不进清单）
        AgentSession session = sessionWith(SessionResource.file("file_1", null));
        createHostCopy("mounts/file_1");
        createHostCopy("mounts/orphan_residue.bin");
        when(fileApi.findReadyMountMeta("file_1", 1L))
                .thenReturn(Optional.of(new FileMountMetaDTO("file_1", "a.txt", 1024L)));

        // when
        List<AgentAssemblySpec.FileMountRef> view = materializer.reconcile(session, 1L);

        // then（孤儿副本保留、视图只含有记录的项）
        assertTrue(Files.exists(tempDir.resolve("mounts/orphan_residue.bin")));
        assertEquals(1, view.size());
    }

    @Test
    void should_propagateIntegrityError_when_reconcile_given_supplementMismatch() {
        // given（补物化摘要不符：一致性异常照常上抛，不静默降级为回落行）
        AgentSession session = sessionWith(SessionResource.file("file_1", null));
        when(fileMountMaterializationPort.materialize(eq("file_1"), anyLong(), any(Path.class)))
                .thenThrow(new FileContentIntegrityException("挂载物化 SHA-256 校验不一致: file_1"));

        // when & then
        assertThrows(FileContentIntegrityException.class, () -> materializer.reconcile(session, 1L));
    }

    // ==================== 副本释放 ====================

    @Test
    void should_deleteHostCopy_when_release_given_fileResourceWithCopy() {
        // given（移除挂载：对应宿主副本被删除）
        AgentSession session = sessionWith(SessionResource.file("file_1", "mounts/reports/q1.pdf"));
        createHostCopy("mounts/reports/q1.pdf");

        // when
        materializer.release(session, session.resources().get(0));

        // then
        assertFalse(Files.exists(tempDir.resolve("mounts/reports/q1.pdf")));
    }

    @Test
    void should_beQuietNoop_when_release_given_missingCopyOrNonFileResource() {
        // given（副本本就不存在 / 非 file 项：均静默成功，不抛不派生落点）
        AgentSession session = sessionWith(SessionResource.file("file_1", null));
        SessionResource memoryMount = SessionResource.memoryStore("ms_1", null, null);

        // when & then
        materializer.release(session, session.resources().get(0));
        materializer.release(session, memoryMount);
        verify(sessionWorkspacePort, never()).cleanup(anyString(), anyString());
    }

    @Test
    void should_delegateNamespaceCleanup_when_releaseAll_given_session() {
        // given（会话删除清理：委托端口递归清理命名空间目录）
        AgentSession session = sessionWith(SessionResource.file("file_1", null));

        // when
        materializer.releaseAll(session);

        // then
        verify(sessionWorkspacePort).cleanup("agent-a", session.sessionId());
    }

    // ==================== 测试脚手架 ====================

    /** 携挂载资源的会话镜像（userId=1、agent-a，与落点派生桩一致）。 */
    private AgentSession sessionWith(SessionResource... resources) {
        return AgentSession.createWithTrigger("1", "agent-a", "1.0.0", "{}", null, null, null,
                List.of(resources));
    }

    /** 桩定指定文件物化成功（真实落盘空副本，供补偿删除断言可见性）。 */
    private void stubMaterializeSuccess(String fileId) {
        when(fileMountMaterializationPort.materialize(eq(fileId), anyLong(), any(Path.class)))
                .thenAnswer(invocation -> stubSuccess(invocation.getArgument(2)));
    }

    private Optional<FileMountMaterializationDTO> stubSuccess(Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.createFile(target);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return Optional.of(new FileMountMaterializationDTO(
                "file_" + target.getFileName(), 1024L, "0".repeat(64), target));
    }

    /** 在派生布局下创建宿主副本文件。 */
    private void createHostCopy(String relativePath) {
        try {
            Path target = tempDir.resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.createFile(target);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
