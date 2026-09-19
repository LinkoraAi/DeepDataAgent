package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.application.port.ArtifactDeliveryPort;
import com.linkroa.deepdataagent.runtime.application.port.ArtifactDeliverySignalPort;
import com.linkroa.deepdataagent.runtime.domain.factory.AgentFactoryPort;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.factory.SessionWorkspacePort;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.McpConnectionCredential;
import com.linkroa.deepdataagent.runtime.domain.model.McpToolRuntimeName;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.service.McpCredentialResolver;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import com.linkroa.deepdataagent.shared.config.EgressProperties;
import com.linkroa.deepdataagent.shared.net.EgressTrustPolicy;
import com.linkroa.deepdataagent.shared.security.SecretMasker;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.builtin.TodoTools;
import io.agentscope.extensions.postgresql.snapshot.PostgresSnapshotSpec;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.agentscope.harness.agent.tools.HarnessPlatformTools;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrationListener;
import io.agentscope.harness.agent.tools.McpServerRegistrationResult;
import io.agentscope.harness.agent.tools.ToolsConfig;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * <p><b>MCP 接线与凭据红线</b>：版本声明的 MCP 连接（{@code spec.mcpConnections()}）经
 * {@link #buildToolsConfig} 写入 {@code ToolsConfig.mcpServers}（streamable-http，
 * 恒显式下发请求 / 初始化超时），Harness {@code build()} 内 {@code McpServerRegistrar} 据 override
 * 配置注册 MCP 工具——<b>注册期同步完成握手（{@code initialize}）与工具枚举（{@code tools/list}）</b>，
 * 实现 MCP discovery；枚举结果即该连接进入模型工具清单的工具集合（平台不自维护静态清单）。
 * 保管库凭证由 {@link McpCredentialResolver} 在 JVM 侧按 <b>URL 全等</b>匹配后注入 MCP 客户端
 * 请求头（握手与枚举请求同样携带该头）——<b>明文凭据 MUST NOT 进沙箱环境变量 / 日志 / 响应</b>
 * （原 {@code VAULT_MCP_CREDENTIALS} 聚合注入已废除）。注册终态经
 * {@link McpServerRegistrationListener} 回调转为诊断（见 {@link #mcpRegistrationListener()}）。
 * <b>出网信任边界</b>：装配期对每个连接目标先做一次共享判定（{@link EgressTrustPolicy}，
 * 与 validate 探测 / OAuth 刷新同一份策略），确证越界的连接不下发给框架（见 {@link #buildMcpServers}）。
 * <b>MCP 工具运行时实名</b>：框架以服务端原始工具名注册（与其文档声明的前缀命名不一致），
 * {@code build()} 之后由 {@link #renameMcpTools} 统一改为 {@code mcp__{server}__{tool}}
 * ——权限规则键、{@code agent.mcp_tool_use} 投影与执行侧 MCP 判定同以该实名为准（D16 路径 B）。</p>
 * <p><b>联网工具默认关闭（2.0.3 升级裁定）</b>：框架 2.0.3 起在 {@code build()} 末期
 * <b>无条件注册</b> {@code web_fetch} / {@code web_search}（无 disableWebTools 开关、不在
 * 平台基座豁免集内）。2.0.1 无此二工具，接受框架默认即等于升级静默扩大能力面——
 * {@code web_fetch} 从后端宿主 JVM 直接出网、不经沙箱隔离，仅校验 http/https 前缀、
 * 无内网地址黑名单（SSRF 面）；{@code web_search} 依赖 TAVILY_API_KEY（未配置时返回
 * 可读配置错误、不抛异常）。故 {@link #buildToolsConfig} 在无白名单时恒把两名并入
 * deny；显式启用须用户在 {@code enabled_tools} 白名单点名 {@code WebFetch}/{@code WebSearch}。</p>
 * <p><b>框架能力「不采用」清单（防后续升级误当遗漏）</b>：不启用
 * {@code FinalAnswerFilterMiddleware}（与逐 token SSE 账本冲突）；不接入 transcript 三件套
 * （transcript / session_log / session_search）、teams、{@code subagentFactory}、
 * {@code filesystemRoute}（RoutedSandboxFilesystem）、{@code getTaskRepository()}——均为有意取舍，
 * 见 openspec 变更 upgrade-agentscope-203 design.md（D13 等）。
 * <b>已重新裁定</b>：{@code McpServerRegistrationListener} 由「不采用」转为<b>采用</b>
 * （align-qoder-vault-credential-capabilities D12）——既有「不采用」判断形成于 MCP 路径未接线的
 * 语境（无可观测对象），MCP 接线后该回调首次具备真实用途，且恰好覆盖「含服务器名与失败类别的诊断」。</p>
 * <p><b>会话挂载装配（sandbox-workspace-file-mounts）框架升级红灯位</b>：挂载视图由装配解析期
 * reconcile 产出（{@link AgentAssemblySpec#fileMounts()}），本工厂只读规格拼系统提示清单段落，
 * 并按宿主挂载目录实况下发<b>单个</b> {@code key=mounts} 的只读 {@link BindMountEntry}。
 * 以下四点属框架行为，<b>每次框架升级 MUST 复核</b>：
 * ① {@code -v} 拼接（{@code DockerSandbox#buildDockerRunCommand} 对 bind mount 条目的
 * host:container:ro 组装）；② 快照排除（{@code WorkspaceMountSupport#tarExcludeArgsForBindMounts}
 * ——挂载字节不得随快照固化）；③ {@code mounts} 目录名与框架保留名不冲突
 * （{@code WorkspaceConstants} 及 {@code plans} / {@code subagents} / {@code .skills-cache} /
 * {@code .index}）；④ {@code IsolationScope} 取值变更需同步复核挂载落点与会话清理范围
 * （挂载恒按 sessionId 派生，与 scope 解耦，D17）。</p>
 */
@Component
public class AgentscopeHarnessAgentFactory implements AgentFactoryPort {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeHarnessAgentFactory.class);

    /** 工作区指令文件名（Harness 上下文加载与沙箱投影的约定名）。 */
    private static final String AGENTS_MD_FILE_NAME = "AGENTS.md";

    /** 系统提示挂载清单段落标题（D14：清单只进本轮系统提示，MUST NOT 写入跨会话共享的 AGENTS.md）。 */
    private static final String MOUNTS_SECTION_TITLE = "## 会话挂载文件（只读）";

    /** MCP 传输形态：streamable HTTP（{@code McpServerRegistrar} 按 toLowerCase 匹配该字域）。 */
    private static final String MCP_TRANSPORT_STREAMABLE_HTTP = "streamable-http";

    @Resource
    private PostgresAgentStateStore stateStore;
    @Resource
    private PostgresSnapshotSpec snapshotSpec;
    /** 会话产出投递端口（本 BC）：deliver_artifact 交付目标把沙箱产出登记为 scope=session File。 */
    @Resource
    private ArtifactDeliveryPort artifactDeliveryPort;
    /** 产物交付信号上抛端口（本 BC）：登记成功后由应用层落 agent.artifact_delivered 事件（5.4）。 */
    @Resource
    private ArtifactDeliverySignalPort artifactDeliverySignalPort;
    /** 会话工作区布局端口：Agent 工作区根与挂载目录宿主路径的统一来源（与物化编排同源）。 */
    @Resource
    private SessionWorkspacePort sessionWorkspacePort;
    /** MCP 凭据解析器：按 Target 精确匹配连接与保管库凭证，产出 JVM 侧鉴权头（明文不进沙箱）。 */
    @Resource
    private McpCredentialResolver mcpCredentialResolver;
    /** 运行时配置载体：MCP 请求 / 初始化超时的唯一来源（超时属受控配置面，不写死为魔法常量）。 */
    @Resource
    private AgentRuntimeProperties runtimeProperties;
    /** 出网信任边界配置：MCP 建连装配期预检与 validate 探测 / OAuth 刷新共用同一白名单开关（D8）。 */
    @Resource
    private EgressProperties egressProperties;

    @Override
    public BuiltAgent build(AgentAssemblySpec spec) {
        HarnessAgent agent = buildNew(spec);
        // MCP 工具改名须在 discovery 之后（枚举结果即改名对象集合，也是 configs[].name 匹配的唯一基线），
        // 见 renameMcpTools
        renameMcpTools(agent.getToolkit());
        // 内置工具白名单在改名之后由平台侧施加：框架 ToolFilter 的 allow 语义是「名单外 catalogued 工具
        // 一律移除」，而 MCP 工具在过滤时仍是服务端原始名、事后才改为 mcp__{server}__{tool} 实名，
        // 故经框架 allow 下达白名单会把该版本声明的 MCP 工具整体剔除；内置白名单按契约只治理内置工具
        applyBuiltinWhitelist(agent.getToolkit(), spec);
        return new HarnessBuiltAgent(agent);
    }

    /**
     * 把框架注册的 MCP 工具改名为平台运行时实名 {@code mcp__{server}__{tool}}（D16 · 路径 B）。
     * <p>框架 2.0.3 以服务端<b>原始工具名</b>注册 MCP 工具（与其官方文档声明的
     * {@code mcp__{server}__{tool}} 命名不一致），而平台全链路的工具身份都建立在实名约定上：
     * 权限规则键（{@link #buildPermissionContext} 经 {@link HarnessToolNames#toMcpRuntimeName} 落表）、
     * runState 的 {@code evaluated_permission} 投影（{@code registerMcpToolPolicies}）与执行侧
     * MCP 判定（{@code McpToolRuntimeName.serverNameOf}）。不改名则版本
     * {@code always_ask} / {@code always_deny} 规则永不命中（静默失效），MCP 调用也会被误投影为
     * {@code agent.tool_use}。</p>
     * <p><b>为何在 {@code build()} 之后</b>：MCP discovery（握手 + 工具枚举）发生在
     * HarnessAgent{@code .build()} 内部，枚举结果既是改名对象集合、也是
     * {@code mcp_toolset.configs[].name} 匹配的唯一基线（平台不自维护静态清单）。</p>
     * <p><b>框架升级红灯位</b>：改名依赖 {@code Toolkit#removeToolIfSame} 与
     * {@code registration().agentTool(...)} 两个注册面 API 的语义；框架未来若按文档实现前缀命名，
     * 本方法据前缀跳过（不产生双重前缀）。改名前已由框架执行 allow/deny 过滤，
     * 改名不改变工具可见性判定结果。</p>
     *
     * @param toolkit 装配后的工具台账（含框架本轮枚举出的 MCP 工具）
     */
    // package-private：装配单测直查改名结果与规则命中（align-qoder-vault D16 / task 1.18）
    void renameMcpTools(Toolkit toolkit) {
        List<String> renamed = new ArrayList<>();
        for (String registeredName : List.copyOf(toolkit.getToolNames())) {
            if (McpToolRuntimeName.isMcpTool(registeredName)) {
                continue;
            }
            if (!(toolkit.getTool(registeredName) instanceof ToolBase base) || base.getMcpName() == null) {
                continue;
            }
            String runtimeName = McpToolRuntimeName.of(base.getMcpName(), registeredName);
            if (!toolkit.removeToolIfSame(registeredName, base)) {
                continue;
            }
            toolkit.registration().agentTool(McpRuntimeNamedTool.of(runtimeName, base)).apply();
            renamed.add(runtimeName);
        }
        if (!renamed.isEmpty()) {
            log.info("MCP 工具按运行时实名注册: {}", renamed);
        }
    }

    /**
     * 施加内置工具白名单（平台侧，{@code build()} 之后、MCP 实名化之后）。
     * <p>语义（对齐 runtime/tools spec「内置工具集与白名单启用」）：白名单只治理<b>内置工具</b>
     * ——版本声明的 {@code mcp_toolset} 工具与 Harness 平台工具（子 agent / 团队 / 计划 / 技能等，
     * 见 {@code HarnessPlatformTools}）不受白名单治理；{@code todo_write} 作为平台辅助工具恒并入白名单。
     * 名单外内置工具（含 {@code list_files} / {@code deliver_artifact} / 联网两名等无契约映射者）
     * 一律移除。</p>
     * <p><b>为何不交给框架 allow</b>：框架 {@code ToolFilter} 在 {@code build()} 末期按 allow 移除
     * 「名单外且非平台工具」的一切注册项，而 MCP 工具此时仍是服务端原始名（实名化发生在其后），
     * 故以 allow 下达白名单会把该版本的 MCP 工具<b>整体剔除</b>（{@code always_ask}/{@code always_deny}
     * 规则与 {@code mcp_tool_use} 投影随之失效）。平台侧施加可在实名化之后精确区分内置与 MCP。</p>
     * <p>{@code deny} 仍由 {@link #buildToolsConfig} 经框架下达（deny 恒移除，且与 allow 无交互）。</p>
     *
     * @param toolkit 装配后的工具台账（已含本轮枚举并实名化的 MCP 工具）
     * @param spec    装配规格（白名单来源）
     */
    // package-private：装配单测直查白名单裁剪与 MCP / 平台工具豁免
    void applyBuiltinWhitelist(Toolkit toolkit, AgentAssemblySpec spec) {
        AgentAssemblySpec.ToolVisibility visibility = spec.toolVisibility();
        if (visibility == null || visibility.allowedTools().isEmpty()) {
            return;
        }
        Set<String> allow = new LinkedHashSet<>(HarnessToolNames.toRuntimeNames(visibility.allowedTools()));
        allow.add(HarnessToolNames.TODO_WRITE);
        List<String> removed = new ArrayList<>();
        for (String name : List.copyOf(toolkit.getToolNames())) {
            if (McpToolRuntimeName.isMcpTool(name) || HarnessPlatformTools.isPlatformTool(name)) {
                continue;
            }
            if (!allow.contains(name)) {
                toolkit.removeTool(name);
                removed.add(name);
            }
        }
        if (!removed.isEmpty()) {
            log.info("内置工具白名单裁剪：移除 {} 个名单外内置工具: {}", removed.size(), removed);
        }
    }

    private HarnessAgent buildNew(AgentAssemblySpec spec) {
        Toolkit toolkit = buildToolkit(spec);

        DockerFilesystemSpec filesystem = new DockerFilesystemSpec()
                // 沙箱 live 句柄由框架 SandboxLifecycleMiddleware 按调用绑定
                // （setSandbox/clearSandboxIfCurrent CAS 清理），平台不注入自定义 client
                .image(spec.sandbox().image())
                .environment(sandboxEnvironment(spec))
                .memorySizeBytes(spec.sandbox().memoryBytes())
                .cpuCount(spec.sandbox().cpuCount())
                .snapshotSpec(snapshotSpec);
        // 会话挂载 bind mount：宿主挂载目录存在且非空才下发（目录缺失 / 空 = 保持框架默认布局）；
        // 与系统提示清单同源（同一 reconcile 视图驱动），只读恒定、不做配置项（D18）
        WorkspaceSpec mountsWorkspace = buildMountsWorkspaceSpec(spec);
        if (mountsWorkspace != null) {
            filesystem.workspaceSpec(mountsWorkspace);
        }
        filesystem.isolationScope(IsolationScope.SESSION);

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(spec.name())
                .model(resolveModel(spec))
                .maxIters(spec.maxIters())
                .agentId(spec.agentId())
                .toolkit(toolkit)
                .filesystem(filesystem)
                // 框架内置 deliver_artifact 工具（仅主 agent 注册），字节→File 的登记
                // 语义由平台 SPI 目标承载，归属固定为本轮会话/属主
                .artifactDeliveryTarget(new SessionArtifactDeliveryTarget(
                        artifactDeliveryPort, artifactDeliverySignalPort, spec.sessionId(), spec.ownerId()))
                .stateStore(stateStore)
                .skillRepository(new RuntimeSkillRepository(spec.skills()))
                // MCP 注册终态回调（D12 诊断与健康观测）：单条连接不可用由框架内建 try/catch 降级
                // （不阻断装配、该连接工具不入清单、其余照常），平台侧补齐含服务器名与失败类别的诊断
                .mcpServerRegistrationListener(mcpRegistrationListener());

        // 系统提示 = 台账原文 + 挂载清单固定段落（D14：数据只取 spec.fileMounts()，
        // 与 bind mount 同一 reconcile 视图同源；无挂载不拼段落）
        String sysPrompt = buildSysPrompt(spec);
        if (StringUtils.isNotBlank(sysPrompt)) {
            builder.sysPrompt(sysPrompt);
        }
        // AGENTS.md：物化到该 Agent 的宿主工作区根，Harness 据此注入系统提示并投影进沙箱
        Path workspace = materializeAgentsMd(spec);
        if (workspace != null) {
            builder.workspace(workspace);
        }
        // 权限引擎：仅当配置了 ask/deny 工具策略时启用（否则保持 trivial 轻量路径，行为零扰动）
        PermissionContextState permissionContext = buildPermissionContext(spec.toolPolicies());
        if (permissionContext != null) {
            builder.permissionContext(permissionContext);
        }
        // 工具可见性：契约名名单翻译为运行时实名后经 ToolsConfig 下发，
        // 框架在 build() 末期自动注册文件/Shell 工具之后再执行 ToolFilter
        // （deny 恒移除；allow 非空移除「白名单外且非平台基座」项）。
        // buildToolsConfig 恒非空（平台对工具面恒有主张：至少默认关闭框架无条件注册的
        // 联网工具），保留判空仅维持可读性、不为 null 语义兜底
        ToolsConfig toolsConfig = buildToolsConfig(spec);
        if (toolsConfig != null) {
            builder.toolsConfig(toolsConfig);
        }
        return builder.build();
    }

    /**
     * 由逐工具权限策略装配 Harness 权限评估上下文。
     * <p>策略映射：{@code always_ask → ASK 规则}（评估序先于工具自检，必然触发 HITL 暂停确认）、
     * {@code always_deny → DENY 规则}（评估序首位，直接拒绝）。规则名必须为 Harness 注册实名——
     * 内置工具契约名（Bash / Read 等）经 {@link HarnessToolNames} 翻译，MCP 工具的服务端原始工具名
     * 经 {@link HarnessToolNames#toMcpRuntimeName} 翻译为 {@code mcp__{server}__{tool}}
     * （按原始名落表会永不命中且静默失效）；两类均无注册实体的名称永不命中任何工具，静默跳过不报错。</p>
     * <p>模式取 {@link PermissionMode#BYPASS}
     * 而非 {@code DEFAULT}：BYPASS 下未显式配置策略的工具兜底放行（ALLOW），保持「无策略即自主执行」
     * 的现状语义；若用 DEFAULT 模式，未配 allow 规则的工具会全部落入 ASK，造成行为回归。</p>
     * <p>{@code always_allow} 在 BYPASS 兜底下天然放行，无需生成规则。策略列表为空、仅存在 allow
     * 策略、或全部 ask/deny 名称均无运行时实体时返回 {@code null}（不设置权限上下文，触发
     * {@link PermissionContextState#isTrivial()} 轻量路径，完全保持框架默认的工具自检门控行为，
     * 避免徒设 BYPASS 压制非 safety 工具自检）。BYPASS 会压制非 safety 类工具自检 ASK（仅影响配置了
     * 策略的 Agent，safety 自检 ASK 仍被尊重）。</p>
     *
     * @param toolPolicies 逐工具权限策略（可空/空）
     * @return 权限上下文；无可生效的 ask/deny 规则时为 {@code null}
     */
    // package-private：装配单测直查契约名→实名翻译与 ask/deny 规则构型（enforce-tool-visibility-execution D2）
    PermissionContextState buildPermissionContext(List<AgentAssemblySpec.ToolExecutionPolicy> toolPolicies) {
        if (toolPolicies == null || toolPolicies.isEmpty()) {
            return null;
        }
        PermissionContextState.Builder context = PermissionContextState.builder().mode(PermissionMode.BYPASS);
        boolean hasRule = false;
        for (AgentAssemblySpec.ToolExecutionPolicy policy : toolPolicies) {
            Optional<String> runtimeName = resolveRuntimeName(policy);
            if (runtimeName.isEmpty()) {
                continue;
            }
            if (AgentAssemblySpec.ToolExecutionPolicy.POLICY_ALWAYS_ASK.equals(policy.permissionPolicy())) {
                context.addAskRule(runtimeName.get(), toolNameRule(runtimeName.get(), PermissionBehavior.ASK));
                hasRule = true;
            } else if (AgentAssemblySpec.ToolExecutionPolicy.POLICY_ALWAYS_DENY.equals(policy.permissionPolicy())) {
                context.addDenyRule(runtimeName.get(), toolNameRule(runtimeName.get(), PermissionBehavior.DENY));
                hasRule = true;
            }
        }
        return hasRule ? context.build() : null;
    }

    /**
     * 策略项 → Harness 运行时实名（唯一翻译入口）。
     * <p>携带 {@code mcpServerName} 的策略项来自 {@code mcp_toolset}：{@code name} 是服务端暴露的
     * <b>原始工具名</b>，须与服务器名共同构成 {@code mcp__{server}__{tool}} 才与框架注册实名一致；
     * 其余策略项走内置工具契约名映射表（无注册实体返回空，调用方静默跳过）。</p>
     *
     * @param policy 策略项（非空）
     * @return 运行时实名；无注册实体时为空 Optional
     */
    private static Optional<String> resolveRuntimeName(AgentAssemblySpec.ToolExecutionPolicy policy) {
        if (StringUtils.isNotBlank(policy.mcpServerName())) {
            return Optional.of(HarnessToolNames.toMcpRuntimeName(policy.mcpServerName(), policy.name()));
        }
        return HarnessToolNames.toRuntimeName(policy.name());
    }

    /**
     * 由工具可见性约束装配 Harness 工具过滤指令。
     * <p><b>平台对工具面恒有主张</b>：返回值不再可能为 {@code null}——即使契约未配置任何
     * 可见性约束，也下发 {@code deny=[web_fetch, web_search]} 关闭框架 2.0.3 起
     * <b>无条件注册</b>的两个联网工具（升级不得静默扩大默认能力面：{@code web_fetch}
     * 从后端宿主 JVM 出网、不经沙箱隔离，仅校验 http/https 前缀、无内网黑名单，SSRF 面；
     * {@code web_search} 需 TAVILY_API_KEY，未配置时返回可读错误而非抛异常）。</p>
     * <p>契约名单经 {@link HarnessToolNames} 统一翻译为运行时实名，无注册实体的契约名
     * （ImageGen 等）静默跳过，与 ToolFilter 对未知名 WARN 忽略的语义一致。</p>
     * <p>记忆库清单占位工具（{@code memory_store_list}）已下线，不再以任何方式装配。</p>
     * <p><b>只下发 deny，不下发 allow</b>：框架 {@code ToolFilter} 的 allow 语义是「名单外 catalogued
     * 工具（含 MCP 工具——过滤时它们还是服务端原始名）一律移除」，与「内置白名单只治理内置工具」的
     * 契约语义不符；白名单改由 {@link #applyBuiltinWhitelist} 在 {@code build()} 之后、MCP 实名化之后
     * 平台侧施加（见该方法的语义说明）。</p>
     * <p>联网两名并入口径：无白名单时并入 deny（去重、保序，{@code disallowed_tools} /
     * {@code configs[].enabled=false} 翻译出的隐藏名单同样与两名合并）；存在白名单时
     * <b>不</b>并入——白名单已在平台侧把二者排除，除非白名单显式列入 {@code WebFetch}/{@code WebSearch}
     * 才按实名放行（白名单场景下 deny 仍照常承载用户显式配置的隐藏名单，仅不追加联网两名）。</p>
     *
     * @param spec 装配规格（可见性约束来源）
     * @return 过滤指令（恒非空：至少默认关闭联网工具）
     */
    // package-private：装配单测直查 deny 翻译、联网默认关闭结果
    ToolsConfig buildToolsConfig(AgentAssemblySpec spec) {
        AgentAssemblySpec.ToolVisibility visibility = spec.toolVisibility();
        boolean hasWhitelist = visibility != null && !visibility.allowedTools().isEmpty();
        ToolsConfig config = new ToolsConfig();
        Set<String> deny = new LinkedHashSet<>();
        if (visibility != null) {
            deny.addAll(HarnessToolNames.toRuntimeNames(visibility.hiddenTools()));
        }
        if (!hasWhitelist) {
            // 框架无条件注册的联网工具：平台默认关闭（白名单场景由 allow 天然排除，无需 deny）
            deny.add(HarnessToolNames.WEB_FETCH);
            deny.add(HarnessToolNames.WEB_SEARCH);
        }
        if (!deny.isEmpty()) {
            config.setDeny(List.copyOf(deny));
        }
        // MCP 接线：版本声明的连接经 JVM 侧凭据解析器产出鉴权头后写入 mcpServers，
        // Harness build() 中 McpServerRegistrar 读取 override 的 getMcpServers() 注册 MCP 工具。
        // 明文 token 只存在于 McpServerConfig.headers 并随 MCP 客户端出网，MUST NOT 打进日志
        // （该类未覆写 toString，仍禁止整体输出 ToolsConfig 以防序列化日志泄露 headers）。
        Map<String, McpServerConfig> mcpServers = buildMcpServers(spec);
        if (!mcpServers.isEmpty()) {
            config.setMcpServers(mcpServers);
        }
        return config;
    }

    /**
     * 装配 MCP 服务器配置：{@code spec.mcpConnections()} 逐条映射为 streamable-http 条目，
     * 鉴权头由 {@link McpCredentialResolver} 按 Target 匹配保管库凭证产出（无命中 = 无鉴权连接）。
     * <p>Map 键为连接名（McpServerRegistrar 以此为服务器实名注册工具）。连接清单为空时
     * 返回空 Map（不下发 mcpServers，保持「版本未声明 MCP = 行为零扰动」）。</p>
     * <p><b>出网信任边界（design D8）</b>：连接由框架持有，本处只做工位预检——确证越界
     * （回环 / 本机通配 / 链路本地含云元数据 / 组播 / RFC1918 / {@code host.docker.internal}，
     * 白名单开关关闭时）的连接<b>不下发给框架</b>，按既有降级语义（D12）留在装配诊断中；
     * 无法解析的目标按「无法判定」放行（此时连接同样无法建立，交由框架注册终态回调处置）。
     * 因建连方为框架，本预检无法把「已校验 IP」交给连接侧，域名在预检与建连之间改指向的窗口
     * 由框架承担；<b>白名单关闭时内网目标在装配期即被剔除，这是 MCP 建连面可控的最早拦截点</b>。</p>
     */
    private Map<String, McpServerConfig> buildMcpServers(AgentAssemblySpec spec) {
        if (spec.mcpConnections().isEmpty()) {
            return Map.of();
        }
        List<McpConnectionCredential> resolved = mcpCredentialResolver.resolve(
                spec.mcpConnections(), spec.vaultCredentials());
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        for (McpConnectionCredential credential : resolved) {
            String host = hostOf(credential.url());
            if (EgressTrustPolicy.isBlockedTarget(host, egressProperties.isAllowPrivateNetwork())) {
                // 诊断只含服务器名与目标主机（不落完整 URL，避免其中的查询参数带入敏感值）
                log.warn(mcpEgressDiagnostic(credential.connectionName(), host));
                continue;
            }
            McpServerConfig serverConfig = new McpServerConfig();
            serverConfig.setTransport(MCP_TRANSPORT_STREAMABLE_HTTP);
            serverConfig.setUrl(credential.url());
            if (!credential.headers().isEmpty()) {
                serverConfig.setHeaders(credential.headers());
            }
            // 超时显式下发：装配期同步阻塞（框架 build() 内 buildAsync().block() 完成握手与工具枚举），
            // 不接受连接但握手无响应的服务器必须在超时内收敛为失败降级，MUST NOT 拖住整轮装配
            serverConfig.setTimeout(runtimeProperties.getMcpRequestTimeout());
            serverConfig.setInitializationTimeout(runtimeProperties.getMcpInitializationTimeout());
            servers.put(credential.connectionName(), serverConfig);
        }
        return servers;
    }

    /**
     * 目标主机名（出网边界判定入参）：URL 非法 / 缺失主机时返回 {@code null}，
     * 由 {@link EgressTrustPolicy#isBlockedTarget} 按「无法判定」处置。
     */
    private static String hostOf(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        try {
            return URI.create(url.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 出网边界降级诊断（D8 预检 + D12 降级）：服务器名 + 目标主机 + 失败类别，
     * <b>不含完整 URL</b>（其查询参数可能携带敏感值）、不含鉴权头与凭证明文。
     *
     * @param serverName 连接名（服务端实名）
     * @param host       目标主机名
     * @return 单行诊断文案
     */
    // package-private：装配单测直查诊断文案（align-qoder-vault D8）
    String mcpEgressDiagnostic(String serverName, String host) {
        return "MCP 服务器超出出网信任边界，该连接工具不进入模型工具清单: name=%s, host=%s, cause=%s"
                .formatted(serverName, host, "out_of_trust_boundary");
    }

    /**
     * MCP 服务器注册终态回调（D12 降级诊断与健康观测）。
     * <p>框架 {@code McpServerRegistrar.register} 对每个服务器条目独立 try/catch——失败仅跳过该条、
     * 其客户端被关闭（工具因而不会进入模型工具清单）、其余条目照常注册，故「单连接失败不阻断装配」
     * 与「失败连接工具不入清单」两条降级语义由框架直接满足；本回调补齐第三条「留下可读诊断」，
     * 避免降级表现为「工具静默消失」（用户无从分辨配置未生效还是服务器故障）。</p>
     * <p><b>日志红线</b>：只记录服务器名、传输形态、终态与失败类别（异常类名 + 经
     * {@link SecretMasker#maskSecrets} 形态脱敏的异常消息）。MCP 请求头 / 凭据明文不经本链路，
     * 且第三方服务器错误消息可能回显请求内容，故一律先脱敏再落日志。</p>
     *
     * @return 逐服务器终态回调（成功 info、失败/跳过 warn，均不含明文）
     */
    // package-private：装配单测直查诊断文案与脱敏面（align-qoder-vault D12）
    McpServerRegistrationListener mcpRegistrationListener() {
        return result -> {
            if (result.status() == McpServerRegistrationResult.Status.SUCCESS) {
                log.info("MCP 服务器注册成功: name={}, transport={}",
                        result.serverName(), result.transport());
                return;
            }
            log.warn(mcpRegistrationDiagnostic(result));
        };
    }

    /**
     * 降级诊断文案：服务器名 + 传输形态 + 终态 + 失败类别（异常类名与经形态脱敏的异常消息）。
     * <p>抽为独立方法以便单测断言「诊断可读」与「不含明文」两条要求（日志本身不作断言面）。</p>
     *
     * @param result 逐服务器注册终态（非 SUCCESS 时 {@code cause} 由框架契约保证非空）
     * @return 单行诊断文案（不含请求头 / 凭证明文）
     */
    // package-private：装配单测直查诊断文案与脱敏面（align-qoder-vault D12）
    String mcpRegistrationDiagnostic(McpServerRegistrationResult result) {
        return "MCP 服务器不可用，该连接工具不进入模型工具清单: name=%s, transport=%s, status=%s, cause=%s: %s"
                .formatted(result.serverName(), result.transport(), result.status(),
                        result.cause() == null ? "unknown" : result.cause().getClass().getSimpleName(),
                        SecretMasker.maskSecrets(result.cause() == null ? null : result.cause().getMessage()));
    }

    /** 构造工具名级权限规则（ruleContent 为 null = 命中该工具的全部调用）。 */
    private static PermissionRule toolNameRule(String toolName, PermissionBehavior behavior) {
        return new PermissionRule(toolName, null, behavior, "agentToolPolicy");
    }

    /**
     * 把装配规格中的 AGENTS.md 物化到宿主侧 Agent 工作区根
     * （{@code <app.agent.workspace-root>/<agentId>/AGENTS.md}，经
     * {@link SessionWorkspacePort#agentWorkspace} 统一取路径、内含逃逸防护）。
     * <p>Harness 的 {@code WorkspaceContextMiddleware} 会把工作区根的 AGENTS.md 注入系统提示，
     * {@code SandboxFilesystemSpec} 的 workspace projection 默认包含根列表同样含
     * {@code AGENTS.md}，因此同一份内容同时到达「模型上下文」与「沙箱文件系统」两个平面。</p>
     * <p><b>工作区根恒为 per-agent</b>（D4 撤回 per-session 根）：会话隔离由框架
     * {@code IsolationScope.SESSION} 命名空间层承担，会话挂载目录
     * {@code <agentWorkspace>/<sessionId>/mounts} 在其下，与 AGENTS.md 的 Agent 级共享面互不重叠
     * ——挂载清单因此 MUST NOT 写入本文件（跨会话泄露，见 D14）。</p>
     * <p>未配置 AGENTS.md 时返回 {@code null}（不设置工作区，保持框架默认解析）；
     * 磁盘写入失败或路径越界时记录错误并返回 {@code null}，不阻断本轮执行——系统提示词仍完整可用。</p>
     *
     * @return Agent 工作区根目录，未配置或物化失败时为 {@code null}
     */
    private Path materializeAgentsMd(AgentAssemblySpec spec) {
        if (StringUtils.isBlank(spec.agentsMd())) {
            return null;
        }
        try {
            Path agentWorkspace = sessionWorkspacePort.agentWorkspace(spec.agentId());
            Files.createDirectories(agentWorkspace);
            Files.writeString(agentWorkspace.resolve(AGENTS_MD_FILE_NAME), spec.agentsMd(),
                    StandardCharsets.UTF_8);
            return agentWorkspace;
        } catch (IOException | IllegalArgumentException e) {
            log.error("AGENTS.md 物化失败，本轮以无工作区模式运行: agentId={}", spec.agentId(), e);
            return null;
        }
    }

    /**
     * 组装本轮系统提示：台账系统提示原文 + 会话挂载清单固定段落（D14）。
     * <p>段落每项一行「原始文件名（字节数）→ 沙箱路径（只读）」，末尾一句产出物走交付通道
     * 的引导——挂载后的宿主文件名是 {@code mounts/<file_id>} 形态（原始语义名只存在于元数据），
     * 不告知清单模型无法把用户口中的文件映射到挂载路径。无挂载时原样返回（MUST NOT 拼空段落）。</p>
     */
    // package-private：装配单测直查清单段落拼装的有无两态与保序（sandbox-workspace-file-mounts D14）
    static String buildSysPrompt(AgentAssemblySpec spec) {
        if (spec.fileMounts().isEmpty()) {
            return spec.systemPrompt();
        }
        StringBuilder prompt = new StringBuilder(StringUtils.defaultString(spec.systemPrompt()));
        prompt.append("\n\n").append(MOUNTS_SECTION_TITLE).append('\n')
                .append("以下用户文件已挂载到本会话沙箱工作区，只读（不得写入、修改或删除 mounts 目录）：\n");
        for (AgentAssemblySpec.FileMountRef mount : spec.fileMounts()) {
            prompt.append("- ").append(mount.filename())
                    .append("（").append(mount.sizeBytes()).append(" 字节）→ ")
                    .append("/workspace/").append(mount.mountPath())
                    .append("（只读）\n");
        }
        prompt.append("如需产出文件，请写入工作区的其他目录，并经交付通道（deliver_artifact）登记。");
        return prompt.toString();
    }

    /**
     * 构造仅承载会话挂载 bind mount 的工作区规格（D8 / D9 / D18）。
     * <p>宿主挂载目录（{@code <agentWorkspace>/<sessionId>/mounts}）存在且<b>非空</b>时，
     * 放入唯一一个 {@code key=mounts} 的 {@link BindMountEntry}（框架据 key 映射到
     * {@code /workspace/mounts}，{@code readOnly=true} 恒定）；目录缺失 / 为空 / 无挂载视图时
     * 返回 {@code null}（不下发条目，保持框架默认布局）。不改 {@code workspaceProjectionRoots}、
     * 不改 {@code isolationScope}。</p>
     */
    // package-private：装配单测直查目录实况三态（缺失/空/非空）与只读条目构型（sandbox-workspace-file-mounts D8）
    WorkspaceSpec buildMountsWorkspaceSpec(AgentAssemblySpec spec) {
        if (spec.fileMounts().isEmpty() || StringUtils.isBlank(spec.sessionId())) {
            return null;
        }
        try {
            Path mountsDir = sessionWorkspacePort.mountsDirectory(spec.agentId(), spec.sessionId());
            if (!Files.isDirectory(mountsDir) || isEmptyDirectory(mountsDir)) {
                return null;
            }
            BindMountEntry mounts = new BindMountEntry();
            mounts.setHostPath(mountsDir.toAbsolutePath().normalize().toString());
            mounts.setReadOnly(true);
            WorkspaceSpec workspaceSpec = new WorkspaceSpec();
            workspaceSpec.getEntries().put(SessionResource.MOUNTS_ROOT, mounts);
            return workspaceSpec;
        } catch (IOException | RuntimeException e) {
            // 挂载目录不可达不阻断执行：退化为「沙箱内无挂载」，清单仍由系统提示如实呈现
            log.error("会话挂载目录探测失败，本轮不下发 bind mount: sessionId={}",
                    spec.sessionId(), e);
            return null;
        }
    }

    /** 目录是否为空（一次迭代判定，不读文件内容）。 */
    private static boolean isEmptyDirectory(Path dir) throws IOException {
        try (var stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        }
    }

    /**
     * 按模型访问配置解析提供方模型：注入台账凭证 / API 端点与生效调优参数（effort /
     * context_window，经 {@link ModelCreationContext#option}）；凭证、端点与调优参数
     * 全缺省时退化到注册表默认解析。
     */
    private Model resolveModel(AgentAssemblySpec spec) {
        boolean hasTuning = StringUtils.isNotBlank(spec.modelEffort()) || spec.modelContextWindow() != null;
        if (StringUtils.isBlank(spec.credential()) && StringUtils.isBlank(spec.apiEndpointUrl()) && !hasTuning) {
            return ModelRegistry.resolve(spec.model());
        }
        ModelCreationContext.Builder context = ModelCreationContext.builder();
        if (StringUtils.isNotBlank(spec.credential())) {
            context.apiKey(spec.credential());
        }
        if (StringUtils.isNotBlank(spec.apiEndpointUrl())) {
            context.baseUrl(spec.apiEndpointUrl());
        }
        // 生效调优参数注入模型配置：显式档位优先、目录默认次之（解析已在 agent BC 装配期完成）
        if (StringUtils.isNotBlank(spec.modelEffort())) {
            context.option("effort", spec.modelEffort());
        }
        if (spec.modelContextWindow() != null) {
            context.option("context_window", spec.modelContextWindow());
        }
        return ModelRegistry.resolve(spec.model(), context.build());
    }

    /**
     * 沙箱数据面环境变量：仅会话级环境变量（Session 挂载注入），<b>零凭据</b>。
     * <p>保管库凭证不再聚合注入容器环境（原 {@code VAULT_MCP_CREDENTIALS} JSON 注入已删除——
     * 明文 token 落沙箱可被容器内任意进程读取，且全仓库无消费者）；鉴权注入改由 JVM 侧
     * {@link McpCredentialResolver} 解析后经 MCP 客户端请求头承担（见 {@link #buildToolsConfig}）。</p>
     */
    private static Map<String, String> sandboxEnvironment(AgentAssemblySpec spec) {
        return new HashMap<>(spec.environmentVariables());
    }

    /**
     * 构建工具集：按 AgentScope 官方推荐方式经 {@link Toolkit#registerTool(Object)}
     * 注册平台自有工具。基座仅注册内置 {@link TodoTools}；沙箱产出交付不再手动注册——
     * Harness 2.0.3 在已设置 {@code artifactDeliveryTarget(...)} 且未调用
     * {@code disableFilesystemTools()} 时，由 {@code build()} 自动注册框架内置
     * {@code deliver_artifact}（契约名 {@code DeliverArtifacts} 经 {@link HarnessToolNames}
     * 映射，allow/deny 与权限策略按名治理天然生效；归属 sessionId / ownerId 由
     * {@link SessionArtifactDeliveryTarget} 装配闭包固定，模型无从置入）。
     * 记忆库清单占位工具（{@code memory_store_list}）已下线、不再装配；真实记忆检索工具
     * 随记忆库内容端口在独立变更中上线。
     * <p>官方内置文件 / Shell / Web 工具（{@code FilesystemTool} /
     * {@code ShellExecuteTool} / {@code WebTools}）不在此手动注册——
     * {@link HarnessAgent.Builder#build()} 在已设置 {@code filesystem(...)} 时自动注入。
     * 用户技能经 {@code skillRepository} 挂载（见类 javadoc）。</p>
     *
     * @param spec 装配规格（保留装配入口签名；当前基座工具不读取其字段）
     */
    // package-private：装配单测直查工具台账（getToolNames）
    Toolkit buildToolkit(AgentAssemblySpec spec) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TodoTools());
        return toolkit;
    }
}