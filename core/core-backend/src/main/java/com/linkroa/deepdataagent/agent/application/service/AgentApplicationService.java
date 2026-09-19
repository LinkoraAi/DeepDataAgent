package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.command.CreateAgentCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishAgentVersionCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateAgentCommand;
import com.linkroa.deepdataagent.agent.application.query.ListAgentQuery;
import com.linkroa.deepdataagent.agent.application.validation.AgentValidator;
import com.linkroa.deepdataagent.agent.application.validation.ModelProfileValidator;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentListFilter;
import com.linkroa.deepdataagent.agent.domain.model.AgentTool;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.domain.model.McpServer;
import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.ModelRef;
import com.linkroa.deepdataagent.agent.domain.model.SkillBinding;
import com.linkroa.deepdataagent.agent.domain.repository.AgentDefinitionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.ModelProfileRepository;
import com.linkroa.deepdataagent.agent.domain.service.AgentConfigValidator;
import com.linkroa.deepdataagent.agent.domain.service.AgentVersionDomainService;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import com.linkroa.deepdataagent.skill.api.SkillAssetApi;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Agent 定义与版本应用服务（创建 / 发布 / 归档 / 删除 / 查询）。
 */
@Service
public class AgentApplicationService {

    @Resource
    private AgentDefinitionRepository agentDefinitionRepository;
    @Resource
    private AgentVersionRepository agentVersionRepository;
    @Resource
    private ModelProfileRepository modelProfileRepository;
    @Resource
    private ModelCatalogService modelCatalogService;
    @Resource
    private SkillAssetApi skillAssetApi;
    @Resource
    private AgentVersionDomainService versionDomainService;
    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 创建 Agent：写入 definition + 生成发布号为 1 的初始版本快照，latest=active=1。
     * 整个流程在同一事务内完成，失败不产生任何数据变更；名称不做 owner 内唯一约束。
     */
    public AgentDefinition createAgent(CreateAgentCommand command) {
        Long ownerId = AuthContext.requireUserId();
        // 模型引用校验（目录存在性 + 档位支持）并解析内部供应商映射
        String modelProfileId = resolveModel(command.modelJson(), ownerId);
        // 技能绑定校验 + 钉版解析（custom 校验 skill 资产存在，动态版不落版本键）
        String skillsJson = resolveSkillBindings(command.skillsJson());
        // 工具 / MCP 服务器配置结构校验（类型词汇、互斥名单、引用完整性、数量上限）
        validateConfigStructure(command.toolsJson(), command.mcpServersJson(), skillsJson);
        // multiagent 本期不实现，非空提交 400
        AgentConfigValidator.validateMultiagentAbsent(command.multiagent());

        // 资源 ID 语义前缀（shared/api-conventions：agent_ + 无连分 UUID）
        String agentId = AgentDefinition.AGENT_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
        return transactionTemplate.execute(status -> {
            AgentDefinition definition = AgentDefinition.create(agentId, command.name(), command.description(), ownerId);
            AgentDefinition saved = agentDefinitionRepository.save(definition);
            // 初始版本快照随创建事务落库；发布后同步 latest_version
            AgentVersion v1 = publishVersionInTransaction(saved,
                    command.name(), command.description(),
                    command.systemPrompt(),
                    modelProfileId, command.modelJson(), command.metadataJson(),
                    command.toolsJson(), command.mcpServersJson(),
                    skillsJson,
                    command.multiagent());
            return agentDefinitionRepository.update(saved.withLatestVersion(v1.versionNumber()));
        });
    }

    /**
     * 发布新版本（全量替换）：事务内对 definition 行加锁串行化版本号计算，
     * 新版本号 = MAX(version_number)+1，latest_version 与版本行同源于同一次计算；
     * 版本快照 name/description 为发布时刻定义属性复制（可随同事务更新定义属性）。
     */
    public AgentVersion publishVersion(PublishAgentVersionCommand command) {
        // 模型引用校验（目录存在性 + 档位支持）并解析内部供应商映射
        String modelProfileId = resolveModel(command.modelJson(), AuthContext.requireUserId());
        // 技能绑定校验 + 钉版解析（custom 校验 skill 资产存在，动态版不落版本键）
        String skillsJson = resolveSkillBindings(command.skillsJson());
        // 工具 / MCP 服务器配置结构校验（类型词汇、互斥名单、引用完整性、数量上限）
        validateConfigStructure(command.toolsJson(), command.mcpServersJson(), skillsJson);
        // multiagent 本期不实现，非空提交 400
        AgentConfigValidator.validateMultiagentAbsent(command.multiagent());

        return transactionTemplate.execute(status -> {
            AgentDefinition definition = requireOwnedForUpdate(command.agentId());
            // 归档后拒绝发布新版本
            AgentValidator.validatePublishable(definition);
            String name = StringUtils.trimToNull(command.name()) != null ? command.name() : definition.name();
            String description = command.description() != null ? command.description() : definition.description();
            AgentDefinition updated = definition.withNameAndDescription(name, description);
            AgentVersion version = publishVersionInTransaction(updated,
                    name, description,
                    command.systemPrompt(),
                    modelProfileId, command.modelJson(), command.metadataJson(),
                    command.toolsJson(), command.mcpServersJson(),
                    skillsJson, command.multiagent());
            agentDefinitionRepository.update(updated.withLatestVersion(version.versionNumber()));
            return version;
        });
    }

