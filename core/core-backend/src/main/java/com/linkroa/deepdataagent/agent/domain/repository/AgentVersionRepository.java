package com.linkroa.deepdataagent.agent.domain.repository;

import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Agent 版本仓储接口
 */
public interface AgentVersionRepository {

    /**
     * 保存（新增发布版本）
     */
    AgentVersion save(AgentVersion version);

    /**
     * 按版本业务ID查询
     */
    Optional<AgentVersion> findByVersionId(String versionId);

    /**
     * 按 Agent + 发布号查询
     */
    Optional<AgentVersion> findByAgentIdAndVersionNumber(String agentId, int versionNumber);

    /**
     * 查询某 Agent 的版本列表（按发布号倒序，最新在前）
     */
    List<AgentVersion> listByAgentId(String agentId);

    /**
     * 游标分页查询某 Agent 的版本历史（发布号降序 keyset；{@code limit} 由调用方 +1 探针放大，
     * {@code reverse}=true（before_id 方向）升序读取更新侧后由应用层翻转回降序）。
     *
     * @param agentId             Agent 业务 ID
     * @param cursorVersionNumber 游标行发布号（null = 首页）
     * @param reverse             是否反向读取（before_id 方向）
     * @param limit               读取行数（含探针行）
     * @return 版本快照列表（正向降序 / 反向升序）
     */
    List<AgentVersion> findByAgentIdCursor(String agentId, Integer cursorVersionNumber, boolean reverse, int limit);

    /**
     * 批量查询多 Agent 的指定发布号版本快照（列表装配「定义 + 当前生效版本」用，避免逐行回表）。
     * <p>查询条件为 {@code agent_id IN (...)} 且 {@code version_number IN (...)} 的<b>笛卡尔放宽</b>，
     * 结果集会大于精确组合数，<b>调用方 MUST 自行按 (agent_id, version_number) 组合精确取行</b>。</p>
     *
     * @param agentIds       Agent 业务 ID 集合（空集合直接返回空列表）
     * @param versionNumbers 发布号集合（空集合直接返回空列表）
     * @return 命中行的版本快照（非精确组合集，调用方按组合过滤）
     */
    List<AgentVersion> findByAgentIdsAndVersionNumbers(Collection<String> agentIds, Collection<Integer> versionNumbers);

    /**
     * 查询某 Agent 的当前最大发布号（无版本时为 0）
     */
    int findMaxVersionNumber(String agentId);

    /**
     * 统计仍引用指定模型配置的未删除版本数（删除冲突校验）
     */
    long countByModelProfileId(String modelProfileId);

    /**
     * 统计 {@code skills_json} 中绑定指定技能（{@code skill_id}）的未删除版本数（技能删除引用校验）。
     *
     * @param skillId 技能业务 ID
     * @return 引用数（0 表示无绑定，可安全删除）
     */
    long countSkillBindings(String skillId);

    /**
     * 统计 {@code skills_json} 中绑定指定技能**特定版本**（{@code {skill_id, version}}）的未删除版本数
     * （6.4 delete-version 引用校验；custom 绑定保存时已固定版本号，缺省版本绑定不参与）。
     *
     * @param skillId 技能业务 ID
     * @param version 技能版本键（创建时刻 epoch 微秒字符串）
     * @return 引用数（0 表示该版本无绑定，可安全删除）
     */
    long countSkillVersionBindings(String skillId, String version);

    /**
     * 逻辑删除某 Agent 的全部版本
     */
    void deleteByAgentId(String agentId);
}