package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.domain.model.AgentListFilter;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentDefinitionEntity;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Agent 定义 Mapper
 *
 * <p>约束：LambdaQueryWrapper / LambdaUpdateWrapper 的列引用一律使用方法引用
 * （{@code AgentDefinitionEntity::getX}），不得写成 lambda 表达式（{@code e -> e.getX()}）——
 * 后者编译为合成方法 {@code lambda$N}，MyBatis-Plus 的 PropertyNamer 无法解析属性名，
 * 真实库运行期抛 ReflectionException。</p>
 */
@Mapper
public interface AgentDefinitionMapper extends BaseMapper<AgentDefinitionEntity> {

    default AgentDefinitionEntity selectByAgentId(String agentId) {
        return selectOne(Wrappers.<AgentDefinitionEntity>lambdaQuery()
                .eq(AgentDefinitionEntity::getAgentId, agentId)
                .last("LIMIT 1"));
    }

    /**
     * 行锁查询（发布事务内串行化同一 Agent 的版本号计算，FOR UPDATE）
     */
    default AgentDefinitionEntity selectByAgentIdForUpdate(String agentId) {
        return selectOne(Wrappers.<AgentDefinitionEntity>lambdaQuery()
                .eq(AgentDefinitionEntity::getAgentId, agentId)
                .last("FOR UPDATE"));
    }

    /**
     * 游标分页查询（keyset：行值比较 {@code (created_at, agent_id)} 定位游标；
     * 正向降序取更旧页、before 方向升序取更新页后由应用层翻转）。
     * <p>元数据过滤按激活版本快照的 {@code metadata_json} JSONB 包含匹配（EXISTS 子查询，
     * 参数经 {@code {0}} 占位符绑定）。</p>
     */
    default List<AgentDefinitionEntity> selectByCursor(Long ownerId, AgentListFilter filter, int limit) {
        LambdaQueryWrapper<AgentDefinitionEntity> query = Wrappers.<AgentDefinitionEntity>lambdaQuery()
                .eq(AgentDefinitionEntity::getOwnerId, ownerId)
                .like(StringUtils.isNotBlank(filter.keyword()), AgentDefinitionEntity::getName, filter.keyword())
                // 归档过滤以 archived_at 时间戳表达（active=仅未归档 / archived=仅已归档）
                .isNull(Boolean.FALSE.equals(filter.archived()), AgentDefinitionEntity::getArchivedAt)
                .isNotNull(Boolean.TRUE.equals(filter.archived()), AgentDefinitionEntity::getArchivedAt)
                .ge(filter.createdFrom() != null, AgentDefinitionEntity::getCreatedAt, filter.createdFrom())
                .le(filter.createdTo() != null, AgentDefinitionEntity::getCreatedAt, filter.createdTo())
                .apply(StringUtils.isNotBlank(filter.metadataJson()),
                        "EXISTS (SELECT 1 FROM agent_version av WHERE av.agent_id = agent_definition.agent_id"
                                + " AND av.version_number = agent_definition.active_version AND av.is_deleted = 0"
                                + " AND av.metadata_json @> cast({0} as jsonb))",
                        filter.metadataJson());
        if (filter.cursorCreatedAt() != null) {
            if (filter.reverse()) {
                query.apply("(created_at, agent_id) > ({0}, {1})", filter.cursorCreatedAt(), filter.cursorAgentId());
            } else {
                query.apply("(created_at, agent_id) < ({0}, {1})", filter.cursorCreatedAt(), filter.cursorAgentId());
            }
        }
        if (filter.reverse()) {
            query.orderByAsc(AgentDefinitionEntity::getCreatedAt).orderByAsc(AgentDefinitionEntity::getAgentId);
        } else {
            query.orderByDesc(AgentDefinitionEntity::getCreatedAt).orderByDesc(AgentDefinitionEntity::getAgentId);
        }
        return selectList(query.last("LIMIT " + limit));
    }

    default int updateActiveVersion(String agentId, int versionNumber) {
        return update(null, Wrappers.<AgentDefinitionEntity>lambdaUpdate()
                .set(AgentDefinitionEntity::getActiveVersion, versionNumber)
                .set(AgentDefinitionEntity::getUpdatedAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(AgentDefinitionEntity::getAgentId, agentId));
    }

    /**
     * 设置归档时间（{@code null} = 取消归档，但公开契约不提供取消归档动作）。
     */
    default int updateArchivedAt(String agentId, OffsetDateTime archivedAt) {
        return update(null, Wrappers.<AgentDefinitionEntity>lambdaUpdate()
                .set(AgentDefinitionEntity::getArchivedAt, archivedAt)
                .set(AgentDefinitionEntity::getUpdatedAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(AgentDefinitionEntity::getAgentId, agentId));
    }
}