package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.dto.WorkspaceDTO;
import com.linkroa.deepdataagent.shared.config.OpenSandboxProperties;
import com.linkroa.deepdataagent.shared.config.SqliteProperties;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 工作区应用服务
 * <p>负责工作区信息的查询，返回应用层 DTO {@link WorkspaceDTO}，
 * 由控制器层转换为 {@link com.linkroa.deepdataagent.agent.controller.response.AgentWorkspaceResponse}。</p>
 */
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

    public WorkspaceDTO describeWorkspace() {
        return new WorkspaceDTO(
                "DeepDataAgent",
                BOUNDED_CONTEXTS,
                true,
                openSandboxProperties.isUseServerProxy(),
                sqliteProperties.getPath()
        );
    }
}
