package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.agent.application.contract.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.agent.application.port.AgentVersionAssemblyPort;
import com.linkroa.deepdataagent.runtime.application.assembler.AgentAssemblyAssembler;
import com.linkroa.deepdataagent.runtime.domain.gateway.AgentToolGateway;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.ModelAccess;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * 运行时 Agent 装配解析服务：按会话绑定的 agentId + 发布号实时装配
 * {@link AgentAssemblySpec} 与模型访问配置 {@link ModelAccess}（无全局回退，装配完全来自 Agent 台账）。
 * <p>装配来源：system ← {@code agent_version.system}；model ← profile(api_format+model_name)；
 * maxIters ← profile.tool_call_rounds；工具集 ← {@link AgentToolGateway}；
 * 沙箱/工作区等运行时基础设施参数仍取 {@link AgentRuntimeProperties}；
 * 凭证/API 端点经 {@link ModelAccess} 注入运行时工厂装配配置（不进组装规格）。
 * 契约获取经 {@link AgentVersionAssemblyPort}（返回发布语言 DTO），DTO → 装配规格
 * 由 {@link AgentAssemblyAssembler}（防腐映射）完成。</p>
 */
@Service
public class RuntimeAgentAssemblyResolver {

    @Resource
    private AgentVersionAssemblyPort agentVersionAssemblyPort;
    @Resource
    private AgentAssemblyAssembler agentAssemblyAssembler;
    @Resource
    private AgentRuntimeProperties properties;
    @Resource
    private AgentToolGateway toolGateway;

    /**
     * 校验一次 agentId + 发布号（会话创建前置校验链，不执行凭证解密）：
     * 发布号非十进制 / Agent 不存在或已归档 / 版本不存在 / profile 缺失 → 404。
     */
    public void assertResolvable(String agentId, String versionNumber) {
        agentVersionAssemblyPort.assertResolvable(agentId, versionNumber);
    }

    /**
     * 会话 → 装配规格 + 模型访问配置（每次构建实时解析，profile 改动对后续轮次生效）。
     *
     * @return 装配规格（领域模型）与模型访问配置（凭证/API 端点，仅入工厂装配，不参与持久化）
     */
    public AssembledAssembly assemble(AgentSession session) {
        ResolvedAgentAssemblyDTO resolved = agentVersionAssemblyPort.resolve(
                session.agentId(), session.agentVersion());
        return new AssembledAssembly(
                agentAssemblyAssembler.toSpec(
                        resolved,
                        toolGateway.availableToolNames().stream().sorted().toList(),
                        AgentAssemblySpec.Sandbox.of(
                                properties.getSandboxImage(),
                                properties.getSandboxMemoryBytes(),
                                properties.getSandboxCpuCount()
                        )
                ),
                ModelAccess.of(resolved.credential(), resolved.apiEndpointUrl())
        );
    }

    /**
     * 装配结果：领域规格 + 运行时工厂装配所需的模型访问配置。
     */
    public record AssembledAssembly(AgentAssemblySpec spec, ModelAccess modelAccess) {
    }
}