package com.linkroa.deepdataagent.runtime.infrastructure.repository;

import com.linkroa.deepdataagent.runtime.domain.model.ExecutionRound;
import com.linkroa.deepdataagent.runtime.domain.repository.ExecutionRoundRepository;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.RuntimePersistenceMapper;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.ExecutionRoundEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper.ExecutionRoundMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 执行轮次仓储实现（MyBatis-Plus）。
 * <p>轮次序号基于会话内最大序号分配；终态轮次（complete 派生）带主键走 updateById。</p>
 */
@Repository
public class JdbcExecutionRoundRepository implements ExecutionRoundRepository {

    @Resource
    private ExecutionRoundMapper mapper;
    @Resource
    private RuntimePersistenceMapper persistenceMapper;

    @Override
    public ExecutionRound save(ExecutionRound round) {
        ExecutionRoundEntity entity = persistenceMapper.toEntity(round);
        if (entity.getId() == null) {
            entity.setId(null);
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return findByRoundId(round.roundId()).orElse(round);
    }

    @Override
    public Optional<ExecutionRound> findByRoundId(String roundId) {
        return Optional.ofNullable(persistenceMapper.toDomain(mapper.findByRoundId(roundId)));
    }

    @Override
    public List<ExecutionRound> findBySessionId(String sessionId) {
        return mapper.findBySessionId(sessionId)
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }

    @Override
    public int nextRoundNumber(String sessionId) {
        return mapper.maxRoundNumber(sessionId) + 1;
    }

    @Override
    public int updateRunningToInterrupted(List<String> sessionIds) {
        return mapper.updateRunningToInterrupted(sessionIds);
    }
}