package com.linkroa.deepdataagent.rag.infrastructure.repository;

import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;
import com.linkroa.deepdataagent.rag.domain.repository.RelationEdgeGraphRepository;
import com.linkroa.deepdataagent.rag.infrastructure.convert.RagGraphPersistenceConvert;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.RagAuditFieldUtils;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.RelationEdgeGraphEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.RelationEdgeGraphMapper;
import com.linkroa.deepdataagent.shared.util.BatchSplitter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 图谱关系边仓储的关系型实现（MyBatis-Plus，relation_edge_graph 表）。
 * <p>本仓储提供两种读取语义：<b>加锁读</b>（{@link #lockByKbIdAndUnorderedPair}，
 * 合并写回的唯一基准，同时锁定两个方向旧行并保证固定输出顺序）与<b>普通读</b>
 * （{@link #findByKbIdAndUnorderedPair}，仅供构造 LLM 上下文等非写回基准场景，
 * 不加锁、可能读到过期账本，不得作为写回过滤基准）。</p>
 * <p>写回按带方向唯一键 {@code ON CONFLICT ... DO UPDATE} 落库；落库方向恒为端点名归一后
 * 的字典序，反向残留行无从产生，故本仓储不再提供按方向删除与按端点探测的能力。</p>
 */
@Repository
public class JdbcRelationEdgeGraphRepository implements RelationEdgeGraphRepository {

    /** 批量写入分片大小（事务规范 500~1000 条/批） */
    private static final int INSERT_BATCH_SIZE = 500;

    /** 整库物理清退单片删除上限（事务规范批量上限，定 1000/批） */
    private static final int DELETE_BATCH_SIZE = 1000;

    private final RelationEdgeGraphMapper mapper;

    public JdbcRelationEdgeGraphRepository(RelationEdgeGraphMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 只读不加锁查询无向端点对的双向边行（仅供构造 LLM 上下文等非写回基准场景）。
     *
     * @param kbId  所属知识库ID
     * @param nameA 端点一
     * @param nameB 端点二
     * @return 领域对象列表（无命中时为空列表），顺序与加锁读一致
     */
    @Override
    public List<RelationEdge> findByKbIdAndUnorderedPair(Long kbId, String nameA, String nameB) {
        return mapper.selectByKbIdAndUnorderedPair(kbId, nameA, nameB).stream()
                .map(RagGraphPersistenceConvert.INSTANCE::toRelationEdge)
                .toList();
    }

    @Override
    public List<RelationEdge> lockByKbIdAndUnorderedPair(Long kbId, String nameA, String nameB) {
        return mapper.selectForUpdateByKbIdAndUnorderedPair(kbId, nameA, nameB).stream()
                .map(RagGraphPersistenceConvert.INSTANCE::toRelationEdge)
                .toList();
    }

    @Override
    public void upsertAll(List<RelationEdge> edges, String operator) {
        if (ObjectUtils.isEmpty(edges)) {
            return;
        }
        List<RelationEdgeGraphEntity> entities = edges.stream()
                .map(RagGraphPersistenceConvert.INSTANCE::toEntity)
                .peek(entity -> RagAuditFieldUtils.fillInsert(entity, operator))
                .toList();
        for (List<RelationEdgeGraphEntity> batch : BatchSplitter.split(entities, INSERT_BATCH_SIZE)) {
            mapper.upsertBatch(batch);
        }
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
    public int deleteByKbIdAndUnorderedPair(Long kbId, String nameA, String nameB) {
        if (ObjectUtils.isEmpty(kbId) || StringUtils.isBlank(nameA) || StringUtils.isBlank(nameB)) {
            return 0;
        }
        return mapper.physicalDeleteByKbIdAndUnorderedPair(kbId, nameA, nameB);
    }
}