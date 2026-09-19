package com.linkroa.deepdataagent.runtime.application.service.assembly;

import com.linkroa.deepdataagent.agent.application.dto.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.agent.application.dto.ResolvedModelCredentialDTO;
import com.linkroa.deepdataagent.agent.application.port.AgentVersionAssemblyPort;
import com.linkroa.deepdataagent.memory.api.MemoryStoreApi;
import com.linkroa.deepdataagent.memory.api.dto.MemoryStoreReferenceDTO;
import com.linkroa.deepdataagent.runtime.application.convert.AgentAssemblyConvert;
import com.linkroa.deepdataagent.runtime.application.convert.SkillAssemblyConvert;
import com.linkroa.deepdataagent.runtime.application.service.UserIds;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.ModelBinding;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.MountView;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.VaultCredentialRef;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.MemoryStoreRef;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.Skill;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import com.linkroa.deepdataagent.vault.application.dto.ResolvedVaultCredentialDTO;
import com.linkroa.deepdataagent.vault.application.port.VaultCredentialResolutionPort;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 运行时 Agent 装配解析服务：按会话绑定的 agentId + 发布号 + Session 挂载实时装配
 * {@link AgentAssemblySpec}（无全局回退，装配完全来自 Agent 台账与 Session 挂载）。
 * <p>装配来源：system ← {@code agent_version.system}；model ← profile(api_format+model_name)；
 * maxIters ← profile.tool_call_rounds；
 * 记忆库引用 / 保管库凭证 / 会话环境变量 ← Session 挂载
 * （经 {@link MemoryStoreApi} / {@link VaultCredentialResolutionPort}
 * 显式 owner 解析，异步链路不回退线程上下文；执行平面自托管守卫已在创建会话期单点完成，
 * 装配期不再复检环境类型 {@code environment_id} 创建后不可变更，见 D16）；
 * 沙箱资源 / 工作区等运行时基础设施参数恒取 {@link AgentRuntimeProperties} 全局默认
 * （环境契约不再承载沙箱明细，见 {@code toSandbox} 注记）；
 * 凭证/API 端点作为工厂装配参数一并映射进装配规格。
 * 契约获取经 {@link AgentVersionAssemblyPort}（返回发布语言 DTO），DTO → 装配规格
 * 由 {@link AgentAssemblyConvert}（防腐映射）完成。</p>
 * <p><b>装配缓存（slim-agent-assembly R14 / D2）</b>：版本为不可变快照，装配结果按会话级键缓存以复用
 * 昂贵的技能正文物化与挂载对账。<b>缓存值为无凭证明文快照 {@link CachedAssemblySnapshot}</b>——
 * 解密后的模型 {@code credential} / {@code apiEndpointUrl} 与保管库明文材料一律<b>不进缓存</b>；
 * 命中路径每轮经 {@link AgentVersionAssemblyPort#resolveModelCredential} 实时材料化凭据段、
 * 并按会话 vaultIds 每轮经 {@link VaultCredentialResolutionPort#resolveVaultCredentials} 取保管库材料，
 * 用 Builder 合成最终规格。淘汰为 {@link AssemblyLruCache}（满 128 逐出最久未访问单条），TTL 60s。</p>
 * <p>decompose-command-facade 4.5：类名由 Resolver 正名为 Service——本类型承载应用层装配用例编排
 * （含装配 LRU 缓存与凭证实时材料化），符合命名约定的 {@code XxxService}；
 * 5.1 已归位 {@code application.service.assembly} 子包（同包含 {@link SessionMountMaterializer}
 * 与 {@link AssemblyLruCache}：前者仍 public——{@code service.session} 跨子包调用物化 / 补偿；
 * 后者收紧为包私有——本服务是其唯一生产者）。</p>
 */
@Service
public class RuntimeAgentAssemblyService {

    /** 装配 TTL 毫秒数（环境 / 技能等可变引用在窗口内的陈旧由短 TTL 收敛）。 */
    private static final long TTL_MILLIS = 60_000L;
    /** 装配缓存最大条数：到达上限时逐出最久未访问单条（LRU，见 {@link AssemblyLruCache}）。 */
    private static final int MAX_CACHE_SIZE = 128;

    /**
     * 装配结果缓存（进程内短 TTL + LRU）：值为无凭证明文快照 {@link CachedAssemblySnapshot}，
     * 键含 owner 与会话挂载签名 + sessionId 隔离装配面与产出归属（明文凭据每轮实时注入，不入缓存）。
     */
    private final AssemblyLruCache<AssemblyKey, CachedAssemblySnapshot> assemblyCache =
            new AssemblyLruCache<>(MAX_CACHE_SIZE, TTL_MILLIS);

    @Resource
    private AgentVersionAssemblyPort agentVersionAssemblyPort;
    /**
     * 运行时全局配置（沙箱规格唯一来源）：{@code @ConfigurationProperties} 属工具性 Spring 设施，
     * 应用层直注（迭代裁决 2026-09-13：不再为其增设端口包装）。
     */
    @Resource
    private AgentRuntimeProperties runtimeProperties;
    @Resource
    private MemoryStoreApi memoryStoreApi;
    @Resource
    private VaultCredentialResolutionPort vaultCredentialResolutionPort;
    /** 会话挂载物化编排：装配解析期幂等对账（缺副本补物化）并产出挂载视图（D20 单一事实源）。 */
    @Resource
    private SessionMountMaterializer sessionMountMaterializer;
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 装配缓存键：owner 隔离 + 版本快照外，会话挂载（环境 / 保管库 / 记忆库 / 环境变量）
     * 参与签名——同一 agent + 版本在不同挂载组合下 MUST NOT 共享装配规格；
     * sessionId 直接入键——装配快照携带产出归属（sessionId / ownerId）与按
     * {@code <agentWorkspace>/<sessionId>/mounts} 派生的 file 挂载宿主路径，
     * 跨会话命中缓存会把产出与挂载副本目录一并串号，MUST NOT 共享。
     * <p>注：缓存值为无凭证明文快照（{@link CachedAssemblySnapshot}），凭证每轮实时注入；
     * 键含 sessionId 是会话隔离要求，与「缓存值不含凭证」正交（去凭证不蕴含去 sessionId）。</p>
     */
    private record AssemblyKey(String agentId, String versionNumber, Long ownerId,
                               String mountSignature, String sessionId) {
    }

    /**
     * 无凭证明文的装配快照（slim-agent-assembly R14 / D2：缓存值去凭证明文）。
     * <p>承载可缓存的非密成分——身份、已格式化模型标识与生效调优参数、提示词、迭代上限、沙箱、
     * 挂载视图的非密部分（技能内容值对象 / 记忆库引用 / 环境变量 / 文件挂载视图）、工具治理、
     * 产出归属、MCP 连接清单（仅名称与 URL，鉴权明文每轮另行材料化）。</p>
     * <p><b>MUST NOT 携带</b>解密后的模型 {@code credential} / {@code apiEndpointUrl} 与保管库明文材料
     * （{@code VaultCredentialRef.token}）——它们每轮经端口实时材料化后注入最终规格，明文凭据因此
     * 不再随缓存驻留整个 TTL。{@link #toString()} 仅暴露环境变量键名，杜绝明文值随日志泄露。</p>
     */
    private record CachedAssemblySnapshot(
            AgentAssemblySpec.AgentIdentity identity,
            String model,
            String modelEffort,
            Integer modelContextWindow,
            AgentAssemblySpec.PromptContent promptContent,
            int maxIters,
            AgentAssemblySpec.Sandbox sandbox,
            List<Skill> skills,
            List<MemoryStoreRef> memoryStoreRefs,
            Map<String, String> envVars,
            List<AgentAssemblySpec.FileMountRef> fileMounts,
            AgentAssemblySpec.ToolGovernance toolGovernance,
            AgentAssemblySpec.ArtifactOwnership artifactOwnership,
            List<AgentAssemblySpec.McpConnection> mcpConnections
    ) {
        CachedAssemblySnapshot {
            skills = skills == null ? List.of() : List.copyOf(skills);
            memoryStoreRefs = memoryStoreRefs == null ? List.of() : List.copyOf(memoryStoreRefs);
            envVars = envVars == null ? Map.of() : Map.copyOf(envVars);
            fileMounts = fileMounts == null ? List.of() : List.copyOf(fileMounts);
            mcpConnections = mcpConnections == null ? List.of() : List.copyOf(mcpConnections);
        }

        @Override
        public String toString() {
            return "CachedAssemblySnapshot[identity=" + identity
                    + ", model=" + model
                    + ", modelEffort=" + modelEffort
                    + ", modelContextWindow=" + modelContextWindow
                    + ", maxIters=" + maxIters
                    + ", promptContent=" + promptContent
                    + ", sandbox=" + sandbox
                    + ", skills=" + skills.stream().map(Skill::name).toList()
                    + ", memoryStoreRefs=" + memoryStoreRefs
                    + ", envVars=" + envVars.keySet()
                    + ", fileMounts=" + fileMounts
                    + ", toolGovernance=" + toolGovernance
                    + ", artifactOwnership=" + artifactOwnership
                    + ", mcpConnections=" + mcpConnections + "]";
        }
    }

    /**
     * 校验一次 agentId + 发布号（会话创建前置校验链，不执行凭证解密）：
     * 发布号非十进制 / Agent 不存在或已归档 / 版本不存在 / profile 缺失 / 归属不匹配 → 404。
     *
     * @param ownerId 归属用户 ID（异步 / 调度链路无 ThreadLocal 认证上下文，显式传参）
     */
    public void assertResolvable(String agentId, String versionNumber, Long ownerId) {
        agentVersionAssemblyPort.assertResolvable(agentId, versionNumber, ownerId);
    }

    /**
     * 解析 Agent 当前最新发布号（会话创建仅绑定 agent 时锁定最新版本快照用）。
     *
     * @param ownerId 归属用户 ID（不匹配 → 404，不泄露存在性）
     */
    public String latestVersionNumber(String agentId, Long ownerId) {
        return agentVersionAssemblyPort.latestVersionNumber(agentId, ownerId);
    }

    /**
     * 解析 Agent 当前激活版本号（会话创建省略版本号时物化激活版本用）。
     *
     * @param ownerId 归属用户 ID（不匹配 → 404，不泄露存在性）
     */
    public String activeVersionNumber(String agentId, Long ownerId) {
        return agentVersionAssemblyPort.activeVersionNumber(agentId, ownerId);
    }

    /**
     * 会话 → 装配规格（会话级缓存：同一会话多轮 turn 复用无凭证明文快照，
     * 避免每轮全量重查 agent / 版本 / profile / 环境 / 技能正文 / 记忆库 / 文件挂载对账）。
     * <p>缓存键 = agentId + 版本号 + owner + 会话挂载签名 + sessionId（产出归属与按 sessionId
     * 派生的 file 宿主路径随快照驻留，跨会话不得共享）；版本为不可变快照，TTL 内环境 / 技能等
     * 可变引用的陈旧窗口由短 TTL 收敛（超出 TTL 自动失效重查）；容量满 128 由 {@link AssemblyLruCache}
     * 逐出最久未访问<b>单条</b>（不再全清，消除缓存击穿风暴）。</p>
     * <p><b>命中路径（slim-agent-assembly R14 / D2）</b>：取无凭证快照后，模型凭据段
     * （{@code credential} / {@code apiEndpointUrl}）每轮经
     * {@link AgentVersionAssemblyPort#resolveModelCredential} 实时材料化（同时重跑归属校验，
     * TTL 内归属失效不再被缓存掩盖）；保管库明文按会话 vaultIds 每轮经
     * {@link VaultCredentialResolutionPort#resolveVaultCredentials} 取回（vaultIds 为空按版本快照取空），
     * 再用 Builder 合成最终规格。<b>MUST NOT 出现同轮两次 agent BC 解析</b>：命中仅调一次窄端口
     * {@code resolveModelCredential}（不重跑全量 {@code resolve}），未命中仅调一次全量 {@code resolve}。</p>
     *
     * @return 装配规格（领域值对象，含每轮实时注入的凭证 / API 端点，仅入工厂装配，不参与持久化）
     */
    public AgentAssemblySpec assemble(AgentSession session) {
        Long ownerId = UserIds.parse(session.userId());
        AssemblyKey key = new AssemblyKey(session.agentId(), session.agentVersion(), ownerId,
                mountSignature(session), session.sessionId());
        long now = System.currentTimeMillis();
        CachedAssemblySnapshot snapshot = assemblyCache.get(key, now);
        if (snapshot != null) {
            return buildFromSnapshot(snapshot, session, ownerId);
        }
        AgentAssemblySpec spec = assembleNow(session, ownerId);
        assemblyCache.put(key, snapshotOf(spec), now);
        return spec;
    }

    /**
     * 实际装配（绕过缓存直接解析契约 → Session 挂载解析 → 物化技能 / 对账挂载 → 映射装配规格）。
     * <p>记忆库 / 保管库以 Session 挂载为准；环境挂载仅参与缓存签名，装配期不再复检
     * 环境类型（执行平面守卫已在会话创建期单点完成，见 D16）；沙箱资源恒取全局运行时属性
     * （环境契约不再承载沙箱明细）。file 挂载在<b>缓存未命中</b>
     * 路径做幂等对账（缺副本补物化）并把视图带入规格——系统提示清单与 bind mount 同源
     * 由该视图驱动（D14 / D20），对账天然早于 {@code agentFactory.build}。</p>
     */
    private AgentAssemblySpec assembleNow(AgentSession session, Long ownerId) {
        ResolvedAgentAssemblyDTO resolved = agentVersionAssemblyPort.resolve(
                session.agentId(), session.agentVersion(), ownerId);
        List<Skill> skills = SkillAssemblyConvert.INSTANCE.materialize(resolved.skills());
        List<MemoryStoreReferenceDTO> memoryStores = session.memoryStoreIds().isEmpty()
                ? resolved.memoryStores()
                : memoryStoreApi.resolveByIds(ownerId, session.memoryStoreIds());
        List<ResolvedVaultCredentialDTO> vaults = session.vaultIds().isEmpty()
                ? resolved.vaultCredentials()
                : vaultCredentialResolutionPort.resolveVaultCredentials(ownerId, session.vaultIds());
        List<AgentAssemblySpec.FileMountRef> fileMounts = sessionMountMaterializer.reconcile(session, ownerId);
        return AgentAssemblyConvert.INSTANCE.toSpec(
                resolved,
                toSandbox(),
                skills,
                toMemoryStoreRefs(memoryStores),
                toVaultCredentialRefs(vaults),
                parseEnvironmentVariables(session),
                session.sessionId(),
                ownerId,
                fileMounts,
                toMcpConnections(resolved)
        );
    }

    /**
     * agent 装配契约 → 本 BC MCP 连接清单（版本级声明，非会话挂载，随快照缓存）。
     * <p>取数来自 {@link ResolvedAgentAssemblyDTO#mcpServers()}（agent BC 已用领域解析器
     * {@code McpServer.parse} 把 {@code agent_version.mcp_servers_json} 收敛为结构化条目），
     * 经防腐映射 {@link AgentAssemblyConvert#toMcpConnections} 转为本 BC 值对象；未声明 MCP
     * 时为空清单（工厂据此不下发 {@code ToolsConfig.mcpServers}，行为零扰动）。</p>
     * <p>清单仅含名称与 URL，<b>不含凭据材料</b>——鉴权头由工厂按 URL 于每轮装配时实时匹配
     * 本轮材料化的保管库凭证产出。</p>
     */
    private static List<AgentAssemblySpec.McpConnection> toMcpConnections(ResolvedAgentAssemblyDTO resolved) {
        return AgentAssemblyConvert.INSTANCE.toMcpConnections(
                resolved == null ? null : resolved.mcpServers());
    }

    /**
     * 全量装配规格 → 无凭证明文缓存快照（R14 / D2：凭证 / 保管库明文一律剥离，仅缓存非密成分）。
     * <p>仅在缓存<b>未命中</b>路径调用（此时本轮已由 {@link #assembleNow} 完成一次全量 agent BC 解析，
     * 拆快照入缓存不产生额外解析）；模型凭据段（{@code credential} / {@code apiEndpointUrl}）
     * 与 {@link VaultCredentialRef} 明文均不进快照。</p>
     */
    private static CachedAssemblySnapshot snapshotOf(AgentAssemblySpec spec) {
        MountView mountView = spec.mountView();
        return new CachedAssemblySnapshot(
                spec.identity(),
                spec.model(),
                spec.modelEffort(),
                spec.modelContextWindow(),
                spec.promptContent(),
                spec.maxIters(),
                spec.sandbox(),
                mountView.skills(),
                mountView.memoryStoreRefs(),
                mountView.envVars(),
                mountView.fileMounts(),
                spec.toolGovernance(),
                spec.artifactOwnership(),
                spec.mcpConnections()
        );
    }

    /**
     * 命中路径：无凭证快照 + 每轮实时材料化凭证 → Builder 合成最终装配规格。
     * <p>模型凭据段经 {@link AgentVersionAssemblyPort#resolveModelCredential} 取回（一次窄查询，
     * 复用归属校验，<b>MUST NOT</b> 重跑全量 {@code resolve}）；保管库明文按会话 vaultIds 每轮经
     * {@link VaultCredentialResolutionPort#resolveVaultCredentials} 取回，vaultIds 为空时按版本快照
     * 语义取空（与 {@link #assembleNow} 中 vaultIds 空 → 版本快照空位行为等价）。其余分组直接复用快照。</p>
     */
    private AgentAssemblySpec buildFromSnapshot(CachedAssemblySnapshot snapshot, AgentSession session, Long ownerId) {
        ResolvedModelCredentialDTO credential = agentVersionAssemblyPort.resolveModelCredential(
                session.agentId(), session.agentVersion(), ownerId);
        ModelBinding modelBinding = new ModelBinding(
                snapshot.model(),
                credential.credential(),
                credential.apiEndpointUrl(),
                snapshot.modelEffort(),
                snapshot.modelContextWindow());
        List<VaultCredentialRef> vaultCredentials = session.vaultIds().isEmpty()
                ? List.of()
                : toVaultCredentialRefs(
                        vaultCredentialResolutionPort.resolveVaultCredentials(ownerId, session.vaultIds()));
        MountView mountView = new MountView(
                snapshot.skills(),
                snapshot.memoryStoreRefs(),
                vaultCredentials,
                snapshot.envVars(),
                snapshot.fileMounts());
        return AgentAssemblySpec.builder()
                .withIdentity(snapshot.identity())
                .withModelBinding(modelBinding)
                .withPromptContent(snapshot.promptContent())
                .withMaxIters(snapshot.maxIters())
                .withSandbox(snapshot.sandbox())
                .withMountView(mountView)
                .withToolGovernance(snapshot.toolGovernance())
                .withArtifactOwnership(snapshot.artifactOwnership())
                .withMcpConnections(snapshot.mcpConnections())
                .build();
    }

    /**
     * 会话挂载签名（装配缓存键成分）：环境 ID + 保管库 / 记忆库挂载列表 + 环境变量原文
     * + file 挂载 {@code fileId:mountPath} 保序列表。
     * <p>挂载任一项变化即视为不同装配面，禁止跨挂载复用缓存规格。file 项入签是<b>正确性
     * 要求而非优化</b>（D14）：追加 / 移除挂载 MUST 立即失效缓存，否则 TTL 内命中旧规格
     * 会造成「清单缺新项而沙箱已有文件」的自相矛盾。</p>
     */
    private static String mountSignature(AgentSession session) {
        String fileMounts = session.resources().stream()
                .filter(resource -> SessionResource.FILE_TYPE.equals(resource.type()))
                .map(resource -> resource.fileId() + ":" + resource.mountPath())
                .collect(Collectors.joining(","));
        return String.join("|",
                StringUtils.defaultString(session.environmentId()),
                String.join(",", session.vaultIds()),
                String.join(",", session.memoryStoreIds()),
                session.environmentVariables(),
                fileMounts);
    }

    /** 会话环境变量 JSON 原文 → 键值映射（解析失败 → 400 语义，杜绝静默丢弃挂载）。 */
    private Map<String, String> parseEnvironmentVariables(AgentSession session) {
        String raw = session.environmentVariables();
        if (StringUtils.isBlank(raw) || "{}".equals(raw.trim())) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception ex) {
            throw new IllegalArgumentException("会话环境变量不是合法的字符串键值 JSON 对象", ex);
        }
    }

    /**
     * 运行时沙箱规格：恒取 {@link AgentRuntimeProperties} 全局默认。
     * <p>沙箱资源明细（镜像 / 内存 / CPU）不再由环境契约承载（随 Environment config
     * 结构化改造移除），全局运行时属性是唯一来源；环境 config 中 packages / setup_script
     * 的沙箱准备阶段执行属自托管执行面里程碑（本期仅登记契约，不接线）。</p>
     */
    private AgentAssemblySpec.Sandbox toSandbox() {
        return AgentAssemblySpec.Sandbox.of(
                runtimeProperties.getSandboxImage(),
                runtimeProperties.getSandboxMemoryBytes(),
                runtimeProperties.getSandboxCpuCount());
    }

    /** memory 契约引用 → runtime 领域记忆库引用值对象（未引用返回空）。 */
    private List<MemoryStoreRef> toMemoryStoreRefs(List<MemoryStoreReferenceDTO> stores) {
        if (stores == null) {
            return List.of();
        }
        return stores.stream()
                .map(store -> new MemoryStoreRef(store.storeId(), store.name()))
                .toList();
    }

    /**
     * vault 契约凭证 → runtime 领域保管库凭证引用值对象（明文仅内存持有，未引用返回空）。
     * <p>Target 口径：URL Target 型（static_bearer / mcp_oauth）取 {@code mcpServerUrl}，
     * 变量名 Target 型（environment_variable）取 {@code secretName}——统一落入
     * {@link VaultCredentialRef#target()} 供 JVM 侧 {@code McpCredentialResolver} 精确匹配；
     * 解密明文仅在该值对象（toString 脱敏）中承载，供 MCP 客户端请求头注入，
     * <b>不进沙箱环境变量</b>、不参与任何响应序列化与持久化。</p>
     */
    private List<VaultCredentialRef> toVaultCredentialRefs(List<ResolvedVaultCredentialDTO> credentials) {
        if (credentials == null) {
            return List.of();
        }
        return credentials.stream()
                .map(c -> new VaultCredentialRef(
                        c.vaultId(), c.credentialId(), c.authType(),
                        // TODO(vault-contract 临时桥接): vault BC 新契约（8 组件：+secretName/+secret/+expiresAt）
                        // 落地后恢复 target = isNotBlank(mcpServerUrl) ? mcpServerUrl : secretName、token = c.secret()；
                        // 磁盘现状仍为旧 5 组件（仅 mcpServerUrl + token），先按现状取数保证可编译
                        c.mcpServerUrl(),
                        c.token()))
                .toList();
    }
}