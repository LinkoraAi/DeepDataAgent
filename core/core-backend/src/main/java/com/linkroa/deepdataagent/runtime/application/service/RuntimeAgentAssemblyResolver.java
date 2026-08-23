package com.linkroa.deepdataagent.runtime.application.service;

import com.linkroa.deepdataagent.agent.application.contract.EnvironmentReferenceDTO;
import com.linkroa.deepdataagent.agent.application.contract.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.agent.application.port.AgentVersionAssemblyPort;
import com.linkroa.deepdataagent.memory.application.contract.MemoryStoreReferenceDTO;
import com.linkroa.deepdataagent.runtime.application.convert.AgentAssemblyConvert;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.MemoryStoreRef;
import com.linkroa.deepdataagent.runtime.domain.model.Skill;
import com.linkroa.deepdataagent.runtime.infrastructure.client.SkillPackageMaterializer;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 运行时 Agent 装配解析服务：按会话绑定的 agentId + 发布号实时装配
 * {@link AgentAssemblySpec}（无全局回退，装配完全来自 Agent 台账）。
 * <p>装配来源：system ← {@code agent_version.system}；model ← profile(api_format+model_name)；
 * maxIters ← profile.tool_call_rounds；
 * 沙箱/工作区等运行时基础设施参数仍取 {@link AgentRuntimeProperties}；
 * 凭证/API 端点作为工厂装配参数一并映射进装配规格。
 * 契约获取经 {@link AgentVersionAssemblyPort}（返回发布语言 DTO），DTO → 装配规格
 * 由 {@link AgentAssemblyConvert}（防腐映射）完成。</p>
 */
@Service
public class RuntimeAgentAssemblyResolver {

    @Resource
    private AgentVersionAssemblyPort agentVersionAssemblyPort;
    @Resource
    private SkillPackageMaterializer skillPackageMaterializer;
    @Resource
    private AgentRuntimeProperties properties;

    /**
     * 校验一次 agentId + 发布号（会话创建前置校验链，不执行凭证解密）：
     * 发布号非十进制 / Agent 不存在或已归档 / 版本不存在 / profile 缺失 → 404。
     */
    public void assertResolvable(String agentId, String versionNumber) {
        agentVersionAssemblyPort.assertResolvable(agentId, versionNumber);
    }

    /**
     * 解析 Agent 当前最新发布号（会话创建仅绑定 agent 时锁定最新版本快照用）。
     */
    public String latestVersionNumber(String agentId) {
        return agentVersionAssemblyPort.latestVersionNumber(agentId);
    }

    /**
     * 解析 Agent 当前激活版本号（会话创建省略版本号时物化激活版本用）。
     */
    public String activeVersionNumber(String agentId) {
        return agentVersionAssemblyPort.activeVersionNumber(agentId);
    }

    /**
     * 会话 → 装配规格（每次构建实时解析，profile 改动对后续轮次生效）。
     *
     * @return 装配规格（领域值对象，含凭证/API 端点，仅入工厂装配，不参与持久化）
     */
    public AgentAssemblySpec assemble(AgentSession session) {
        ResolvedAgentAssemblyDTO resolved = agentVersionAssemblyPort.resolve(
                session.agentId(), session.agentVersion());
        List<Skill> skills = skillPackageMaterializer.materialize(resolved.skills());
        List<MemoryStoreRef> memoryStoreRefs = toMemoryStoreRefs(resolved.memoryStores());
        return AgentAssemblyConvert.INSTANCE.toSpec(
                resolved,
                toSandbox(resolved.environment()),
                skills,
                memoryStoreRefs
        );
    }

    /**
     * 版本引用环境 → 运行时沙箱规格（未引用回退全局默认规格）。
     * <p>环境规格以 MB / 双精度 CPU 存储，运行时沙箱以字节 / 整核表达，此处做单位换算；
     * 未显式配置（0 或非正）的维度回退为「不限制」（null）。</p>
     */
    private AgentAssemblySpec.Sandbox toSandbox(EnvironmentReferenceDTO environment) {
        if (environment == null) {
            return AgentAssemblySpec.Sandbox.of(
                    properties.getSandboxImage(),
                    properties.getSandboxMemoryBytes(),
                    properties.getSandboxCpuCount());
        }
        Long memoryBytes = environment.memoryMb() > 0 ? environment.memoryMb() * 1024L * 1024L : null;
        Long cpuCount = environment.cpu() > 0 ? Math.max(1L, Math.round(environment.cpu())) : null;
        return AgentAssemblySpec.Sandbox.of(environment.image(), memoryBytes, cpuCount);
    }

    /** memory 契约引用 → runtime 领域记忆库引用值对象（未引用返回空）。 */
    private List<MemoryStoreRef> toMemoryStoreRefs(List<MemoryStoreReferenceDTO> stores) {
        if (stores == null) {
            return List.of();
        }
        return stores.stream()
                .map(store -> new MemoryStoreRef(store.memoryStoreId(), store.name(), store.type()))
                .toList();
    }
}