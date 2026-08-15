package com.linkroa.deepdataagent.runtime.infrastructure.repository;

import com.linkroa.deepdataagent.runtime.domain.model.RunTrace;
import com.linkroa.deepdataagent.runtime.domain.repository.RunTraceRepository;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.RuntimePersistenceMapper;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.RunTraceEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper.RunTraceMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 链路追踪 Span 仓储实现（MyBatis-Plus）。
 * <p>根 span 落库为新记录；事件流推导的 span 经 {@code finish} 派生带主键走 updateById。</p>
 */
@Repository
public class JdbcRunTraceRepository implements RunTraceRepository {

    @Resource
    private RunTraceMapper mapper;
    @Resource
    private RuntimePersistenceMapper persistenceMapper;

    @Override
    public RunTrace save(RunTrace trace) {
        RunTraceEntity entity = persistenceMapper.toEntity(trace);
        if (entity.getId() == null) {
            entity.setId(null);
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return findById(entity.getId()).orElse(trace);
    }

    @Override
    public List<RunTrace> findByRound(String roundId) {
        return mapper.findByRound(roundId)
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }

    private Optional<RunTrace> findById(Long id) {
        return Optional.ofNullable(persistenceMapper.toDomain(mapper.selectById(id)));
    }
}