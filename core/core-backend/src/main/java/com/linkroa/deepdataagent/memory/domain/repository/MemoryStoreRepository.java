package com.linkroa.deepdataagent.memory.domain.repository;

import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 记忆库仓储接口
 */
public interface MemoryStoreRepository {

    /** 保存记忆库（新增） */
    MemoryStore save(MemoryStore store);

    /** 按业务ID查询 */
    Optional<MemoryStore> findByStoreId(String storeId);

    /** 按业务ID查询并锁定该行（FOR UPDATE） */
    Optional<MemoryStore> findByStoreIdForUpdate(String storeId);

    /** 批量按业务ID查询（按 owner 隔离，越权的 id 视为不存在） */
    List<MemoryStore> findByIds(Long ownerId, List<String> storeIds);

    /** 分页查询（按 owner 隔离，未归档） */
    List<MemoryStore> findByPage(Long ownerId, int page, int size);

    /** 分页统计（按 owner 隔离，未归档） */
    long countByOwnerId(Long ownerId);

    /** 归档（置 status=archived 与归档时间；仅活跃行生效，重复归档返回 0） */
    int archive(String storeId, OffsetDateTime archivedAt);

    /** 随条目内容变更增量维护统计列（entry_count / total_size） */
    int adjustStats(String storeId, int deltaCount, long deltaSize);

    /** 物理删除（含级联清空 memories / memory_versions，由服务层协调） */
    void deleteByStoreId(String storeId);
}