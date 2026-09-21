package com.linkroa.deepdataagent.rag.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.rag.domain.model.EntityInfoVector;
import com.linkroa.deepdataagent.rag.domain.model.EntityLedgerSnapshot;
import com.linkroa.deepdataagent.rag.domain.repository.EntityInfoVectorRepository;
import com.linkroa.deepdataagent.rag.infrastructure.convert.RagGraphPersistenceConvert;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.RagAuditFieldUtils;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.EntityInfoVectorEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.mapper.EntityInfoVectorMapper;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.projection.EntityLedgerOverlapProjection;
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
 * 实体向量仓储的关系型实现（MyBatis-Plus，entity_info_vector 表，与 entity_node_graph 1:1）。
 * <p>写路径统一走 {@code ON CONFLICT (kb_id, entity_name) DO UPDATE} 增量合并；
 * 向量字面量经 SQL 侧 {@code ::vector} 转换落库，chunk_ids 以 JSON 数组文本承载。</p>
 */
@Repository
public class JdbcEntityInfoVectorRepository implements EntityInfoVectorRepository {

    /** 批量写入分片大小（事务规范 500~1000 条/批） */
    private static final int INSERT_BATCH_SIZE = 500;

    /** 整库物理清退单片删除上限（事务规范批量上限，定 1000/批） */
    private static final int DELETE_BATCH_SIZE = 1000;

    private final EntityInfoVectorMapper mapper;

    public JdbcEntityInfoVectorRepository(EntityInfoVectorMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<EntityInfoVector> findByKbIdAndName(Long kbId, String entityName) {
        EntityInfoVectorEntity entity = mapper.selectOne(Wrappers.<EntityInfoVectorEntity>lambdaQuery()
                .eq(EntityInfoVectorEntity::getKbId, kbId)
                .eq(EntityInfoVectorEntity::getEntityName, entityName));
        return Optional.ofNullable(RagGraphPersistenceConvert.INSTANCE.toEntityInfoVector(entity));
    }

    @Override
    public Optional<EntityInfoVector> lockByKbIdAndName(Long kbId, String entityName) {
        return Optional.ofNullable(
                RagGraphPersistenceConvert.INSTANCE.toEntityInfoVector(mapper.selectForUpdateByKbIdAndName(kbId, entityName)));
    }

    @Override
    public void upsert(EntityInfoVector vector, String operator) {
        upsertAll(List.of(vector), operator);
    }

    @Override
    public int updateContentIfUnchanged(Long kbId, String entityName, String expectedContent,
                                        float[] newVector, String guardContent) {
        if (ObjectUtils.isEmpty(kbId) || StringUtils.isBlank(entityName)) {
            return 0;
        }
        // 向量数组转 PG 向量字面量（null 数组落 NULL，与整行 upsert 的 ::vector 写法同源）
        String vectorLiteral = RagGraphPersistenceConvert.INSTANCE.floatArrayToLiteral(newVector);
        return mapper.updateContentIfUnchanged(kbId, entityName, expectedContent, vectorLiteral, guardContent);
    }

    @Override
    public void upsertAll(List<EntityInfoVector> vectors, String operator) {
        if (ObjectUtils.isEmpty(vectors)) {
            return;
        }
        List<EntityInfoVectorEntity> entities = vectors.stream()
                .map(RagGraphPersistenceConvert.INSTANCE::toEntity)
                .peek(entity -> {
                    entity.setId(null);
                    RagAuditFieldUtils.fillInsert(entity, operator);
                })
                .toList();
        for (List<EntityInfoVectorEntity> batch : BatchSplitter.split(entities, INSERT_BATCH_SIZE)) {
            mapper.upsertBatch(batch);
        }
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
        // 固定语句顺序①图行→②向量行→③账本收缩：①依赖②的向量行做关联判定，顺序不可调换
        // kb_id 等值条件把扫描面从全系统收窄到本知识库（不改变命中集）
        mapper.physicalDeletePrunedGraphNodes(kbId, targets);
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
    public List<EntityLedgerSnapshot> findLedgerSnapshotsWithChunkOverlap(Long kbId, Collection<Long> chunkIds) {
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
    public int deleteByKbIdAndName(Long kbId, String entityName) {
        if (ObjectUtils.isEmpty(kbId) || StringUtils.isBlank(entityName)) {
            return 0;
        }
        return mapper.physicalDeleteByKbIdAndName(kbId, entityName);
    }

    /**
     * 投影 → 领域快照（账本严格解析：摄入读路径要求账本结构完整，脏 JSON 异常上抛暴露；
     * 图行缺失（收敛中间态）按空属性承载，由重建服务按降级/兜底语义处置）。
     *
     * @param kbId        所属知识库ID
     * @param projection  重叠命中投影
     * @param convert     转换器
     * @return 实体账本快照
     */
    private static EntityLedgerSnapshot toSnapshot(Long kbId, EntityLedgerOverlapProjection projection,
                                                   RagGraphPersistenceConvert convert) {
        return new EntityLedgerSnapshot(kbId, projection.getEntityName(),
                convert.jsonToLongList(projection.getChunkIdsRaw()),
                projection.getContent(),
                convert.literalToFloatArray(projection.getContentVector()),
                convert.jsonToEntityProperties(projection.getPropertiesRaw()));
    }
}