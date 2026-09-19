package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.application.port.ArtifactDeliveryPort;
import com.linkroa.deepdataagent.runtime.application.port.ArtifactDeliverySignalPort;
import com.linkroa.deepdataagent.runtime.domain.event.ArtifactDeliveredSignal;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionArtifactDeliveryTarget} 会话产出交付目标单测
 * （Harness 2.0.3 artifactDeliveryTarget SPI 的平台侧实现）。
 * <p>覆盖：成功登记（purpose 恒 tool_output、归属来自装配闭包、文件名取框架校验值）/
 * 嵌套相对路径登记 / 路径逃逸拒绝（绝对 / 越界，不触碰登记端口）/
 * 端口准入错误转失败结果 / 会话累计超配额拒绝 / 登记成功上抛交付信号（含内容类型探测）/
 * 交付信号上抛异常不改写交付结果。</p>
 */
class SessionArtifactDeliveryTargetTest {

    private final ArtifactDeliveryPort port = mock(ArtifactDeliveryPort.class);
    private final ArtifactDeliverySignalPort signalPort = mock(ArtifactDeliverySignalPort.class);

    @AfterEach
    void clearQuotaTable() {
        // 静态配额表跨用例隔离，避免种子数据污染其他会话
        SessionArtifactDeliveryTarget.SESSION_DELIVERED_BYTES.clear();
    }

    private SessionArtifactDeliveryTarget target(String sessionId, Long ownerId) {
        return new SessionArtifactDeliveryTarget(port, signalPort, sessionId, ownerId);
    }

    private ArtifactDeliveryRequest request(String filePath, byte[] content, String fileName) {
        return new ArtifactDeliveryRequest(filePath, content, fileName, null, false);
    }

    @Test
    void should_registerToolOutputArtifactAndReturnSuccess_when_deliver_given_validRelativePath() {
        // given（框架已下载 report.csv 字节，端口登记返回 file_ id）
        String sessionId = "sess_deliver_ok";
        byte[] content = "col1,col2\n".getBytes(StandardCharsets.UTF_8);
        when(port.registerSessionArtifact(eq(sessionId), eq(7L), anyString(), anyString(), any()))
                .thenReturn("file_abc123");
        SessionArtifactDeliveryTarget target = target(sessionId, 7L);

        // when
        ArtifactDeliveryResult result = target.deliver(null,
                request("report.csv", content, "report.csv"));

        // then（purpose 恒 tool_output，归属 sessionId / ownerId 来自装配上下文，成功详情含 file_ id 与字节数）
        ArgumentCaptor<String> purposeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> filenameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(port).registerSessionArtifact(eq(sessionId), eq(7L),
                purposeCaptor.capture(), filenameCaptor.capture(), contentCaptor.capture());
        assertEquals("tool_output", purposeCaptor.getValue());
        assertEquals("report.csv", filenameCaptor.getValue());
        assertEquals("col1,col2\n", new String(contentCaptor.getValue(), StandardCharsets.UTF_8));
        assertTrue(result.successful());
        assertFalse(result.conflict());
        assertTrue(result.message().contains("file_abc123"));
        assertTrue(result.message().contains(content.length + " bytes"));
    }

