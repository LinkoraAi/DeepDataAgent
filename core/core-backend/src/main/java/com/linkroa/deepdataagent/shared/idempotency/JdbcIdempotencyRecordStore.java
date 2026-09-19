package com.linkroa.deepdataagent.shared.idempotency;

import com.linkroa.deepdataagent.shared.infrastructure.convert.IdempotencyRecordPersistenceConvert;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.IdempotencyRecordEntity;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.mapper.IdempotencyRecordMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 幂等记录存储实现（idempotency_record 表，MyBatis-Plus）。
 * <p>并发同键写入由唯一索引 {@code uk_idempotency_record_key} 兜底：后到者撞库静默丢弃，
 * 收敛为「先落库者为准」。</p>
 */
@Repository
public class JdbcIdempotencyRecordStore implements IdempotencyRecordStore {

    private final IdempotencyRecordMapper mapper;

    /**
     * 构造器装配唯一的表访问器依赖。
     *
     * @param mapper 幂等记录表 Mapper
     */
    public JdbcIdempotencyRecordStore(IdempotencyRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<IdempotencyRecord> find(Long ownerId, String scope, String idempotencyKey) {
        IdempotencyRecordEntity entity = mapper.selectByKey(ownerId, scope, idempotencyKey);
        return Optional.ofNullable(IdempotencyRecordPersistenceConvert.INSTANCE.toDomain(entity));
    }

    @Override
    public void save(IdempotencyRecord record) {
        IdempotencyRecordEntity entity = IdempotencyRecordPersistenceConvert.INSTANCE.toEntity(record);
        entity.setId(null);
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException ignored) {
            // 并发同键：先落库者为准，后到者静默丢弃（回放时读到的仍是首次记录）
        }
    }
}