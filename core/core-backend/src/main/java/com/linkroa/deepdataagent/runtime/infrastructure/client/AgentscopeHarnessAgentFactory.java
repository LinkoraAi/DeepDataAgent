package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.factory.AgentFactoryPort;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import com.linkroa.deepdataagent.datasource.application.port.DatasourceQueryPort;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.builtin.TodoTools;
import io.agentscope.extensions.postgresql.snapshot.PostgresSnapshotSpec;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AgentScope Harness 组装工厂（{@link AgentFactoryPort} 实现）。
 * <p>采用「每请求构建 + 用后释放」生命周期：{@link #build} 每次装配全新
 * {@link HarnessAgent}（不缓存、无跨请求共享可变状态），调用方在轮次结束后经
 * {@link BuiltAgent#close()} 释放。模型经 {@link #resolveModel} 解析：
 * 台账凭证/API 端点（{@link AgentAssemblySpec} 承载）注入 {@link ModelCreationContext}，
 * 无凭证则交由注册表默认解析。</p>
 * <p>工具/技能按 AgentScope 官方推荐方式接入：工具经 {@link Toolkit#registerTool(Object)}
 * 注册（内置 {@link TodoTools} 作为基座示例）；用户技能经
 * {@code HarnessAgent.Builder#skillRepository(...)} 挂载运行时物化仓储
 * {@link RuntimeSkillRepository}，并关闭框架动态/默认工作区技能以保证仅显式挂载生效。</p>
 */
@Component
public class AgentscopeHarnessAgentFactory implements AgentFactoryPort {

    @Resource
    private AgentRuntimeProperties properties;
    @Resource
    private PostgresAgentStateStore stateStore;
    @Resource
    private PostgresSnapshotSpec snapshotSpec;
    @Resource
    private DatasourceQueryPort datasourceQueryPort;

    @Override
    public BuiltAgent build(AgentAssemblySpec spec) {
        return new HarnessBuiltAgent(spec.agentId(), buildNew(spec));
    }

    private HarnessAgent buildNew(AgentAssemblySpec spec) {
        Toolkit toolkit = buildToolkit(spec.dataSourceIds());

        SandboxFilesystemSpec filesystem = new DockerFilesystemSpec()
                .image(spec.sandbox().image())
                .memorySizeBytes(spec.sandbox().memoryBytes())
                .cpuCount(spec.sandbox().cpuCount())
                .snapshotSpec(snapshotSpec)
                .isolationScope(IsolationScope.SESSION);

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(spec.name())
                .model(resolveModel(spec.model(), spec.credential(), spec.apiEndpointUrl()))
                .maxIters(spec.maxIters())
                .agentId(spec.agentId())
                .toolkit(toolkit)
                .filesystem(filesystem)
                .stateStore(stateStore)
                .skillRepository(new RuntimeSkillRepository(spec.skills()));

        if (StringUtils.isNotBlank(spec.systemPrompt())) {
            builder.sysPrompt(spec.systemPrompt());
        }
        return builder.build();
    }

    /**
     * 按模型访问配置解析提供方模型：注入台账凭证 / API 端点（经
     * {@link ModelCreationContext}），无凭证时退化到注册表默认解析。
     */
    private Model resolveModel(String modelName, String apiKey, String baseUrl) {
        if (StringUtils.isBlank(apiKey) && StringUtils.isBlank(baseUrl)) {
            return ModelRegistry.resolve(modelName);
        }
        ModelCreationContext.Builder context = ModelCreationContext.builder();
        if (StringUtils.isNotBlank(apiKey)) {
            context.apiKey(apiKey);
        }
        if (StringUtils.isNotBlank(baseUrl)) {
            context.baseUrl(baseUrl);
        }
        return ModelRegistry.resolve(modelName, context.build());
    }

    /**
     * 构建工具集：按 AgentScope 官方推荐方式经 {@link Toolkit#registerTool(Object)}
     * 注册工具。基座注册内置 {@link TodoTools}，并在 Agent 版本配置了数据源引用时自动装配
     * 数据源查询工具 {@link DatasourceQueryTool}（查询类工具随数据源引用自动启用，无需用户勾选）。
     * <p>官方内置文件 / Shell 工具（{@code FilesystemTool} / {@code ShellExecuteTool}）不在此
     * 手动注册——{@link HarnessAgent.Builder#build()} 在已设置 {@code filesystem(...)} 且未调用
     * {@code disableFilesystemTools()/disableShellTool()} 时自动注入（{@code javap} 已复核）。
     * 用户技能经 {@code skillRepository} 挂载（见类 javadoc）。</p>
     */
    private Toolkit buildToolkit(List<Long> dataSourceIds) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TodoTools());
        if (!dataSourceIds.isEmpty()) {
            toolkit.registerTool(new DatasourceQueryTool(datasourceQueryPort, dataSourceIds));
        }
        return toolkit;
    }
}