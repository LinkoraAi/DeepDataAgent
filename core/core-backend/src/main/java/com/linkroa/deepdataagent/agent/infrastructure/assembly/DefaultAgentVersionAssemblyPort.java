package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.application.dto.AgentMcpServerDTO;
import com.linkroa.deepdataagent.agent.application.dto.AgentToolPolicyDTO;
import com.linkroa.deepdataagent.agent.application.dto.AgentToolVisibilityDTO;
import com.linkroa.deepdataagent.agent.application.dto.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.agent.application.dto.ResolvedModelCredentialDTO;
import com.linkroa.deepdataagent.agent.application.dto.ResolvedSkillDTO;
import com.linkroa.deepdataagent.agent.application.port.AgentVersionAssemblyPort;
import com.linkroa.deepdataagent.agent.application.service.ModelCatalogService;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentTool;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.domain.model.ModelCatalogItem;
import com.linkroa.deepdataagent.agent.domain.model.ModelIndicator;
import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.ModelRef;
import com.linkroa.deepdataagent.agent.domain.model.SkillBinding;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelEffort;
import com.linkroa.deepdataagent.agent.domain.repository.AgentDefinitionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.ModelProfileRepository;
import com.linkroa.deepdataagent.agent.infrastructure.util.ModelCredentialEncryptionUtil;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.skill.api.SkillAssetApi;
import com.linkroa.deepdataagent.skill.api.dto.SkillContentDTO;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Agent「版本 + 模型」解析端口实现（发布号十进制解析 + 存在性/归档校验 + 凭证解密）。
 * <p>运行装配「仅校验 profile 存在，容忍 DISABLED」（决策 D6）；Agent 已归档时
 * 拒绝创建新会话（无回退）。</p>
 */
