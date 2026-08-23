package com.linkroa.deepdataagent.memory.domain.repository;

import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;

import java.util.List;
import java.util.Optional;

/**
 * 记忆库仓储接口
 */
public interface MemoryStoreRepository {

    /**
     * 保存记忆库（新增）
     */
    MemoryStore save(MemoryStore store);

    /**
     * 按业务ID查询
     */
    Optional<MemoryStore> findByMemoryId(String memoryId);

    /**
     * 按业务ID查询并锁定该行（FOR UPDATE），用于删除等 check-then-act 场景的事务内串行化
     */
    Optional<MemoryStore> findByMemoryIdForUpdate(String memoryId);

    /**
     * 批量按业务ID查询（供引用完整性校验）
     */
    List<MemoryStore> findByIds(List<String> memoryIds);

    /**
     * 查询全部记忆库
     */
    List<MemoryStore> findAll();

    /**
     * 分页查询
     */
    List<MemoryStore> findByPage(int page, int size);

    /**
     * 分页统计
     */
    long countAll();

    /**
     * 逻辑删除
     */
    void deleteByMemoryId(String memoryId);
}