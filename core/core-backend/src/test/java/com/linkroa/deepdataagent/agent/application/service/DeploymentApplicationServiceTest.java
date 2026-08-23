package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.command.CreateDeploymentCommand;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.domain.model.Deployment;
import com.linkroa.deepdataagent.agent.domain.repository.AgentDefinitionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.DeploymentRepository;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeploymentApplicationServiceTest {

    @Mock private DeploymentRepository deploymentRepository;
    @Mock private AgentDefinitionRepository agentDefinitionRepository;
    @Mock private AgentVersionRepository agentVersionRepository;
    @Mock private TransactionTemplate transactionTemplate;

    private DeploymentApplicationService service;

    @BeforeEach
    void setUp() {
        service = new DeploymentApplicationService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "deploymentRepository", deploymentRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "agentDefinitionRepository", agentDefinitionRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "agentVersionRepository", agentVersionRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
    }

    private AgentDefinition buildDefinition() {
        return AgentDefinition.restore(1L, "agent-1", "agent", null, false, null, 1, 1,
                "default", null, null, null, null);
    }

    @Test
    void should_activateVersion_when_deploy_given_existingVersion() {
        // given
        AgentVersion version = AgentVersion.create("v-2", "agent-1", 2, "v2", null, "sys",
                "mp-1", null, null, null, null, null, "default");
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1")).thenReturn(Optional.of(buildDefinition()));
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-1", 2)).thenReturn(Optional.of(version));
        when(deploymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Deployment deployment = service.deploy(new CreateDeploymentCommand("agent-1", 2));

        // then
        assertEquals("agent-1", deployment.agentId());
        assertEquals(2, deployment.versionNumber());
        verify(agentDefinitionRepository).updateActiveVersion("agent-1", 2);
    }

    @Test
    void should_throwNotFound_when_deploy_given_missingVersion() {
        // given
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1")).thenReturn(Optional.of(buildDefinition()));
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-1", 99)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.deploy(new CreateDeploymentCommand("agent-1", 99)));
        verify(deploymentRepository, never()).save(any());
        verify(agentDefinitionRepository, never()).updateActiveVersion(anyString(), anyInt());
    }
}