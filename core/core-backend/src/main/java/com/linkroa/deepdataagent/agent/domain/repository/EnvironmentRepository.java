package com.linkroa.deepdataagent.agent.domain.repository;

import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentListFilter;

import java.util.List;
import java.util.Optional;

/**
 * 运行环境仓储接口
 */
public interface EnvironmentRepository {

    /**
     * 保存运行环境（新增）
     */
    Environment save(Environment environment);

    /**
     * 更新运行环境（全量替换）
     */
    Environment update(Environment environment);

    /**
     * 按业务ID查询
     */
    Optional<Environment> findByEnvironmentId(String environmentId);

    /**
     * 按名称查询（owner 隔离下名称唯一）
     */
    Optional<Environment> findByNameAndOwnerId(String name, Long ownerId);

    /**
     * 按业务ID查询并锁定该行（FOR UPDATE），用于删除等 check-then-act 场景的事务内串行化
     */
    Optional<Environment> findByEnvironmentIdForUpdate(String environmentId);

    /**
     * 批量按业务ID查询（供引用完整性校验）
     */
    List<Environment> findByIds(List<String> environmentIds);

    /**
     * 游标分页查询（owner 隔离，创建时间降序 keyset，metadata 包含 / 创建时间区间过滤）
     */
    List<Environment> findByCursor(Long ownerId, EnvironmentListFilter filter, int limit);

    /**
     * 逻辑删除
     */
    void deleteByEnvironmentId(String environmentId);
}