    /**
     * 事务内发布一个版本快照（共用行锁，保证 MAX+1 计算串行且 latest_version 与版本号一致）。
     * <p>版本快照的 name/description 为发布时刻 Agent 定义属性复制值，不存在独立发布标签入参。</p>
     */
    private AgentVersion publishVersionInTransaction(AgentDefinition definition, String name, String description,
                                                     String systemPrompt,
                                                     String modelProfileId, String modelJson, String metadataJson,
                                                     String toolsJson, String mcpServersJson,
                                                     String skillsJson, String multiagent) {
        int maxVersion = agentVersionRepository.findMaxVersionNumber(definition.agentId());
        int nextVersion = versionDomainService.nextVersionNumber(maxVersion);
        AgentVersion version = AgentVersion.create(
                UUID.randomUUID().toString(),
                definition.agentId(),
                nextVersion,
                name != null ? name : definition.name(),
                description != null ? description : definition.description(),
                systemPrompt,
                modelProfileId,
                modelJson,
                toolsJson,
                mcpServersJson,
                skillsJson,
                multiagent,
                metadataJson
        );
        return agentVersionRepository.save(version);
    }

    /**
     * 按业务 ID 读取 Agent 定义（owner 隔离，不存在或越权一律 404）。
     *
     * @param agentId Agent 业务 ID
     * @return Agent 定义
     * @throws ResourceNotFoundException Agent 不存在或不属于当前用户
     */
    public AgentDefinition getAgent(String agentId) {
        return requireOwned(agentId);
    }

    /**
     * 游标分页查询 Agent 列表（创建时间降序；status/metadata/时间范围过滤，遵循统一 Cursor 约定）。
     * <p>after_id/before_id 游标先解析为行位点（keyset），游标资源不存在 / 非本人 → 404。</p>
     */
    public CursorPage<AgentDefinition> listAgents(ListAgentQuery query) {
        Long ownerId = AuthContext.requireUserId();
        CursorPageParams cursor = query.cursor();
        // 状态词汇 → 归档过滤：active=仅未归档、archived=仅已归档
        Boolean archived = ListAgentQuery.STATUS_ARCHIVED.equals(query.status()) ? Boolean.TRUE : Boolean.FALSE;
        OffsetDateTime cursorCreatedAt = null;
        String cursorAgentId = null;
        boolean reverse = false;
        if (cursor.afterId() != null) {
            AgentDefinition at = requireOwned(cursor.afterId());
            cursorCreatedAt = at.createdAt();
            cursorAgentId = at.agentId();
        } else if (cursor.beforeId() != null) {
            AgentDefinition at = requireOwned(cursor.beforeId());
            cursorCreatedAt = at.createdAt();
            cursorAgentId = at.agentId();
            reverse = true;
        }
        AgentListFilter filter = new AgentListFilter(
                query.keyword(), archived, query.metadataJson(),
                query.createdAtFrom(), query.createdAtTo(),
                cursorCreatedAt, cursorAgentId, reverse);
        // limit+1 探针判 has_more；before 方向升序读取后翻转回降序
        List<AgentDefinition> rows = agentDefinitionRepository.findByCursor(ownerId, filter, cursor.limit() + 1);
        boolean hasMore = rows.size() > cursor.limit();
        List<AgentDefinition> data = rows.subList(0, Math.min(rows.size(), cursor.limit()));
        if (reverse) {
            data = List.copyOf(data).reversed();
        }
        return CursorPage.of(data, hasMore, AgentDefinition::agentId);
    }

