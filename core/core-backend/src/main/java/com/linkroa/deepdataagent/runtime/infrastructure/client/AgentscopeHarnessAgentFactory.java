package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.factory.AgentFactoryPort;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.gateway.AgentToolGateway;
import com.linkroa.deepdataagent.runtime.domain.gateway.AgentToolGateway.ToolDescriptor;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.postgresql.snapshot.PostgresSnapshotSpec;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;

/**
 * AgentScope Harness 组装工厂（{@link AgentFactoryPort} 实现）。
 * <p>采用「每请求构建 + 用后释放」生命周期：{@link #build} 每次装配全新
 * {@link HarnessAgent}（不缓存、无跨请求共享可变状态），调用方在轮次结束后经
 * {@link BuiltAgent#close()} 释放。工具集经 {@link Toolkit#registerAgentTool} 注册
 * {@link AgentToolGateway} 暴露的真实基础工具（非 schema 骨架），工具执行受
 * {@code app.agent.tool-timeout} 超时保护。</p>
 */
@Component
public class AgentscopeHarnessAgentFactory implements AgentFactoryPort {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeHarnessAgentFactory.class);

    @Resource
    private AgentRuntimeProperties properties;
    @Resource
    private PostgresAgentStateStore stateStore;
    @Resource
    private PostgresSnapshotSpec snapshotSpec;
    @Resource
    private AgentToolGateway toolGateway;

    @Override
    public BuiltAgent build(AgentAssemblySpec spec) {
        return new HarnessBuiltAgent(spec.agentId(), buildNew(spec));
    }

    private HarnessAgent buildNew(AgentAssemblySpec spec) {
        Toolkit toolkit = buildToolkit(spec);

        SandboxFilesystemSpec filesystem = new DockerFilesystemSpec()
                .image(spec.sandbox().image())
                .memorySizeBytes(spec.sandbox().memoryBytes())
                .cpuCount(spec.sandbox().cpuCount())
                .snapshotSpec(snapshotSpec)
                .isolationScope(IsolationScope.SESSION);

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(spec.name())
                .description(spec.description())
                .model(spec.model())
                .maxIters(spec.maxIters())
                .agentId(spec.agentId())
                .toolkit(toolkit)
                .filesystem(filesystem)
                .stateStore(stateStore);

        if (StringUtils.isNotBlank(spec.systemPrompt())) {
            builder.sysPrompt(spec.systemPrompt());
        }
        return builder.build();
    }

    /**
     * 构建工具集：遍历网关可用工具，以 {@code registerAgentTool} 注册真实 {@link AgentTool}。
     */
    private Toolkit buildToolkit(AgentAssemblySpec spec) {
        Toolkit toolkit = new Toolkit();
        for (String toolName : spec.toolNames()) {
            try {
                ToolDescriptor descriptor = toolGateway.describe(toolName);
                toolkit.registerAgentTool(new GatewayAgentTool(descriptor, toolGateway, properties.getToolTimeout()));
            } catch (IllegalArgumentException ex) {
                // 装配规格引用了网关未注册的工具：跳过并记录（不阻断 agent 装配）
                log.warn("装配规格引用了未注册工具，已跳过: toolName={}, reason={}", toolName, ex.getMessage());
            }
        }
        return toolkit;
    }

    /**
     * 网关 → { @link AgentTool } 适配器：callAsync 经网关 invoke 同步执行，
     * 异常/超时收敛为 {@code ToolResultBlock.error（ERROR 状态）} 返回，不向上抛。
     */
    private record GatewayAgentTool(ToolDescriptor descriptor, AgentToolGateway gateway, Duration timeout)
            implements AgentTool {

        @Override
        public String getName() {
            return descriptor.name();
        }

        @Override
        public String getDescription() {
            return descriptor.description();
        }

        @Override
        public Map<String, Object> getParameters() {
            return descriptor.parameters();
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> {
                        String output = gateway.invoke(getName(), param.getInput() != null ? param.getInput() : Map.of());
                        return ToolResultBlock.text(output);
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(timeout)
                    .onErrorResume(ex -> Mono.just(ToolResultBlock.error(
                            "工具执行失败: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()))));
        }
    }
}