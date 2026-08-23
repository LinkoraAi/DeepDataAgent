package com.linkroa.deepdataagent.agent.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentTest {

    @Test
    void should_createDeployment_when_create_given_validFields() {
        // given // when
        Deployment deployment = Deployment.create("dep-1", "agent-1", 3, "ws-default");

        // then
        assertEquals("dep-1", deployment.deploymentId());
        assertEquals("agent-1", deployment.agentId());
        assertEquals(3, deployment.versionNumber());
        assertEquals("ws-default", deployment.workspaceId());
    }

    @Test
    void should_throwException_when_create_given_blankAgentId() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Deployment.create("dep-1", " ", 3, "ws-default"));
    }

    @Test
    void should_throwException_when_create_given_nonPositiveVersionNumber() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Deployment.create("dep-1", "agent-1", 0, "ws-default"));
    }
}