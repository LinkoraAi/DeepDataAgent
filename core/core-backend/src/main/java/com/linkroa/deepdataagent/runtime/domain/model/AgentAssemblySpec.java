package com.linkroa.deepdataagent.runtime.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Agent 组装规格：声明式描述一次 Harness 装配所需参数，由领域层持有，不包含任何框架类型。
 * <p>工厂（infrastructure.client）据此装配 AgentScope HarnessAgent，规格本身保持框架无关注。</p>
 * <p><b>分组构造（R13 / D1）</b>：位置参数按语义拆为 8 个分组子 record
 * （{@link AgentIdentity} / {@link ModelBinding} / {@link PromptContent} / {@link Sandbox} /
 * {@link MountView} / {@link ToolGovernance} / {@link ArtifactOwnership}）+ {@code maxIters} 标量
 * + {@code mcpConnections} 清单（版本级 MCP 连接声明，非会话挂载，直挂顶层），
 * 位置参数错配在编译期不可能；构造一律经 {@link Builder}（逐组 withX），不再提供历史兼容构造器。</p>
 * <p><b>消费面兼容</b>：顶层保留与旧签名一致的扁平访问器（{@code agentId()} / {@code model()} /
 * {@code credential()} 等），它们委托各分组，工厂与执行器读取方式不变。</p>
 * <p><b>凭证明文面</b>：解密后的模型凭证与保管库明文分别由 {@link ModelBinding}（{@code toString}
 * 恒掩码）与 {@link VaultCredentialRef}（{@code toString} 恒掩码）持有；装配缓存仅存
 * 无凭证明文快照，凭证每轮实时注入（见 {@code RuntimeAgentAssemblyService}）。保管库明文<b>永不
 * 进沙箱数据面</b>——仅供 JVM 侧 MCP 凭据解析器（{@code McpCredentialResolver}）按 Target 匹配
 * 连接后注入请求鉴权头。</p>
 */