    @Test
    void should_registerWithGivenFileName_when_deliver_given_nestedRelativePath() {
        // given（嵌套 workspace 相对路径，框架已按 basename 给出纯文件名；属主可空）
        String sessionId = "sess_deliver_nested";
        byte[] content = "# summary".getBytes(StandardCharsets.UTF_8);
        when(port.registerSessionArtifact(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn("file_nested");
        SessionArtifactDeliveryTarget target = target(sessionId, null);

        // when
        ArtifactDeliveryResult result = target.deliver(null,
                request("out/summary.md", content, "summary.md"));

        // then（登记文件名取框架校验值，ownerId 透传 null）
        verify(port).registerSessionArtifact(eq(sessionId), eq(null), eq("tool_output"),
                eq("summary.md"), any());
        assertTrue(result.successful());
    }

    @Test
    void should_failWithoutRegistering_when_deliver_given_parentEscapePath() {
        // given（框架 normalizer 对越界相对路径原样透传：../etc/passwd）
        SessionArtifactDeliveryTarget target = target("sess_deliver_escape", 7L);

        // when
        ArtifactDeliveryResult result = target.deliver(null,
                request("../etc/passwd", "x".getBytes(StandardCharsets.UTF_8), "passwd"));

        // then（目标层逃逸拒绝，不登记）
        assertFalse(result.successful());
        assertTrue(result.error().contains("路径非法"));
        verify(port, never()).registerSessionArtifact(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void should_failWithoutRegistering_when_deliver_given_absolutePath() {
        // given（框架 normalizer 允许活动文件系统上的绝对路径：/etc/passwd 会原样透传）
        SessionArtifactDeliveryTarget target = target("sess_deliver_abs", 7L);

        // when
        ArtifactDeliveryResult result = target.deliver(null,
                request("/etc/passwd", "x".getBytes(StandardCharsets.UTF_8), "passwd"));

        // then（登记前二次逃逸校验拒绝，字节不离开沙箱边界）
        assertFalse(result.successful());
        assertTrue(result.error().contains("路径非法"));
        verify(port, never()).registerSessionArtifact(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void should_returnReadableFailure_when_deliver_given_portRejectsBinary() {
        // given（二进制内容被登记面准入规则拒绝，抛 IllegalArgumentException）
        byte[] binary = new byte[]{0x00, 0x01, 0x02};
        when(port.registerSessionArtifact(anyString(), any(), anyString(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("文件内容非文本，不支持登记为会话产物"));
        SessionArtifactDeliveryTarget target = target("sess_deliver_binary", 7L);

        // when
        ArtifactDeliveryResult result = target.deliver(null,
                request("blob.bin", binary, "blob.bin"));

        // then（端口准入错误转可读失败，不抛穿；失败交付不占配额）
        assertFalse(result.successful());
        assertFalse(result.conflict());
        assertTrue(result.error().contains("非文本"));
    }

    @Test
    void should_failWithoutRegistering_when_deliver_given_sessionQuotaExceeded() {
        // given（预灌配额表：该会话已交付至配额上限前 10 字节，本次 20 字节必然超限）
        String sessionId = "sess_deliver_quota";
        SessionArtifactDeliveryTarget.SESSION_DELIVERED_BYTES
                .computeIfAbsent(sessionId, k -> new java.util.concurrent.atomic.AtomicLong())
                .set(SessionArtifactDeliveryTarget.MAX_SESSION_DELIVERY_BYTES - 10);
        SessionArtifactDeliveryTarget target = target(sessionId, 7L);

        // when
        ArtifactDeliveryResult result = target.deliver(null,
                request("big.csv", "01234567890123456789".getBytes(StandardCharsets.UTF_8), "big.csv"));

        // then（超配额拒绝，端口不被调用）
        assertFalse(result.successful());
        assertTrue(result.error().contains("超过配额"));
        verify(port, never()).registerSessionArtifact(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void should_publishDeliveredSignalWithResolvedContentType_when_deliver_given_registrationSucceeds() {
        // given（登记成功返回 file_ id；文件名扩展名参与内容类型探测）
        String sessionId = "sess_deliver_signal";
        byte[] content = "# 报告".getBytes(StandardCharsets.UTF_8);
        when(port.registerSessionArtifact(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn("file_signal1");
        SessionArtifactDeliveryTarget target = target(sessionId, 7L);

        // when
        ArtifactDeliveryResult result = target.deliver(null,
                request("report.md", content, "report.md"));

        // then（上抛交付信号：会话 / 文件 ID / 原始文件名 / 字节数 / 按扩展名探测的 content_type）
        ArgumentCaptor<ArtifactDeliveredSignal> signalCaptor = ArgumentCaptor.forClass(ArtifactDeliveredSignal.class);
        verify(signalPort).publish(signalCaptor.capture());
        ArtifactDeliveredSignal signal = signalCaptor.getValue();
        assertEquals(sessionId, signal.sessionId());
        assertEquals("file_signal1", signal.fileId());
        assertEquals("report.md", signal.originalFilename());
        assertEquals(content.length, signal.sizeBytes());
        assertEquals("text/markdown", signal.contentType());
        assertTrue(result.successful());
    }

    @Test
    void should_returnSuccess_when_deliver_given_signalPortThrows() {
        // given（登记成功但事件上抛异常：交付结果 MUST NOT 被改写为失败）
        byte[] content = "a,b\n".getBytes(StandardCharsets.UTF_8);
        when(port.registerSessionArtifact(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn("file_signal2");
        doThrow(new IllegalStateException("会话上下文不在场")).when(signalPort).publish(any());
        SessionArtifactDeliveryTarget target = target("sess_deliver_signal_fail", 7L);

        // when
        ArtifactDeliveryResult result = target.deliver(null,
                request("data.csv", content, "data.csv"));

        // then（交付仍报成功，配额照常累加）
        assertTrue(result.successful());
        assertTrue(result.message().contains("file_signal2"));
    }
}
