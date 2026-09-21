package com.linkroa.deepdataagent.rag.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.rag.domain.model.EntityNode;
import com.linkroa.deepdataagent.rag.domain.repository.EntityNodeGraphRepository;
import com.linkroa.deepdataagent.rag.infrastructure.convert.RagGraphPersistenceConvert;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.RagAuditFieldUtils;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.EntityNodeGraphEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.EntityNodeGraphMapper;
import com.linkroa.deepdataagent.shared.util.BatchSplitter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 图谱实体节点仓储的关系型实现（MyBatis-Plus，entity_node_graph 表）。
 * <p>写路径统一走 {@code ON CONFLICT (kb_id, entity_name) DO UPDATE} 增量合并；
 * 批量 SQL 不触发 MetaObjectHandler，逐条 {@code RagAuditFieldUtils.fillInsert} 补齐审计字段。</p>
 */
@Repository
public class JdbcEntityNodeGraphRepository implements EntityNodeGraphRepository {

    /** 批量写入分片大小（事务规范 500~1000 条/批） */
    private static final int INSERT_BATCH_SIZE = 500;

    /** 整库物理清退单片删除上限（事务规范批量上限，定 1000/批） */
    private static final int DELETE_BATCH_SIZE = 1000;

    private final EntityNodeGraphMapper mapper;

    public JdbcEntityNodeGraphRepository(EntityNodeGraphMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<EntityNode> findByKbIdAndName(Long kbId, String entityName) {
        EntityNodeGraphEntity entity = mapper.selectOne(Wrappers.<EntityNodeGraphEntity>lambdaQuery()
                .eq(EntityNodeGraphEntity::getKbId, kbId)
                .eq(EntityNodeGraphEntity::getEntityName, entityName));
        return Optional.ofNullable(RagGraphPersistenceConvert.INSTANCE.toEntityNode(entity));
    }

    @Override
    public List<EntityNode> lockByKbIdAndNames(Long kbId, List<String> entityNames) {
        if (ObjectUtils.isEmpty(entityNames)) {
            return List.of();
        }
        return mapper.selectForUpdateByKbIdAndNames(kbId, entityNames).stream()
                .map(RagGraphPersistenceConvert.INSTANCE::toEntityNode)
                .toList();
    }

    @Override
    public void upsert(EntityNode node, String operator) {
        upsertAll(List.of(node), operator);
    }

    @Override
    public void upsertAll(List<EntityNode> nodes, String operator) {
        if (ObjectUtils.isEmpty(nodes)) {
            return;
        }
        List<EntityNodeGraphEntity> entities = nodes.stream()
                .map(RagGraphPersistenceConvert.INSTANCE::toEntity)
                .peek(entity -> RagAuditFieldUtils.fillInsert(entity, operator))
                .toList();
        for (List<EntityNodeGraphEntity> batch : BatchSplitter.split(entities, INSERT_BATCH_SIZE)) {
            mapper.upsertBatch(batch);
        }
    }

    @Override
    public long countByKbId(Long kbId) {
        return mapper.selectCount(Wrappers.<EntityNodeGraphEntity>lambdaQuery()
                .eq(EntityNodeGraphEntity::getKbId, kbId));
    }

    @Override
    public int deleteByKbId(Long kbId) {
        if (ObjectUtils.isEmpty(kbId)) {
            return 0;
        }
        // 主键子查询分片循环：单语句即一片、逐片独立提交，本片不满即清空（幂等可重入）
        int total = 0;
        int deletedInBatch;
        do {
            deletedInBatch = mapper.physicalDeleteByKbIdBatch(kbId, DELETE_BATCH_SIZE);
            total += deletedInBatch;
        } while (deletedInBatch >= DELETE_BATCH_SIZE);
        return total;
    }

    @Override
    public int shrinkDisplaySourceIds(Long kbId, Collection<Long> chunkIds) {
        if (ObjectUtils.isEmpty(kbId) || CollectionUtils.isEmpty(chunkIds)) {
            return 0;
        }
        return mapper.shrinkDisplaySourceIds(kbId, new ArrayList<>(chunkIds));
    }

    @Override
    public int deleteByKbIdAndName(Long kbId, String entityName) {
        if (ObjectUtils.isEmpty(kbId) || StringUtils.isBlank(entityName)) {
            return 0;
        }
        return mapper.physicalDeleteByKbIdAndName(kbId, entityName);
    }
}