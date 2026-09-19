package com.linkroa.deepdataagent.file.infrastructure.assembly;

import com.linkroa.deepdataagent.file.application.port.FileContentPort;
import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.model.FileScope;
import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;
import com.linkroa.deepdataagent.file.domain.repository.FileRepository;
import com.linkroa.deepdataagent.file.domain.service.FileContentTypeDomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行时产出物写入端口实现单元测试：产出三态白名单登记（scope / downloadable 派生）、
 * 输入拒绝与 Session 删除 scope 清理（逐文件删除、失败继续）。
 */
@ExtendWith(MockitoExtension.class)
class DefaultArtifactRegistrationPortTest {

    private static final String SESSION_ID = "sess_1";
    private static final Long OWNER_ID = 1L;
    private static final byte[] CONTENT = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);

    @Mock
    private FileRepository fileRepository;
    @Mock
    private FileContentPort fileContentPort;

    private DefaultArtifactRegistrationPort port;

    @BeforeEach
    void setUp() {
        port = new DefaultArtifactRegistrationPort();
        ReflectionTestUtils.setField(port, "fileRepository", fileRepository);
        ReflectionTestUtils.setField(port, "fileContentPort", fileContentPort);
        // 领域服务无状态无依赖，使用真实实例贴合 MIME 探测与文本准入行为
        ReflectionTestUtils.setField(port, "contentTypeDomainService", new FileContentTypeDomainService());
    }

    // ==================== registerSessionArtifact ====================

    @Test
    void should_registerToolOutputWithSessionScope_when_registerSessionArtifact_given_toolOutputPurpose() {
        // given
        ArgumentCaptor<File> captor = ArgumentCaptor.forClass(File.class);

        // when
        String fileId = port.registerSessionArtifact(SESSION_ID, OWNER_ID, "tool_output", "result.csv", CONTENT);

        // then
        verify(fileContentPort).register(captor.capture(), eq(CONTENT));
        File saved = captor.getValue();
        assertThat(fileId).isEqualTo(saved.fileId());
        assertThat(fileId).startsWith(File.FILE_ID_PREFIX);
        assertThat(saved.ownerId()).isEqualTo(OWNER_ID);
        assertThat(saved.purpose()).isEqualTo(FilePurpose.TOOL_OUTPUT);
        assertThat(saved.downloadable()).isTrue();
        assertThat(saved.scope()).isEqualTo(new FileScope(SESSION_ID, FileScope.TYPE_SESSION));
        assertThat(saved.mimeType()).isEqualTo("text/csv");
        assertThat(saved.sizeBytes()).isEqualTo(CONTENT.length);
    }

    @Test
    void should_registerAgentOutputNotDownloadable_when_registerSessionArtifact_given_agentOutputPurpose() {
        // given
        ArgumentCaptor<File> captor = ArgumentCaptor.forClass(File.class);

        // when
        port.registerSessionArtifact(SESSION_ID, OWNER_ID, "agent_output", "summary.md", CONTENT);

        // then
        verify(fileContentPort).register(captor.capture(), any(byte[].class));
        assertThat(captor.getValue().purpose()).isEqualTo(FilePurpose.AGENT_OUTPUT);
        assertThat(captor.getValue().downloadable()).isFalse();
    }

    @Test
    void should_throwIllegalArgument_when_registerSessionArtifact_given_userUploadPurpose() {
        // given / when / then
        assertThatThrownBy(() ->
                port.registerSessionArtifact(SESSION_ID, OWNER_ID, "user_upload", "a.txt", CONTENT))
                .isInstanceOf(IllegalArgumentException.class);
        verify(fileContentPort, never()).register(any(), any());
    }

    @Test
    void should_throwIllegalArgument_when_registerSessionArtifact_given_sessionResourcePurpose() {
        // given / when / then
        assertThatThrownBy(() ->
                port.registerSessionArtifact(SESSION_ID, OWNER_ID, "session_resource", "a.txt", CONTENT))
                .isInstanceOf(IllegalArgumentException.class);
        verify(fileContentPort, never()).register(any(), any());
    }

    @Test
    void should_throwIllegalArgument_when_registerSessionArtifact_given_blankSessionIdOrFilename() {
        // given / when / then
        assertThatThrownBy(() ->
                port.registerSessionArtifact(" ", OWNER_ID, "tool_output", "a.txt", CONTENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                port.registerSessionArtifact(SESSION_ID, OWNER_ID, "tool_output", " ", CONTENT))
                .isInstanceOf(IllegalArgumentException.class);
        verify(fileContentPort, never()).register(any(), any());
    }

    @Test
    void should_throwIllegalArgument_when_registerSessionArtifact_given_emptyOrNullContent() {
        // given / when / then
        assertThatThrownBy(() ->
                port.registerSessionArtifact(SESSION_ID, OWNER_ID, "tool_output", "a.txt", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                port.registerSessionArtifact(SESSION_ID, OWNER_ID, "tool_output", "a.txt", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(fileContentPort, never()).register(any(), any());
    }

    @Test
    void should_registerBinaryArtifact_when_registerSessionArtifact_given_pngBytes() {
        // given：真实二进制字节（PNG 魔数 + NUL，产出登记面不做文本性准入）
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 1, 2, 3};
        ArgumentCaptor<File> captor = ArgumentCaptor.forClass(File.class);

        // when
        port.registerSessionArtifact(SESSION_ID, OWNER_ID, "tool_output", "chart.png", png);

        // then：按产出面扩展名映射登记为 image/png
        verify(fileContentPort).register(captor.capture(), eq(png));
        assertThat(captor.getValue().mimeType()).isEqualTo("image/png");
    }

    @Test
    void should_fallbackToOctetStream_when_registerSessionArtifact_given_unknownExtensionBytes() {
        // given：未知扩展名 + 非 UTF-8 字节（产出面兜底缺省 MIME）
        byte[] raw = new byte[] {(byte) 0xC3, 0x28, 0x00};
        ArgumentCaptor<File> captor = ArgumentCaptor.forClass(File.class);

        // when
        port.registerSessionArtifact(SESSION_ID, OWNER_ID, "tool_output", "mystery.qqq", raw);

        // then
        verify(fileContentPort).register(captor.capture(), eq(raw));
        assertThat(captor.getValue().mimeType()).isEqualTo("application/octet-stream");
    }

    // ==================== deleteSessionArtifacts ====================

    @Test
    void should_deleteAllScopedFiles_when_deleteSessionArtifacts_given_twoArtifacts() {
        // given
        when(fileRepository.findByScope(SESSION_ID))
                .thenReturn(List.of(artifact("file_a"), artifact("file_b")));
        when(fileContentPort.deleteFile(anyString())).thenReturn(1);

        // when
        int deleted = port.deleteSessionArtifacts(SESSION_ID);

        // then
        assertThat(deleted).isEqualTo(2);
        verify(fileContentPort).deleteFile("file_a");
        verify(fileContentPort).deleteFile("file_b");
    }

    @Test
    void should_continueDeleting_when_deleteSessionArtifacts_given_singleFileFailure() {
        // given：第一个文件删除抛异常，第二个仍须尝试清理
        when(fileRepository.findByScope(SESSION_ID))
                .thenReturn(List.of(artifact("file_a"), artifact("file_b")));
        when(fileContentPort.deleteFile("file_a")).thenThrow(new RuntimeException("store down"));
        when(fileContentPort.deleteFile("file_b")).thenReturn(1);

        // when
        int deleted = port.deleteSessionArtifacts(SESSION_ID);

        // then
        assertThat(deleted).isEqualTo(1);
        verify(fileContentPort).deleteFile("file_b");
    }

    @Test
    void should_deleteNothing_when_deleteSessionArtifacts_given_noScopedFiles() {
        // given
        when(fileRepository.findByScope(SESSION_ID)).thenReturn(List.of());

        // when
        int deleted = port.deleteSessionArtifacts(SESSION_ID);

        // then
        assertThat(deleted).isZero();
        verify(fileContentPort, never()).deleteFile(anyString());
    }

    @Test
    void should_skipQuery_when_deleteSessionArtifacts_given_blankSessionId() {
        // given / when
        int deleted = port.deleteSessionArtifacts(" ");

        // then
        assertThat(deleted).isZero();
        verify(fileRepository, never()).findByScope(anyString());
    }

    /** 构造已登记的会话产出文件聚合。 */
    private static File artifact(String fileId) {
        return File.create(fileId, OWNER_ID, "out.csv", "text/csv",
                FilePurpose.TOOL_OUTPUT, FileScope.ofSession(SESSION_ID), null, CONTENT);
    }
}
