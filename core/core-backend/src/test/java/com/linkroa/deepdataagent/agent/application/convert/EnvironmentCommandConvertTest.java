package com.linkroa.deepdataagent.agent.application.convert;

import com.linkroa.deepdataagent.agent.application.command.CreateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.controller.request.CreateEnvironmentRequest;
import com.linkroa.deepdataagent.agent.controller.request.SandboxSpecRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateEnvironmentRequest;
import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvironmentCommandConvertTest {

    private SandboxSpecRequest spec(String image, Integer memoryMb, Double cpu, String mode, Integer timeout) {
        return new SandboxSpecRequest(image, memoryMb, cpu, mode, timeout);
    }

    @Test
    void should_parseLocalAndSpec_when_toCreateCommand_given_validRequest() {
        // given
        CreateEnvironmentRequest request = new CreateEnvironmentRequest(
                "本地环境", "LOCAL", spec("ubuntu:22.04", 512, 1.0, "read-only", 300));

        // when
        CreateEnvironmentCommand command = EnvironmentCommandConvert.INSTANCE.toCreateCommand(request);

        // then
        assertEquals(EnvironmentType.LOCAL, command.type());
        assertEquals("ubuntu:22.04", command.sandboxSpec().image());
        assertEquals(512, command.sandboxSpec().memoryMb());
        assertEquals(300, command.sandboxSpec().timeoutSeconds());
    }

    @Test
    void should_rejectNonLocal_when_toCreateCommand_given_remoteType() {
        // given
        CreateEnvironmentRequest request = new CreateEnvironmentRequest(
                "远程环境", "remote", spec("ubuntu:22.04", 512, 1.0, "read-only", 300));

        // when // then
        assertThrows(IllegalArgumentException.class, () -> EnvironmentCommandConvert.INSTANCE.toCreateCommand(request));
    }

    @Test
    void should_bindEnvironmentId_when_toUpdateCommand_given_validRequest() {
        // given
        UpdateEnvironmentRequest request = new UpdateEnvironmentRequest(
                "本地环境", "LOCAL", spec("ubuntu:24.04", 1024, 2.0, "read-write", 600));

        // when
        UpdateEnvironmentCommand command = EnvironmentCommandConvert.INSTANCE.toUpdateCommand("env-9", request);

        // then
        assertEquals("env-9", command.environmentId());
        assertEquals("ubuntu:24.04", command.sandboxSpec().image());
    }
}