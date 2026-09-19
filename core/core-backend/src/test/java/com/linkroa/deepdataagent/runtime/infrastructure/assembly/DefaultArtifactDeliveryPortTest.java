package com.linkroa.deepdataagent.runtime.infrastructure.assembly;

import com.linkroa.deepdataagent.file.application.port.ArtifactRegistrationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultArtifactDeliveryPort} 产出投递端口实现单测：登记 / 清理两法
 * 纯委托 file BC {@code ArtifactRegistrationPort}（fix-runtime-layering 4.2），
 * 参数原样透传、返回值透传。
 */
@ExtendWith(MockitoExtension.class)
class DefaultArtifactDeliveryPortTest {

    private static final String SESSION_ID = "sess_1";
    private static final Long OWNER_ID = 1L;

    @Mock
    private ArtifactRegistrationPort artifactRegistrationPort;

    private DefaultArtifactDeliveryPort port;

    @BeforeEach
    void setUp() {
        port = new DefaultArtifactDeliveryPort();
        ReflectionTestUtils.setField(port, "artifactRegistrationPort", artifactRegistrationPort);
    }

    @Test
    void should_delegateRegisterWithPassthroughArgs_when_registerSessionArtifact_given_artifactParams() {
        // given
        byte[] content = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
        when(artifactRegistrationPort.registerSessionArtifact(
                SESSION_ID, OWNER_ID, "tool_output", "out.csv", content)).thenReturn("file_9");

        // when
        String fileId = port.registerSessionArtifact(SESSION_ID, OWNER_ID, "tool_output", "out.csv", content);

        // then（委托参数逐位一致）
        assertEquals("file_9", fileId);
        verify(artifactRegistrationPort).registerSessionArtifact(
                SESSION_ID, OWNER_ID, "tool_output", "out.csv", content);
    }

    @Test
    void should_delegateDeleteAndReturnCount_when_deleteSessionArtifacts_given_sessionId() {
        // given
        when(artifactRegistrationPort.deleteSessionArtifacts(SESSION_ID)).thenReturn(3);

        // when / then
        assertEquals(3, port.deleteSessionArtifacts(SESSION_ID));
        verify(artifactRegistrationPort).deleteSessionArtifacts(SESSION_ID);
    }
}