@Service
public class DefaultAgentVersionAssemblyPort implements AgentVersionAssemblyPort {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentVersionAssemblyPort.class);

    @Resource
    private AgentDefinitionRepository agentDefinitionRepository;
    @Resource
    private AgentVersionRepository agentVersionRepository;
    @Resource
    private ModelProfileRepository modelProfileRepository;
    @Resource
    private ModelCatalogService modelCatalogService;
    @Resource
    private ModelCredentialEncryptionUtil credentialEncryptionUtil;
    @Resource
    private SkillAssetApi skillAssetApi;

    @Override
    public ResolvedAgentAssemblyDTO resolve(String agentId, String versionNumber, Long ownerId) {
        // 校验链路（存在性/归档/版本/profile + owner 归属）与装配契约拼装复用一份查询结果
        VersionAndProfile validated = validateResolvable(agentId, versionNumber, ownerId);

        // 解密在基础设施层完成，明文凭证仅注入运行时工厂装配配置，不参与任何响应序列化
        String credential = resolveCredential(validated.profile());
        ModelTuning tuning = resolveTuning(validated.modelRef());
        return new ResolvedAgentAssemblyDTO(
                agentId,
                validated.versionRow().versionNumber(),
                validated.versionRow().name(),
                validated.versionRow().systemPrompt(),
                // AGENTS.md 能力已废止（旧字段下线），契约占位恒 null
                null,
                ModelIndicator.of(validated.profile().apiFormat(), validated.profile().modelName()).resolved(),
                validated.profile().toolCallRounds(),
                credential,
                validated.profile().apiEndpointUrl(),
                resolveSkills(validated.versionRow(), ownerId),
                // 环境 / 记忆库 / 保管库挂载不再来自版本快照（Session 执行时选择）；
                // 空位由 Session 挂载装配（阶段三）填充，版本快照本身不参与解析
                null,
                List.of(),
                List.of(),
                // 目录引用解析出的生效调优参数（显式档位优先，目录默认次之），随契约进入 Harness 模型配置
                tuning.effort(),
                tuning.contextWindow(),
                // 逐工具权限策略（tools_json configs 中 permission_policy 非空项），供 runtime 装配权限引擎
                resolveToolPolicies(validated.versionRow()),
                // 内置工具可见性约束（agent_toolset 白名单/隐藏名单聚合），供 runtime 生成 Harness 过滤指令
                resolveToolVisibility(validated.versionRow()),
                // 版本声明的 MCP 服务器清单（结构化发布，不含凭据材料），供 runtime 装配 JVM 侧 MCP 客户端
                resolveMcpServers(validated.versionRow())
        );
    }

    /**
     * 窄口径解析模型凭据段：复用 {@link #validateResolvable}（存在性 / 归档 / 版本 / profile +
     * owner 归属校验）与 {@link #resolveCredential}（解密模型凭证），恒返回非空 DTO。
     * <p>MUST NOT 加载技能正文（{@link #resolveSkills}）、解析工具策略（{@link #resolveToolPolicies}
     * / {@link #resolveToolVisibility}）或读工作区文件——那些属 {@link #resolve} 的全量装配职责。
     * 命中路径每轮调用本方法既取回凭证、又重跑归属校验（越权 / 归档 → 404，语义与 resolve 一致）。</p>
     */
    @Override
    public ResolvedModelCredentialDTO resolveModelCredential(String agentId, String versionNumber, Long ownerId) {
        VersionAndProfile validated = validateResolvable(agentId, versionNumber, ownerId);
        return new ResolvedModelCredentialDTO(
                resolveCredential(validated.profile()),
                validated.profile().apiEndpointUrl());
    }

    /**
     * 解析版本快照 {@code tools_json} 中逐工具权限策略：遍历各工具集 {@code configs[]}，
     * 仅收集 {@code permission_policy} 非空的配置项（always_allow / always_ask / always_deny），
     * 跨 toolset 类型统一展平（保持声明顺序）。
     * <p>{@code enabled=false}（隐藏并拒绝）与 {@code disallowed_tools} 属工具可见性语义，
     * 由 {@link #resolveToolVisibility(AgentVersion)} 单独聚合，不混入权限策略。</p>
     * <p>{@code mcp_toolset} 条目的 {@code configs[].name} 是<b>服务端暴露的原始工具名</b>，
     * 随策略项携带其 {@code mcp_server_name} 归属出站——运行时实名
     * {@code mcp__{server_name}__{tool_name}} 的翻译是 runtime 侧知识，agent BC 不越界。</p>
     */
    private List<AgentToolPolicyDTO> resolveToolPolicies(AgentVersion versionRow) {
        List<AgentToolPolicyDTO> policies = new ArrayList<>();
        for (AgentTool tool : versionRow.parseTools()) {
            boolean mcpToolset = AgentTool.TYPE_MCP_TOOLSET.equals(tool.type());
            for (AgentTool.ToolConfig config : tool.configs()) {
                if (config.permissionPolicy() != null) {
                    policies.add(new AgentToolPolicyDTO(config.name(), config.permissionPolicy(),
                            mcpToolset ? tool.mcpServerName() : null));
                }
            }
        }
        return policies;
    }

    /**
     * 解析版本快照 {@code tools_json} 中内置工具可见性约束（仅 {@code agent_toolset_20260401}
     * 条目参与，browser_toolset / mcp_toolset / custom 类型不产生可见性约束）：
     * 白名单为各条目 {@code enabled_tools} 并集（全缺省 = 空名单 = 无白名单约束，全量基座暴露）；
     * 隐藏名单为 {@code disallowed_tools} 与 {@code configs[].enabled=false} 并集，再扣除
     * {@code configs[].enabled=true}（对齐公开契约叠加顺序：默认集 → disallowed → configs[].enabled，
     * 显式启用末位叠加可复活）。集合并集保序去重。
     */
    private AgentToolVisibilityDTO resolveToolVisibility(AgentVersion versionRow) {
        Set<String> allowed = new LinkedHashSet<>();
        Set<String> hidden = new LinkedHashSet<>();
        Set<String> revived = new LinkedHashSet<>();
        for (AgentTool tool : versionRow.parseTools()) {
            if (!AgentTool.TYPE_AGENT_TOOLSET.equals(tool.type())) {
                continue;
            }
            allowed.addAll(tool.enabledTools());
            hidden.addAll(tool.disallowedTools());
            for (AgentTool.ToolConfig config : tool.configs()) {
                if (Boolean.FALSE.equals(config.enabled())) {
                    hidden.add(config.name());
                } else if (Boolean.TRUE.equals(config.enabled())) {
                    revived.add(config.name());
                }
            }
        }
        hidden.removeAll(revived);
        return new AgentToolVisibilityDTO(List.copyOf(allowed), List.copyOf(hidden));
    }

    /**
     * 解析版本快照 {@code mcp_servers_json} 为 MCP 服务器装配契约清单
     * （{@code [{name, type:"url", url}]}，顺序与声明一致）。
     * <p>复用领域解析器 {@link AgentVersion#parseMcpServers()}：结构非法抛
     * {@link IllegalStateException}，与版本快照其它配方字段（tools / skills）同一失败语义，
     * <b>不静默降级为「未声明 MCP」</b>。本清单不含任何凭据材料，可安全随装配缓存驻留。</p>
     */
    private List<AgentMcpServerDTO> resolveMcpServers(AgentVersion versionRow) {
        return versionRow.parseMcpServers().stream()
                .map(server -> new AgentMcpServerDTO(server.name(), server.type(), server.url()))
                .toList();
    }

    /**
     * 解析生效模型调优参数：目录引用显式档位优先，目录条目默认档位（default_effort /
     * default_context_window）次之，两者皆无返回空（提供方内置默认）。
     */
    private ModelTuning resolveTuning(ModelRef modelRef) {
        if (modelRef == null) {
            return new ModelTuning(null, null);
        }
        ModelCatalogItem item = modelCatalogService.find(modelRef.id()).orElse(null);
        ModelEffort effort = modelRef.effort() != null ? modelRef.effort()
                : (item == null ? null : item.defaultEffort());
        Integer contextWindow = modelRef.contextWindow() != null ? modelRef.contextWindow()
                : (item == null ? null : item.defaultContextWindow());
        return new ModelTuning(effort == null ? null : effort.value(), contextWindow);
    }

    /**
     * 解析模型凭证明文：解密内嵌密文（凭证内嵌加密单一模式）。
     * 明文仅在本方法内存中持有，不落库、不进响应。
     */
    private String resolveCredential(ModelProfile profile) {
        return credentialEncryptionUtil.decrypt(profile.encryptedCredential());
    }

    /**
     * 解析技能绑定为运行时装配契约：遍历 {@code agent_version.skills_json} 的 {@code {type, skill_id, version}}
     * 绑定，{@code custom} 类型经 {@link SkillAssetApi} 按 skill 资产（skill_id + version）解析正文 + 资源映射，
     * 物化目录名取 {@code skill_id}（不再经 workspace AGENT scope 路径寻址）。
     * <p>{@code custom} 绑定缺失（技能 / 版本 / 磁盘内容任一不存在）→ 显式装配失败（404），不做静默降级
     * （技能是运行时数据面的一部分）；{@code catalog} 类型指向技能目录引用，由目录侧解析其自身目录，
     * 本系统无本地资产来源，暂不参与物化（目录集成落地前跳过，记录告警）。</p>
     */
    private List<ResolvedSkillDTO> resolveSkills(AgentVersion versionRow, Long ownerId) {
        List<ResolvedSkillDTO> skills = new ArrayList<>();
        for (SkillBinding binding : versionRow.parseSkills()) {
            if (!binding.isCustom()) {
                log.warn("技能目录引用暂不由本地资产物化: {}", binding.skillId());
                continue;
            }
            // 钉版（非空 epoch 字符串）固定物化该版本；动态版（version 省略 / "latest"）解析当时最新版本
            SkillContentDTO content = skillAssetApi.findContent(binding.skillId(), binding.version(), ownerId);
            if (content == null) {
                throw new ResourceNotFoundException("技能引用不存在: " + binding.skillId());
            }
            skills.add(new ResolvedSkillDTO(
                    content.directory(),
                    content.name(),
                    content.description(),
                    content.skillContent(),
                    content.resources()
            ));
        }
        return skills;
    }

    @Override
    public void assertResolvable(String agentId, String versionNumber, Long ownerId) {
        validateResolvable(agentId, versionNumber, ownerId);
    }

    @Override
    public String latestVersionNumber(String agentId, Long ownerId) {
        AgentDefinition definition = agentDefinitionRepository.findByAgentId(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent不存在"));
        requireOwner(definition.ownerId(), ownerId, "Agent不存在");
        if (definition.isArchived()) {
            throw new ResourceNotFoundException("Agent已归档，不可创建新会话");
        }
        if (definition.latestVersion() < 1) {
            throw new ResourceNotFoundException("Agent尚未发布版本");
        }
        return String.valueOf(definition.latestVersion());
    }

    @Override
    public String activeVersionNumber(String agentId, Long ownerId) {
        AgentDefinition definition = agentDefinitionRepository.findByAgentId(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent不存在"));
        requireOwner(definition.ownerId(), ownerId, "Agent不存在");
        if (definition.isArchived()) {
            throw new ResourceNotFoundException("Agent已归档，不可创建新会话");
        }
        if (definition.activeVersion() < 1) {
            throw new ResourceNotFoundException("Agent无激活版本");
        }
        return String.valueOf(definition.activeVersion());
    }

    /**
     * 校验装配链路（发布号十进制 / Agent 存在且未归档 / 版本存在 / 内部供应商配置可解析 /
     * agent 与 profile owner 归属），任一失败抛 404；返回校验通过的版本行 + profile +
     * 模型引用供装配复用。不执行凭证解密。
     */
    private VersionAndProfile validateResolvable(String agentId, String versionNumber, Long ownerId) {
        int version = parseVersionNumber(versionNumber);

        AgentDefinition definition = agentDefinitionRepository.findByAgentId(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent不存在"));
        requireOwner(definition.ownerId(), ownerId, "Agent不存在");
        if (definition.isArchived()) {
            throw new ResourceNotFoundException("Agent已归档，不可创建新会话");
        }

        AgentVersion versionRow = agentVersionRepository.findByAgentIdAndVersionNumber(agentId, version)
                .orElseThrow(() -> new ResourceNotFoundException("Agent版本不存在"));

        ModelRef modelRef = versionRow.parseModel();
        ModelProfile profile = resolveInternalProfile(versionRow, modelRef, ownerId);
        requireOwner(profile.ownerId(), ownerId, "模型配置不存在");
        return new VersionAndProfile(versionRow, profile, modelRef);
    }

    /**
     * 解析内部供应商配置：版本快照 {@code model_profile_id}（发布期固化）优先，
     * 缺失时经模型目录供应商映射（{@link ModelCatalogService#resolveProfileId}）按目录模型 id 兜底解析；
     * 两条路径皆无映射视为装配失败（404，不静默切换到其它供应商）。
     */
    private ModelProfile resolveInternalProfile(AgentVersion versionRow, ModelRef modelRef, Long ownerId) {
        String profileId = StringUtils.isNotBlank(versionRow.modelProfileId())
                ? versionRow.modelProfileId()
                : (modelRef == null ? null : modelCatalogService.resolveProfileId(modelRef.id(), ownerId));
        if (StringUtils.isBlank(profileId)) {
            throw new ResourceNotFoundException("该模型未配置内部供应商映射");
        }
        return modelProfileRepository.findByProfileId(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("模型配置不存在"));
    }

    /**
     * owner 归属校验：不匹配统一 404（不区分「不存在」与「越权」，防止枚举）。
     */
    private void requireOwner(Long actualOwnerId, Long ownerId, String message) {
        if (!Objects.equals(actualOwnerId, ownerId)) {
            throw new ResourceNotFoundException(message);
        }
    }

    /** 校验通过的版本行 + 内部模型配置 + 模型引用组合（供装配复用，避免二次解析）。 */
    private record VersionAndProfile(AgentVersion versionRow, ModelProfile profile, ModelRef modelRef) {
    }

    /** 生效模型调优参数（effort 档位字符串 + 上下文窗口，可空 = 提供方内置默认）。 */
    private record ModelTuning(String effort, Integer contextWindow) {
    }

    /**
     * 发布号须为十进制字符串；非十进制视为版本不存在（404），解析失败同样 404。
     */
    private int parseVersionNumber(String versionNumber) {
        if (StringUtils.isBlank(versionNumber) || !versionNumber.matches("\\d+")) {
            throw new ResourceNotFoundException("发布号格式非法");
        }
        try {
            int version = Integer.parseInt(versionNumber);
            if (version < 1) {
                throw new ResourceNotFoundException("发布号格式非法");
            }
            return version;
        } catch (NumberFormatException e) {
            throw new ResourceNotFoundException("发布号格式非法");
        }
    }
}