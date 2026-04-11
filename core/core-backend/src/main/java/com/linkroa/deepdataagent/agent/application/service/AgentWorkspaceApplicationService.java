package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.controller.response.AgentWorkspaceResponse;
import com.linkroa.deepdataagent.shared.config.OpenSandboxProperties;
import com.linkroa.deepdataagent.shared.config.SqliteProperties;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AgentWorkspaceApplicationService {

    private static final List<String> BOUNDED_CONTEXTS = List.of("agent", "skills", "memory", "datasource");

    private final OpenSandboxProperties openSandboxProperties;
    private final SqliteProperties sqliteProperties;

    public AgentWorkspaceApplicationService(
            OpenSandboxProperties openSandboxProperties,
            SqliteProperties sqliteProperties
    ) {
        this.openSandboxProperties = openSandboxProperties;
        this.sqliteProperties = sqliteProperties;
    }

    public AgentWorkspaceResponse describeWorkspace() {
        return new AgentWorkspaceResponse(
                "DeepDataAgent",
                BOUNDED_CONTEXTS,
                true,
                openSandboxProperties.isUseServerProxy(),
                sqliteProperties.getPath()
        );
    }
}
