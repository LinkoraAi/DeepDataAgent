package com.linkroa.deepdataagent.rag.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.rag.domain.model.RelationInfoVector;
import com.linkroa.deepdataagent.rag.domain.model.RelationLedgerSnapshot;
import com.linkroa.deepdataagent.rag.domain.repository.RelationInfoVectorRepository;
import com.linkroa.deepdataagent.rag.infrastructure.convert.RagGraphPersistenceConvert;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.RagAuditFieldUtils;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.RelationInfoVectorEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.RelationInfoVectorMapper;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.RelationLedgerOverlapProjection;
import com.linkroa.deepdataagent.shared.util.BatchSplitter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 关系向量仓储的关系型实现（MyBatis-Plus，relation_info_vector 表，与 relation_edge_graph 1:1）。
 * <p>向量身份使用 sorted 双向端点对，检索同时匹配 {@code (a,b)} 与 {@code (b,a)} 两个方向；
 * 写路径统一按字典序方向 {@code ON CONFLICT ... DO UPDATE} 增量合并。</p>
 */
@Repository
public class JdbcRelationInfoVectorRepository implements RelationInfoVectorRepository {

    /** 批量写入分片大小（事务规范 500~1000 条/批） */
    private static final int INSERT_BATCH_SIZE = 500;

    /** 整库物理清退单片删除上限（事务规范批量上限，定 1000/批） */
    private static final int DELETE_BATCH_SIZE = 1000;

    private final RelationInfoVectorMapper mapper;

    public JdbcRelationInfoVectorRepository(RelationInfoVectorMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<RelationInfoVector> findByKbIdAndUnorderedPair(Long kbId, String nameA, String nameB) {
        List<RelationInfoVectorEntity> rows = mapper.selectList(Wrappers.<RelationInfoVectorEntity>lambdaQuery()
                .eq(RelationInfoVectorEntity::getKbId, kbId)
                .and(wrapper -> wrapper
                        .eq(RelationInfoVectorEntity::getSourceName, nameA)
                        .eq(RelationInfoVectorEntity::getTargetName, nameB)
                        .or()
                        .eq(RelationInfoVectorEntity::getSourceName, nameB)
                        .eq(RelationInfoVectorEntity::getTargetName, nameA))
                .last("LIMIT 1"));
        return rows.stream()
                .map(RagGraphPersistenceConvert.INSTANCE::toRelationInfoVector)
                .findFirst();
    }

    @Override
    public Optional<RelationInfoVector> lockByKbIdAndUnorderedPair(Long kbId, String nameA, String nameB) {
        return Optional.ofNullable(RagGraphPersistenceConvert.INSTANCE.toRelationInfoVector(
                mapper.selectForUpdateByKbIdAndUnorderedPair(kbId, nameA, nameB)));
    }

    @Override
    public void upsertAll(List<RelationInfoVector> vectors, String operator) {
        if (ObjectUtils.isEmpty(vectors)) {
            return;
        }
        List<RelationInfoVectorEntity> entities = vectors.stream()
                .map(RagGraphPersistenceConvert.INSTANCE::toEntity)
                .peek(entity -> {
                    entity.setId(null);
                    RagAuditFieldUtils.fillInsert(entity, operator);
                })
                .toList();
        for (List<RelationInfoVectorEntity> batch : BatchSplitter.split(entities, INSERT_BATCH_SIZE)) {
            mapper.upsertBatch(batch);
        }
    }

    @Override
    public int updateContentIfUnchanged(Long kbId, String nameA, String nameB, String expectedContent,
                                        float[] newVector, String guardContent) {
        if (ObjectUtils.isEmpty(kbId) || StringUtils.isBlank(nameA) || StringUtils.isBlank(nameB)) {
            return 0;
        }
        // 向量数组转 PG 向量字面量（null 数组落 NULL，与整行 upsert 的 ::vector 写法同源）
        String vectorLiteral = RagGraphPersistenceConvert.INSTANCE.floatArrayToLiteral(newVector);
        return mapper.updateContentIfUnchanged(kbId, nameA, nameB, expectedContent, vectorLiteral, guardContent);
    }

    @Override
    public int removeChunkContributionsAndPrune(Long kbId, Collection<Long> chunkIds) {
        if (ObjectUtils.isEmpty(kbId) || ObjectUtils.isEmpty(chunkIds)) {
            return 0;
        }
        // 去 null 去重：null 元素会使 SQL 侧 NOT IN 恒非真而误删存活条目，必须先行剔除
        List<Long> targets = chunkIds.stream().filter(Objects::nonNull).distinct().toList();
        if (CollectionUtils.isEmpty(targets)) {
            return 0;
        }
        // 固定语句顺序①图边→②向量行→③账本收缩：①依赖②的向量行做双向关联判定，顺序不可调换
        // kb_id 等值条件把扫描面从全系统收窄到本知识库（不改变命中集）
        mapper.physicalDeletePrunedGraphEdges(kbId, targets);
        int removedEntries = mapper.physicalDeletePrunedVectors(kbId, targets);
        mapper.shrinkChunkContributions(kbId, targets);
        return removedEntries;
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
    public List<RelationLedgerSnapshot> findLedgerSnapshotsWithChunkOverlap(Long kbId, Collection<Long> chunkIds) {
        if (ObjectUtils.isEmpty(kbId) || CollectionUtils.isEmpty(chunkIds)) {
            return List.of();
        }
        // 去 null 去重：null 元素会让 IN 列表参数化失败/毒化谓词，先行剔除；归一后为空零 DB 交互
        List<Long> targets = chunkIds.stream().filter(Objects::nonNull).distinct().toList();
        if (CollectionUtils.isEmpty(targets)) {
            return List.of();
        }
        RagGraphPersistenceConvert convert = RagGraphPersistenceConvert.INSTANCE;
        return mapper.selectLedgerOverlappingChunks(kbId, targets).stream()
                .map(projection -> toSnapshot(kbId, projection, convert))
                .toList();
    }

    @Override
    public int deleteByKbIdAndUnorderedPair(Long kbId, String nameA, String nameB) {
        if (ObjectUtils.isEmpty(kbId) || StringUtils.isBlank(nameA) || StringUtils.isBlank(nameB)) {
            return 0;
        }
        return mapper.physicalDeleteByKbIdAndUnorderedPair(kbId, nameA, nameB);
    }

    /**
     * 投影 → 领域快照（端点对在构造器侧完成字典序归一；账本严格解析、脏 JSON 异常上抛，
     * 图行缺失按空属性承载——口径与实体侧一致）。
     *
     * @param kbId       所属知识库ID
     * @param projection 重叠命中投影
     * @param convert    转换器
     * @return 关系账本快照（归一端点对）
     */
    private static RelationLedgerSnapshot toSnapshot(Long kbId, RelationLedgerOverlapProjection projection,
                                                     RagGraphPersistenceConvert convert) {
        return new RelationLedgerSnapshot(kbId, projection.getSourceName(), projection.getTargetName(),
                convert.jsonToLongList(projection.getChunkIdsRaw()),
                projection.getContent(),
                convert.literalToFloatArray(projection.getContentVector()),
                convert.jsonToRelationProperties(projection.getPropertiesRaw()));
    }
}