    /**
     * 归档 Agent（仅写 archived_at 时间戳；已发布版本仍可被既有 Session 装配使用）。
     * <p>行锁内 check-then-act（与发布 / 更新 / 激活同口径，避免与删除并发时对已逻辑删除行写入）；
     * 幂等：已归档时直接返回，不刷新首次归档时间。</p>
     */
    public void archiveAgent(String agentId) {
        transactionTemplate.executeWithoutResult(status -> {
            AgentDefinition definition = requireOwnedForUpdate(agentId);
            if (definition.isArchived()) {
                return;
            }
            agentDefinitionRepository.updateArchivedAt(
                    agentId, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
        });
    }

    /**
     * 删除 Agent 定义及其全部版本（同一事务内逻辑删除；已发布版本对既有 Session 的装配能力不受影响）。
     *
     * @param agentId Agent 业务 ID
     * @throws ResourceNotFoundException Agent 不存在或不属于当前用户
     */
    public void deleteAgent(String agentId) {
        requireOwned(agentId);
        transactionTemplate.executeWithoutResult(status -> {
            agentDefinitionRepository.deleteByAgentId(agentId);
            agentVersionRepository.deleteByAgentId(agentId);
        });
    }

    /**
     * 游标分页查询版本历史（发布号降序）。游标即「版本号字符串」——与公开契约
     * {@code first_id} / {@code last_id} / {@code next_page} 的口径一致，非正整数游标 → 400。
     *
     * @param agentId Agent 业务 ID
     * @param cursor  游标分页参数（不透明 {@code page} 已在协议层归一为 {@code afterId}）
     * @return 版本快照游标页（有下一页时回传 {@code next_page = last_id}）
     */
    public CursorPage<AgentVersion> listVersionPage(String agentId, CursorPageParams cursor) {
        requireOwned(agentId);
        Integer cursorVersionNumber = null;
        boolean reverse = false;
        if (cursor.afterId() != null) {
            cursorVersionNumber = parseCursorVersionNumber(cursor.afterId());
        } else if (cursor.beforeId() != null) {
            cursorVersionNumber = parseCursorVersionNumber(cursor.beforeId());
            reverse = true;
        }
        List<AgentVersion> rows = agentVersionRepository.findByAgentIdCursor(
                agentId, cursorVersionNumber, reverse, cursor.limit() + 1);
        boolean hasMore = rows.size() > cursor.limit();
        List<AgentVersion> data = rows.subList(0, Math.min(rows.size(), cursor.limit()));
        if (reverse) {
            data = List.copyOf(data).reversed();
        }
        return CursorPage.of(data, hasMore, version -> Integer.toString(version.versionNumber()))
                .withNextCursorFromLastId();
    }

    /** 解析版本游标：公开契约为「正发布号字符串」（非正整数 → 400 {@code invalid_request_error}）。 */
    private static int parseCursorVersionNumber(String cursor) {
        try {
            int versionNumber = Integer.parseInt(cursor.trim());
            if (versionNumber < 1) {
                throw new IllegalArgumentException("版本游标必须为正整数: " + cursor);
            }
            return versionNumber;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("版本游标必须为正整数: " + cursor);
        }
    }

    /**
     * 查询指定版本快照（detail {@code ?version=N}；省略返回最新版本，latest_version&lt;1 时 null；
     * 显式指定但台账缺行 → 404）。
     */
    public AgentVersion getVersion(String agentId, Integer versionNumber) {
        AgentDefinition definition = requireOwned(agentId);
        if (versionNumber == null) {
            if (definition.latestVersion() < 1) {
                return null;
            }
            return agentVersionRepository.findByAgentIdAndVersionNumber(agentId, definition.latestVersion())
                    .orElse(null);
        }
        return agentVersionRepository.findByAgentIdAndVersionNumber(agentId, versionNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Agent版本不存在"));
    }

    /**
     * 装配完整 Agent 对象用的当前生效版本快照：
     * {@link AgentDefinition#activeVersion()} 优先（回滚后即回滚目标版本），未设置时回落
     * {@link AgentDefinition#latestVersion()}；两者皆无（版本台账为空）返回 {@code null}。
     * <p>与 {@link #getVersion(String, Integer)} 的口径差异：后者取「最新发布号」（版本历史查看），
     * 本方法取「当前生效版本」（Agent 对象配置快照）——激活版本被回滚时两者不同。</p>
     *
     * @param definition Agent 定义（须已通过归属校验）
     * @return 当前生效版本快照；无版本台账返回 {@code null}
     */
    public AgentVersion currentVersion(AgentDefinition definition) {
        if (definition == null) {
            return null;
        }
        int target = definition.activeVersion() >= 1 ? definition.activeVersion() : definition.latestVersion();
        if (target < 1) {
            return null;
        }
        return agentVersionRepository.findByAgentIdAndVersionNumber(definition.agentId(), target).orElse(null);
    }

    /**
     * 批量解析多个 Agent 的当前生效版本快照（Agent 列表装配用，单次查询避免逐行回表）。
     *
     * @param definitions Agent 定义列表（须已通过归属校验）
     * @return Agent 业务 ID → 当前生效版本快照；无版本的 Agent 不出现在结果中
     */
    public Map<String, AgentVersion> currentVersions(List<AgentDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> targets = new HashMap<>();
        for (AgentDefinition definition : definitions) {
            int target = definition.activeVersion() >= 1 ? definition.activeVersion() : definition.latestVersion();
            if (target >= 1) {
                targets.put(definition.agentId(), target);
            }
        }
        if (targets.isEmpty()) {
            return Map.of();
        }
        Set<Integer> distinctNumbers = new LinkedHashSet<>(targets.values());
        Map<String, AgentVersion> resolved = new HashMap<>();
        for (AgentVersion version : agentVersionRepository.findByAgentIdsAndVersionNumbers(
                List.copyOf(targets.keySet()), List.copyOf(distinctNumbers))) {
            // Mapper 侧查询为笛卡尔放宽，此处按 (agent_id, version_number) 组合精确取行
            Integer target = targets.get(version.agentId());
            if (target != null && target == version.versionNumber()) {
                resolved.put(version.agentId(), version);
            }
        }
        return resolved;
    }

    /**
     * 更新 Agent 定义属性（名称/描述，OCC 乐观并发）：请求携带 version 与当前 latest_version
     * 不匹配 → 409 conflict_error；归档 Agent 拒绝（409）。更新同步以最新快照全量配置发布
     * 新版本（语义与发布新版本一致）并递增 latest_version。
     */
    public AgentDefinition updateAgent(UpdateAgentCommand command) {
        return transactionTemplate.execute(status -> {
            AgentDefinition definition = requireOwnedForUpdate(command.agentId());
            // 归档后拒绝更新（与发布同口径 409）
            AgentValidator.validatePublishable(definition);
            // OCC：客户端版本必须等于当前最新发布号
            if (command.version() != definition.latestVersion()) {
                throw new ResourceConflictException(
                        "版本不匹配：当前最新版本 " + definition.latestVersion() + "，请求携带 " + command.version() + "，请刷新后重试");
            }
            String name = command.name() != null ? command.name() : definition.name();
            String description = command.description() != null ? command.description() : definition.description();
            AgentVersion latest = agentVersionRepository
                    .findByAgentIdAndVersionNumber(command.agentId(), definition.latestVersion())
                    .orElseThrow(() -> new ResourceNotFoundException("Agent最新版本快照不存在"));
            // 全量替换语义：非改名属性整体复制最新快照，产生 N+1 版本
            AgentVersion snapshot = publishVersionInTransaction(definition,
                    name, description,
                    latest.systemPrompt(),
                    latest.modelProfileId(), latest.modelJson(), latest.metadataJson(),
                    latest.toolsJson(), latest.mcpServersJson(),
                    latest.skillsJson(), latest.multiagent());
            return agentDefinitionRepository.update(
                    definition.withNameAndDescription(name, description).withLatestVersion(snapshot.versionNumber()));
        });
    }

    /**
     * 激活 / 回滚版本：在已发布台账区间内切换 {@code active_version}（{@code latest_version} 不变）。
     * <p>会话创建省略版本号时以激活版本装配；调度器创建省略版本时同样解析并固定激活版本。
     * 新版本发布默认自动激活（{@code active_version} 随 {@code latest_version} 同步）。</p>
     *
     * @param agentId       Agent 业务 ID
     * @param versionNumber 目标激活发布号（{@code 1..latest_version}，版本台账必须存在）
     * @return 激活版本切换后的 Agent 定义
     */
    public AgentDefinition activateVersion(String agentId, int versionNumber) {
        return transactionTemplate.execute(status -> {
            AgentDefinition definition = requireOwnedForUpdate(agentId);
            if (definition.isArchived()) {
                throw new ResourceConflictException("Agent「" + definition.name() + "」已归档，无法激活版本");
            }
            // 领域不变量：激活号必须落在已发布台账区间（越界 → 400）
            definition.withActivatedVersion(versionNumber);
            // 台账存在性：区间内缺行（历史版本被清理）同样拒绝
            agentVersionRepository.findByAgentIdAndVersionNumber(agentId, versionNumber)
                    .orElseThrow(() -> new ResourceNotFoundException("Agent版本不存在"));
            agentDefinitionRepository.updateActiveVersion(agentId, versionNumber);
            return agentDefinitionRepository.findByAgentId(agentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Agent不存在"));
        });
    }

    /**
     * 模型引用解析：解析 {@code model_json}（字符串简写或对象形态）→ 目录校验（不在目录 / 已禁用 /
     * 档位不支持 → 400）→ 内部供应商映射（租户键覆盖默认键，未映射返回 {@code null}，运行时装配
     * 回退默认目录模型 + 可用供应商）。映射命中时内部配置须存在且 ENABLED（管理员误禁 → 404）。
     *
     * @param modelJson 模型引用 JSON（请求经转换器序列化，字符串或对象形态）
     * @param ownerId   租户用户 id（映射解析用）
     * @return 内部 model_profile_id；未配置映射返回 {@code null}
     */
    private String resolveModel(String modelJson, Long ownerId) {
        ModelRef modelRef = ModelRef.parse(modelJson);
        if (modelRef == null) {
            throw new IllegalArgumentException("模型引用不能为空");
        }
        modelCatalogService.validateModel(modelRef);
        String profileId = modelCatalogService.resolveProfileId(modelRef.id(), ownerId);
        if (profileId != null) {
            ModelProfileValidator.validateReferable(modelProfileRepository.findByProfileId(profileId).orElse(null));
        }
        return profileId;
    }

    /**
     * 工具 / MCP 服务器 / 技能配置的跨列表结构校验（解析即校验类型词汇与单条不变量）。
     */
    private void validateConfigStructure(String toolsJson, String mcpServersJson, String skillsJson) {
        AgentConfigValidator.validate(
                AgentTool.parse(toolsJson),
                McpServer.parse(mcpServersJson),
                SkillBinding.parse(skillsJson));
    }

    /**
     * 校验技能绑定并解析钉版，返回可持久化的绑定配方 JSON。
     * <p>{@code custom} 绑定经 {@link SkillAssetApi} 校验 skill 资产存在（不存在 / 非 owner → 404）。
     * {@code version} 为非空 epoch 字符串时校验该版本存在；省略或 {@code "latest"} 为动态版——
     * 发布时<b>不钉版</b>（沙箱准备期才解析当时最新版本），源 Skill 发版无需更新绑定即可生效。
     * 绑定数量超过上限（{@value SkillBinding#MAX_BINDINGS}）拒绝发布。</p>
     *
     * @param skillsJson 请求技能绑定配方（可空）
     * @return 校验后的绑定配方 JSON；无绑定返回 {@code null}
     * @throws ResourceNotFoundException 技能 / 版本引用不存在
     * @throws IllegalArgumentException  绑定结构非法或数量超限
     */
    private String resolveSkillBindings(String skillsJson) {
        List<SkillBinding> bindings = SkillBinding.parse(skillsJson);
        SkillBinding.validateCount(bindings);
        if (bindings.isEmpty()) {
            return null;
        }
        Long ownerId = AuthContext.requireUserId();
        List<SkillBinding> resolved = new ArrayList<>(bindings.size());
        for (SkillBinding binding : bindings) {
            resolved.add(binding.isCustom() ? validateCustomBinding(binding, ownerId) : binding);
        }
        return SkillBinding.toJson(resolved);
    }

    /** 校验 custom 绑定：skill 资产存在；钉版字符串校验对应版本存在（动态版不解析、不落键）。 */
    private SkillBinding validateCustomBinding(SkillBinding binding, Long ownerId) {
        if (!skillAssetApi.exists(binding.skillId(), ownerId)) {
            throw new ResourceNotFoundException("技能引用不存在: " + binding.skillId());
        }
        if (binding.version() != null
                && !skillAssetApi.versionExists(binding.skillId(), binding.version(), ownerId)) {
            throw new ResourceNotFoundException("技能版本不存在: " + binding.skillId() + "@" + binding.version());
        }
        return binding;
    }

    /**
     * 校验 Agent 归属：仅 owner 可见（不含则 404，防止越权枚举）。
     */
    private AgentDefinition requireOwned(String agentId) {
        AgentDefinition definition = agentDefinitionRepository.findByAgentId(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent不存在"));
        if (!definition.ownerId().equals(AuthContext.requireUserId())) {
            throw new ResourceNotFoundException("Agent不存在");
        }
        return definition;
    }

    /**
     * 校验 Agent 归属并锁行（发布 / 删除等 check-then-act 场景）。
     */
    private AgentDefinition requireOwnedForUpdate(String agentId) {
        AgentDefinition definition = agentDefinitionRepository.findByAgentIdForUpdate(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent不存在"));
        if (!definition.ownerId().equals(AuthContext.requireUserId())) {
            throw new ResourceNotFoundException("Agent不存在");
        }
        return definition;
    }
}