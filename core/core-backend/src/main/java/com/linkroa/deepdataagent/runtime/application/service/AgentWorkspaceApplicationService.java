package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.runtime.controller.response.AgentWorkspaceResponse;
import com.linkroa.deepdataagent.shared.config.OpenSandboxProperties;
import com.linkroa.deepdataagent.shared.config.SqliteProperties;
import jakarta.annotation.Resource;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AgentWorkspaceApplicationService {

    private static final List<String> BOUNDED_CONTEXTS = List.of("agent", "skills", "memory", "datasource");

    @Resource
    private OpenSandboxProperties openSandboxProperties;
    @Resource
    private SqliteProperties sqliteProperties;

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
