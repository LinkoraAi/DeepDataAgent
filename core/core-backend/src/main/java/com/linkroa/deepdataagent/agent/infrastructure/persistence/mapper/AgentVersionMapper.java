package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentVersionEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * Agent 版本 Mapper
 * <p>列引用一律使用方法引用（{@code AgentVersionEntity::getX}）：lambda 表达式形式
 * （{@code e -> e.getX()}）编译为合成方法，MyBatis-Plus 运行期解析属性名会抛
 * {@code ReflectionException}，禁止使用。</p>
 */
@Mapper
public interface AgentVersionMapper extends BaseMapper<AgentVersionEntity> {

    default AgentVersionEntity selectByVersionId(String versionId) {
        return selectOne(Wrappers.<AgentVersionEntity>lambdaQuery()
                .eq(AgentVersionEntity::getVersionId, versionId)
                .last("LIMIT 1"));
    }

    default AgentVersionEntity selectByAgentIdAndVersionNumber(String agentId, int versionNumber) {
        return selectOne(Wrappers.<AgentVersionEntity>lambdaQuery()
                .eq(AgentVersionEntity::getAgentId, agentId)
                .eq(AgentVersionEntity::getVersionNumber, versionNumber)
                .last("LIMIT 1"));
    }

    /**
     * 查询某 Agent 的全部版本（按发布号倒序，最新在前）
     */
    default List<AgentVersionEntity> selectByAgentId(String agentId) {
        return selectList(Wrappers.<AgentVersionEntity>lambdaQuery()
                .eq(AgentVersionEntity::getAgentId, agentId)
                .orderByDesc(AgentVersionEntity::getVersionNumber));
    }

    /**
     * 游标分页查询版本历史（发布号 keyset：正向降序取更旧页、before 方向升序取更新页）。
     */
    default List<AgentVersionEntity> selectByAgentIdCursor(String agentId, Integer cursorVersionNumber, boolean reverse, int limit) {
        LambdaQueryWrapper<AgentVersionEntity> query = Wrappers.<AgentVersionEntity>lambdaQuery()
                .eq(AgentVersionEntity::getAgentId, agentId);
        if (cursorVersionNumber != null) {
            if (reverse) {
                query.gt(AgentVersionEntity::getVersionNumber, cursorVersionNumber);
            } else {
                query.lt(AgentVersionEntity::getVersionNumber, cursorVersionNumber);
            }
        }
        if (reverse) {
            query.orderByAsc(AgentVersionEntity::getVersionNumber);
        } else {
            query.orderByDesc(AgentVersionEntity::getVersionNumber);
        }
        return selectList(query.last("LIMIT " + limit));
    }

    /**
     * 批量查询多 Agent 的指定发布号版本行（列表装配「定义 + 当前生效版本」用）。
     * <p>条件为 {@code agent_id IN (...)} 与 {@code version_number IN (...)} 的笛卡尔放宽，
     * 结果集可能大于精确组合数，由仓储实现按 (agent_id, version_number) 组合精确过滤。</p>
     *
     * @param agentIds       Agent 业务 ID 集合
     * @param versionNumbers 发布号集合
     * @return 命中行的版本实体
     */
    default List<AgentVersionEntity> selectByAgentIdsAndVersionNumbers(Collection<String> agentIds,
                                                                      Collection<Integer> versionNumbers) {
        return selectList(Wrappers.<AgentVersionEntity>lambdaQuery()
                .in(AgentVersionEntity::getAgentId, agentIds)
                .in(AgentVersionEntity::getVersionNumber, versionNumbers));
    }

    default AgentVersionEntity selectMaxVersion(String agentId) {
        return selectOne(Wrappers.<AgentVersionEntity>lambdaQuery()
                .eq(AgentVersionEntity::getAgentId, agentId)
                .orderByDesc(AgentVersionEntity::getVersionNumber)
                .last("LIMIT 1"));
    }

    default Long countByModelProfileId(String modelProfileId) {
        return selectCount(Wrappers.<AgentVersionEntity>lambdaQuery()
                .eq(AgentVersionEntity::getModelProfileId, modelProfileId));
    }

    /**
     * 统计 {@code skills_json} 数组中含指定 {@code skill_id} 绑定的未删除版本数（技能删除引用校验）。
     *
     * @param containmentJson JSONB 包含判定串（形如 {@code [{"skill_id":"skill_xxx"}]}）
     * @return 引用数
     */
    default Long countBySkillBinding(String containmentJson) {
        return selectCount(Wrappers.<AgentVersionEntity>lambdaQuery()
                .isNotNull(AgentVersionEntity::getSkillsJson)
                .apply("skills_json @> cast({0} as jsonb)", containmentJson));
    }
}