public record AgentAssemblySpec(
        AgentIdentity identity,
        ModelBinding modelBinding,
        PromptContent promptContent,
        int maxIters,
        Sandbox sandbox,
        MountView mountView,
        ToolGovernance toolGovernance,
        ArtifactOwnership artifactOwnership,
        List<McpConnection> mcpConnections
) {

    public AgentAssemblySpec {
        if (identity == null) {
            throw new IllegalArgumentException("Agent 身份分组不能为空");
        }
        if (modelBinding == null) {
            throw new IllegalArgumentException("模型绑定分组不能为空");
        }
        if (promptContent == null) {
            throw new IllegalArgumentException("提示词分组不能为空");
        }
        if (maxIters <= 0) {
            throw new IllegalArgumentException("最大迭代次数必须为正数");
        }
        if (sandbox == null) {
            throw new IllegalArgumentException("沙箱规格不能为空");
        }
        if (mountView == null) {
            throw new IllegalArgumentException("挂载视图分组不能为空");
        }
        if (toolGovernance == null) {
            throw new IllegalArgumentException("工具治理分组不能为空");
        }
        if (artifactOwnership == null) {
            throw new IllegalArgumentException("产出归属分组不能为空");
        }
        mcpConnections = mcpConnections == null ? List.of() : List.copyOf(mcpConnections);
    }

    // ==================== 扁平访问器（消费面无感：委托分组，签名与旧 record 一致） ====================

    /** Agent 业务 ID。 */
    public String agentId() {
        return identity.agentId();
    }

    /** Agent 名称（版本显示名）。 */
    public String name() {
        return identity.name();
    }

    /** 模型字符串 ID（如 openai:gpt-4，经 AgentScope ModelRegistry 解析）。 */
    public String model() {
        return modelBinding.model();
    }

    /** 解密后的模型凭证（无鉴权时可空）。 */
    public String credential() {
        return modelBinding.credential();
    }

    /** 模型 API 端点（可空，默认走提供方内置端点）。 */
    public String apiEndpointUrl() {
        return modelBinding.apiEndpointUrl();
    }

    /** 生效模型 effort 档位（可空 = 提供方内置默认）。 */
    public String modelEffort() {
        return modelBinding.effort();
    }

    /** 生效模型上下文窗口（可空 = 提供方内置默认）。 */
    public Integer modelContextWindow() {
        return modelBinding.contextWindow();
    }

    /** 系统提示词（可空）。 */
    public String systemPrompt() {
        return promptContent.systemPrompt();
    }

    /** AGENTS.md 指令文件原文（可空 = 未配置）。 */
    public String agentsMd() {
        return promptContent.agentsMd();
    }

    /** 挂载技能（已物化的框架无关注内容）。 */
    public List<Skill> skills() {
        return mountView.skills();
    }

    /** 记忆库引用（框架无关注）。 */
    public List<MemoryStoreRef> memoryStoreRefs() {
        return mountView.memoryStoreRefs();
    }

    /** 保管库凭证引用（明文仅内存持有，toString 脱敏）。 */
    public List<VaultCredentialRef> vaultCredentials() {
        return mountView.vaultCredentials();
    }

    /** 会话级环境变量（toString 仅输出键名）。 */
    public Map<String, String> environmentVariables() {
        return mountView.envVars();
    }

    /** 会话文件挂载视图（装配解析期 reconcile 产出清单）。 */
    public List<FileMountRef> fileMounts() {
        return mountView.fileMounts();
    }

    /** 逐工具执行权限策略。 */
    public List<ToolExecutionPolicy> toolPolicies() {
        return toolGovernance.toolPolicies();
    }

    /** 内置工具可见性约束。 */
    public ToolVisibility toolVisibility() {
        return toolGovernance.toolVisibility();
    }

    /** 产出归属会话业务 ID（可空 = 未绑定会话）。 */
    public String sessionId() {
        return artifactOwnership.sessionId();
    }

    /** 产出归属用户 ID（可空）。 */
    public Long ownerId() {
        return artifactOwnership.ownerId();
    }

    /**
     * 组合 toString：委托各分组（凭证 / 保管库明文分别由 {@link ModelBinding}、
     * {@link VaultCredentialRef}、{@link MountView} 各自掩码），明文不随日志 / 异常链泄露。
     */
    @Override
    public String toString() {
        return "AgentAssemblySpec[identity=" + identity
                + ", modelBinding=" + modelBinding
                + ", promptContent=" + promptContent
                + ", maxIters=" + maxIters
                + ", sandbox=" + sandbox
                + ", mountView=" + mountView
                + ", toolGovernance=" + toolGovernance
                + ", artifactOwnership=" + artifactOwnership
                + ", mcpConnections=" + mcpConnections + "]";
    }

    /**
     * 分组建造者（唯一构造入口）：逐组 withX 装配，{@code build()} 走顶层规范构造器校验不变量。
     * <p>未设置的可选分组（{@link ToolGovernance} / {@link ArtifactOwnership}）与未设置的
     * {@code mcpConnections} 清单在 {@code build()} 时按语义归一为空（空分组 / 空清单），
     * 等价旧「不携带工具策略 / 未绑定会话 / 版本未声明 MCP」的兼容构造器效果。</p>
     */
    public static final class Builder {
        private AgentIdentity identity;
        private ModelBinding modelBinding;
        private PromptContent promptContent;
        private int maxIters;
        private Sandbox sandbox;
        private MountView mountView;
        private ToolGovernance toolGovernance;
        private ArtifactOwnership artifactOwnership;
        private List<McpConnection> mcpConnections;

        private Builder() {
        }

        /**
         * 设置 Agent 身份分组（必填）。
         *
         * @param identity 身份分组
         * @return 本建造者（链式调用）
         */
        public Builder withIdentity(AgentIdentity identity) {
            this.identity = identity;
            return this;
        }

        /**
         * 设置模型绑定分组（必填）：模型标识 + 明文凭证 + 端点 + 调优参数。
         *
         * @param modelBinding 模型绑定分组
         * @return 本建造者（链式调用）
         */
        public Builder withModelBinding(ModelBinding modelBinding) {
            this.modelBinding = modelBinding;
            return this;
        }

        /**
         * 设置提示词分组（必填）。
         *
         * @param promptContent 提示词分组
         * @return 本建造者（链式调用）
         */
        public Builder withPromptContent(PromptContent promptContent) {
            this.promptContent = promptContent;
            return this;
        }

        /**
         * 设置最大迭代次数（必填，须为正数）。
         *
         * @param maxIters 最大迭代次数
         * @return 本建造者（链式调用）
         */
        public Builder withMaxIters(int maxIters) {
            this.maxIters = maxIters;
            return this;
        }

        /**
         * 设置沙箱规格（必填）。
         *
         * @param sandbox 沙箱规格
         * @return 本建造者（链式调用）
         */
        public Builder withSandbox(Sandbox sandbox) {
            this.sandbox = sandbox;
            return this;
        }

        /**
         * 设置挂载视图分组（必填）。
         *
         * @param mountView 挂载视图分组
         * @return 本建造者（链式调用）
         */
        public Builder withMountView(MountView mountView) {
            this.mountView = mountView;
            return this;
        }

        /**
         * 设置工具治理分组（可空；未设置时 {@link #build()} 归一为空分组）。
         *
         * @param toolGovernance 工具治理分组
         * @return 本建造者（链式调用）
         */
        public Builder withToolGovernance(ToolGovernance toolGovernance) {
            this.toolGovernance = toolGovernance;
            return this;
        }

        /**
         * 设置产出归属分组（可空；未设置时 {@link #build()} 归一为空分组，即未绑定会话）。
         *
         * @param artifactOwnership 产出归属分组
         * @return 本建造者（链式调用）
         */
        public Builder withArtifactOwnership(ArtifactOwnership artifactOwnership) {
            this.artifactOwnership = artifactOwnership;
            return this;
        }

        /**
         * 设置版本级 MCP 连接声明（可空；未设置时 {@link #build()} 归一为空清单）。
         *
         * @param mcpConnections MCP 连接清单
         * @return 本建造者（链式调用）
         */
        public Builder withMcpConnections(List<McpConnection> mcpConnections) {
            this.mcpConnections = mcpConnections;
            return this;
        }

        /**
         * 构建装配规格：未设置的可选分组按空分组、未设置的 MCP 清单按空清单归一，再走顶层规范构造器校验不变量。
         *
         * @return 装配规格
         * @throws IllegalArgumentException 必填分组缺失或标量 / 子 record 字段违反不变量
         */
        public AgentAssemblySpec build() {
            return new AgentAssemblySpec(
                    identity,
                    modelBinding,
                    promptContent,
                    maxIters,
                    sandbox,
                    mountView,
                    toolGovernance == null ? ToolGovernance.empty() : toolGovernance,
                    artifactOwnership == null ? ArtifactOwnership.empty() : artifactOwnership,
                    mcpConnections == null ? List.of() : mcpConnections);
        }
    }

    /**
     * 创建分组建造者（装配规格的唯一构造入口）。
     *
     * @return 空建造者
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== 分组子 record（R13 / D1：语义分组，组内做不变量校验） ====================

    /**
     * Agent 身份分组。
     *
     * @param agentId Agent 业务 ID（非空）
     * @param name    Agent 名称（非空，≤128）
     */
    public record AgentIdentity(String agentId, String name) {
        public AgentIdentity {
            if (StringUtils.isBlank(agentId)) {
                throw new IllegalArgumentException("Agent ID 不能为空");
            }
            if (StringUtils.isBlank(name)) {
                throw new IllegalArgumentException("Agent 名称不能为空");
            }
            if (name.length() > 128) {
                throw new IllegalArgumentException("Agent 名称长度不能超过 128");
            }
        }
    }

    /**
     * 模型绑定分组：模型标识 + 明文凭证 + API 端点 + 生效调优参数。
     * <p>{@code credential} / {@code apiEndpointUrl} 属明文凭据，<b>MUST NOT 进装配缓存</b>
     * （见 {@code CachedAssemblySnapshot}）；{@link #toString()} 恒掩码凭证，防止随日志 / 异常链泄露。</p>
     *
     * @param model          模型字符串 ID（非空，如 openai:gpt-4）
     * @param credential     解密后的模型凭证（无鉴权时可空）
     * @param apiEndpointUrl 模型 API 端点（可空，默认走提供方内置端点）
     * @param effort         生效模型 effort 档位（可空 = 提供方内置默认）
     * @param contextWindow  生效模型上下文窗口（可空 = 提供方内置默认；非空须 &gt;0）
     */
    public record ModelBinding(
            String model,
            String credential,
            String apiEndpointUrl,
            String effort,
            Integer contextWindow
    ) {
        public ModelBinding {
            if (StringUtils.isBlank(model)) {
                throw new IllegalArgumentException("模型 ID 不能为空");
            }
            if (contextWindow != null && contextWindow < 1) {
                throw new IllegalArgumentException("模型上下文窗口必须大于0");
            }
        }

        /**
         * 脱敏 toString：明文凭证保留前 4 位其余掩码，避免随日志 / 异常链泄露。
         */
        @Override
        public String toString() {
            return "ModelBinding[model=" + model
                    + ", credential=" + mask(credential)
                    + ", apiEndpointUrl=" + apiEndpointUrl
                    + ", effort=" + effort
                    + ", contextWindow=" + contextWindow + "]";
        }

        /** 凭证打码：非空且长度大于 4 时保留前 4 位，其余替换为掩码（长度不足以保留时全掩码）。 */
        private static String mask(String credential) {
            if (credential == null || credential.isBlank()) {
                return credential;
            }
            if (credential.length() <= 4) {
                return "****";
            }
            return credential.substring(0, 4) + "****";
        }
    }

    /**
     * 提示词分组：系统提示词原文 + AGENTS.md 原文。
     *
     * @param systemPrompt 系统提示词（可空，≤100000）
     * @param agentsMd     AGENTS.md 指令文件原文（可空 = 未配置，≤20000；装配时物化到 Agent 工作区根）
     */
    public record PromptContent(String systemPrompt, String agentsMd) {
        public PromptContent {
            if (systemPrompt != null && systemPrompt.length() > 100000) {
                throw new IllegalArgumentException("系统提示词长度不能超过 100000");
            }
            if (agentsMd != null && agentsMd.length() > 20000) {
                throw new IllegalArgumentException("AGENTS.md 长度不能超过 20000");
            }
        }

        /**
         * 脱敏 toString：AGENTS.md 全文不随日志输出，仅暴露长度（系统提示词为可展示配置，原样输出）。
         */
        @Override
        public String toString() {
            return "PromptContent[systemPrompt=" + systemPrompt
                    + ", agentsMdLength=" + (agentsMd == null ? 0 : agentsMd.length()) + "]";
        }
    }

    /**
     * 沙箱规格。
     *
     * @param image       Docker 镜像
     * @param memoryBytes 内存上限（字节，可空）
     * @param cpuCount    CPU 核数（可空）
     */
    public record Sandbox(String image, Long memoryBytes, Long cpuCount) {

        public Sandbox {
            if (StringUtils.isBlank(image)) {
                throw new IllegalArgumentException("沙箱镜像不能为空");
            }
            if (memoryBytes != null && memoryBytes <= 0) {
                throw new IllegalArgumentException("沙箱内存上限必须为正数");
            }
            if (cpuCount != null && cpuCount <= 0) {
                throw new IllegalArgumentException("沙箱 CPU 核数必须为正数");
            }
        }

        /**
         * 以显式参数创建沙箱规格。
         *
         * @param image       Docker 镜像（非空）
         * @param memoryBytes 内存上限（字节，可空）
         * @param cpuCount    CPU 核数（可空）
         * @return 沙箱规格
         * @throws IllegalArgumentException 镜像为空或资源上限非正
         */
        public static Sandbox of(String image, Long memoryBytes, Long cpuCount) {
            return new Sandbox(image, memoryBytes, cpuCount);
        }
    }

    /**
     * 挂载视图分组：技能内容 + 记忆库引用 + 保管库凭证 + 环境变量 + 文件挂载视图。
     * <p>{@code vaultCredentials} 携解密密文材料，属明文凭据，<b>MUST NOT 进装配缓存</b>；
     * 缓存快照仅取其非密成分（skills / memoryStoreRefs / envVars / fileMounts，见
     * {@code CachedAssemblySnapshot}），保管库明文每轮实时注入。</p>
     *
     * @param skills          挂载技能（已物化的框架无关注内容，可空/空）
     * @param memoryStoreRefs 记忆库引用（框架无关注，可空/空，未引用不装配记忆检索工具）
     * @param vaultCredentials 挂载保管库解密后的凭证（框架无关注值对象，明文仅内存持有，toString 脱敏）
     * @param envVars         会话级环境变量（Session 挂载注入沙箱数据面，可空/空；toString 仅输出键名）
     * @param fileMounts      会话文件挂载视图（reconcile 产出清单，工厂据此拼系统提示段落并判定 bind mount）
     */
    public record MountView(
            List<Skill> skills,
            List<MemoryStoreRef> memoryStoreRefs,
            List<VaultCredentialRef> vaultCredentials,
            Map<String, String> envVars,
            List<FileMountRef> fileMounts
    ) {
        public MountView {
            skills = skills == null ? List.of() : List.copyOf(skills);
            memoryStoreRefs = memoryStoreRefs == null ? List.of() : List.copyOf(memoryStoreRefs);
            vaultCredentials = vaultCredentials == null ? List.of() : List.copyOf(vaultCredentials);
            envVars = envVars == null ? Map.of() : Map.copyOf(envVars);
            fileMounts = fileMounts == null ? List.of() : List.copyOf(fileMounts);
        }

        /**
         * 脱敏 toString：技能仅列名称、环境变量仅输出键名（值可能含敏感材料），
         * 保管库凭证随 {@link VaultCredentialRef} 各自掩码 token，挂载清单为元数据直接列出。
         */
        @Override
        public String toString() {
            return "MountView[skills=" + skills.stream().map(Skill::name).toList()
                    + ", memoryStoreRefs=" + memoryStoreRefs
                    + ", vaultCredentials=" + vaultCredentials
                    + ", envVars=" + envVars.keySet()
                    + ", fileMounts=" + fileMounts + "]";
        }
    }

    /**
     * 工具治理分组：逐工具权限策略 + 内置工具可见性约束。
     *
     * @param toolPolicies   逐工具执行权限策略（可空/空 = 无策略配置，工厂据此装配权限引擎）
     * @param toolVisibility 内置工具可见性约束（可空归一为无约束；工厂据此生成工具过滤指令）
     */
    public record ToolGovernance(
            List<ToolExecutionPolicy> toolPolicies,
            ToolVisibility toolVisibility
    ) {
        public ToolGovernance {
            toolPolicies = toolPolicies == null ? List.of() : List.copyOf(toolPolicies);
            toolVisibility = toolVisibility == null ? new ToolVisibility(null, null) : toolVisibility;
        }

        /** 空治理分组（无策略、无可见性约束）。 */
        public static ToolGovernance empty() {
            return new ToolGovernance(List.of(), new ToolVisibility(null, null));
        }
    }

    /**
     * 产出归属分组：会话与属主标识（交付工具据此登记会话产出，不信任模型自报）。
     *
     * @param sessionId 产出归属会话业务 ID（可空 = 未绑定会话，交付时由登记面拒绝）
     * @param ownerId   产出归属用户 ID（可空）
     */
    public record ArtifactOwnership(String sessionId, Long ownerId) {

        /** 空归属分组（未绑定会话）。 */
        public static ArtifactOwnership empty() {
            return new ArtifactOwnership(null, null);
        }

        @Override
        public String toString() {
            return "ArtifactOwnership[sessionId=" + sessionId + ", ownerId=" + ownerId + "]";
        }
    }

    // ==================== 既有值对象 ====================

    /**
     * MCP 连接声明（框架无关注值对象，agent 版本快照 {@code mcp_servers} 的本 BC 映射）。
     * <p>描述版本声明的一路 MCP 服务器连接：{@code name} 为服务器名（MCP 配置 Map 的键），
     * {@code url} 为 streamable-http 端点。工厂据此结合 {@link VaultCredentialRef}
     * 经 {@code McpCredentialResolver} 注入鉴权头。</p>
     *
     * @param name 服务器名称（非空）
     * @param url  MCP 端点 URL（非空）
     */
    public record McpConnection(String name, String url) {

        public McpConnection {
            if (StringUtils.isBlank(name)) {
                throw new IllegalArgumentException("MCP 连接名称不能为空");
            }
            if (StringUtils.isBlank(url)) {
                throw new IllegalArgumentException("MCP 连接 URL 不能为空");
            }
        }
    }

    /**
     * 保管库凭证引用（运行时解密材料化的框架无关注值对象）。
     * <p>承载解密后的 {@code token} 明文，<b>仅作 JVM 侧 MCP 鉴权头注入的瞬态数据</b>，
     * 永不进沙箱数据面（环境变量）；不参与持久化与响应序列化，且 {@link #toString()}
     * 强制脱敏防止随日志 / 异常链泄露。</p>
     *
     * @param vaultId      所属保管库业务ID
     * @param credentialId 凭证业务ID
     * @param authType     凭证鉴权类型（static_bearer / mcp_oauth / environment_variable）
     * @param target       凭证 Target（URL Target 型为 MCP 服务器 URL；变量名 Target 型为环境变量名），
     *                     运行时按此精确匹配 MCP 连接后注入鉴权
     * @param token        解密后的凭证明文
     */
    public record VaultCredentialRef(
            String vaultId,
            String credentialId,
            String authType,
            String target,
            String token
    ) {

        public VaultCredentialRef {
            if (StringUtils.isBlank(vaultId)) {
                throw new IllegalArgumentException("保管库ID不能为空");
            }
            if (StringUtils.isBlank(credentialId)) {
                throw new IllegalArgumentException("凭证ID不能为空");
            }
            if (StringUtils.isBlank(authType)) {
                throw new IllegalArgumentException("凭证鉴权类型不能为空");
            }
            if (StringUtils.isBlank(target)) {
                throw new IllegalArgumentException("凭证 Target 不能为空");
            }
        }

        /**
         * 脱敏 toString：凭证明文不随日志 / 异常链输出。
         */
        @Override
        public String toString() {
            return "VaultCredentialRef[vaultId=" + vaultId
                    + ", credentialId=" + credentialId
                    + ", authType=" + authType
                    + ", target=" + target
                    + ", token=****]";
        }
    }

    /**
     * 逐工具执行权限策略（框架无关注值对象，agent 契约 tools_json 权限策略的本 BC 映射）。
     * <p>工厂据此装配 Harness 权限引擎：always_ask → 每次调用触发 HITL 确认暂停、
     * always_deny → 直接拒绝；always_allow 与未配置工具走框架放行路径。</p>
     *
     * @param name             工具名（内置工具为公开契约名，MCP 工具为服务端暴露的原始工具名，非空）
     * @param permissionPolicy 权限策略（always_allow / always_ask / always_deny，非空）
     * @param mcpServerName    所属 MCP 服务器名（仅 {@code mcp_toolset} 来源的策略项非空；内置工具为 null）。
     *                         非空时工厂 MUST 先把 {@code name} 翻译为运行时实名
     *                         {@code mcp__{mcpServerName}__{name}} 再落为权限规则（按原始工具名落表永不命中）
     */
    public record ToolExecutionPolicy(String name, String permissionPolicy, String mcpServerName) {

        /** 恒允许策略。 */
        public static final String POLICY_ALWAYS_ALLOW = "always_allow";
        /** 每次询问策略（触发 HITL tool_confirmation）。 */
        public static final String POLICY_ALWAYS_ASK = "always_ask";
        /** 恒拒绝策略。 */
        public static final String POLICY_ALWAYS_DENY = "always_deny";

        /** 求值结果：直接执行（D15 事件载荷 {@code evaluated_permission} 取值域）。 */
        public static final String EVALUATED_ALLOW = "allow";
        /** 求值结果：暂停轮次等 {@code user.tool_confirmation}。 */
        public static final String EVALUATED_ASK = "ask";
        /** 求值结果：平台拒绝执行、直接返回拒绝结果。 */
        public static final String EVALUATED_DENY = "deny";

        /**
         * 策略词汇 → 求值结果（{@code allow} / {@code ask} / {@code deny}）。
         * <p>对外发布的是<b>求值结果</b>而非策略词汇：未配置策略（null / 未知）走框架默认放行路径，
         * 与 {@code always_allow} 同判为 {@code allow}。</p>
         *
         * @param permissionPolicy 策略词汇（可空）
         * @return 求值结果
         */
        public static String evaluatedPermissionOf(String permissionPolicy) {
            if (POLICY_ALWAYS_ASK.equals(permissionPolicy)) {
                return EVALUATED_ASK;
            }
            if (POLICY_ALWAYS_DENY.equals(permissionPolicy)) {
                return EVALUATED_DENY;
            }
            return EVALUATED_ALLOW;
        }

        /**
         * 兼容构造器：内置工具策略（无 MCP 服务器归属）。
         */
        public ToolExecutionPolicy(String name, String permissionPolicy) {
            this(name, permissionPolicy, null);
        }

        /**
         * 紧凑构造器：工具名非空、策略词汇合法。
         */
        public ToolExecutionPolicy {
            if (StringUtils.isBlank(name)) {
                throw new IllegalArgumentException("工具策略 name 不能为空");
            }
            if (!POLICY_ALWAYS_ALLOW.equals(permissionPolicy)
                    && !POLICY_ALWAYS_ASK.equals(permissionPolicy)
                    && !POLICY_ALWAYS_DENY.equals(permissionPolicy)) {
                throw new IllegalArgumentException("工具 permission_policy 非法，须为 always_allow/always_ask/always_deny");
            }
        }
    }

    /**
     * 内置工具可见性约束（框架无关注值对象，agent 契约 tools_json 可见性名单的本 BC 映射）。
     * <p>名单承载公开契约名（Bash / Read / Write 等），契约名 → Harness 运行时实名的翻译
     * 属工厂基础设施职责；白名单为空 = 无白名单约束（全量基座暴露），隐藏名单为空 = 无隐藏约束，
     * 两者皆空时工厂不产生工具过滤指令。</p>
     *
     * @param allowedTools 内置工具白名单（契约名，可空/空 = 无约束）
     * @param hiddenTools  隐藏并拒绝名单（契约名，可空/空 = 无约束）
     */
    public record ToolVisibility(List<String> allowedTools, List<String> hiddenTools) {

        public ToolVisibility {
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
            hiddenTools = hiddenTools == null ? List.of() : List.copyOf(hiddenTools);
        }

        /** 是否携带任一可见性约束（两名单皆空时工厂不生成过滤指令）。 */
        public boolean hasConstraint() {
            return !allowedTools.isEmpty() || !hiddenTools.isEmpty();
        }
    }

    /**
     * 文件挂载视图项（框架无关注值对象，装配解析期 reconcile 产出的清单条目）。
     * <p>「有挂载记录且宿主副本就位」的单条挂载元数据：工厂据此拼系统提示清单段落
     * （原始文件名 / 字节数 / 沙箱路径 / 只读），与 bind mount 的目录实况同源（D14 / D20）。
     * 元数据缺失（文件记录已删）时以 {@code fileId} 回落文件名、体积记 0（「有记录即有行」）。</p>
     *
     * @param fileId    文件业务 ID（前缀 {@code file_}）
     * @param filename  原始文件名（非空；元数据缺失时为 fileId 回落值）
     * @param sizeBytes 文件字节数（≥0；元数据缺失时记 0）
     * @param mountPath 工作区相对挂载路径（{@code mounts/} 前缀，沙箱内可见 {@code /workspace/<mountPath>}）
     */
    public record FileMountRef(
            String fileId,
            String filename,
            long sizeBytes,
            String mountPath
    ) {

        public FileMountRef {
            if (StringUtils.isBlank(fileId) || !fileId.startsWith("file_")) {
                throw new IllegalArgumentException("挂载文件ID非法: " + fileId);
            }
            if (StringUtils.isBlank(filename)) {
                throw new IllegalArgumentException("挂载文件名不能为空");
            }
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("挂载文件字节数不能为负: " + sizeBytes);
            }
            if (StringUtils.isBlank(mountPath)
                    || !mountPath.startsWith(SessionResource.DEFAULT_MOUNT_PATH_PREFIX)) {
                throw new IllegalArgumentException("挂载路径必须为 mounts/ 前缀的工作区相对路径: " + mountPath);
            }
        }
    }
}
