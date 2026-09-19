package com.linkroa.deepdataagent.memory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryStoreStatus;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.entity.MemoryStoreEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记忆库 Mapper
 */
@Mapper
public interface MemoryStoreMapper extends BaseMapper<MemoryStoreEntity> {

    default MemoryStoreEntity selectByStoreId(String storeId) {
        return selectOne(Wrappers.<MemoryStoreEntity>lambdaQuery()
                .eq(MemoryStoreEntity::getStoreId, storeId)
                .last("LIMIT 1"));
    }

    default MemoryStoreEntity selectByStoreIdForUpdate(String storeId) {
        return selectOne(Wrappers.<MemoryStoreEntity>lambdaQuery()
                .eq(MemoryStoreEntity::getStoreId, storeId)
                .last("FOR UPDATE"));
    }

    default List<MemoryStoreEntity> selectByIds(Long ownerId, List<String> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<MemoryStoreEntity>lambdaQuery()
                .eq(MemoryStoreEntity::getOwnerId, ownerId)
                .in(MemoryStoreEntity::getStoreId, storeIds));
    }

    /** 分页查询（仅活跃库：列表 MUST NOT 出现 archived）。 */
    default List<MemoryStoreEntity> selectPage(Long ownerId, long offset, int size) {
        return selectList(Wrappers.<MemoryStoreEntity>lambdaQuery()
                .eq(MemoryStoreEntity::getOwnerId, ownerId)
                .eq(MemoryStoreEntity::getStatus, MemoryStoreStatus.ACTIVE.getValue())
                .orderByAsc(MemoryStoreEntity::getCreatedAt)
                .orderByAsc(MemoryStoreEntity::getId)
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    default long countByOwnerId(Long ownerId) {
        return selectCount(Wrappers.<MemoryStoreEntity>lambdaQuery()
                .eq(MemoryStoreEntity::getOwnerId, ownerId)
                .eq(MemoryStoreEntity::getStatus, MemoryStoreStatus.ACTIVE.getValue()));
    }

    /** 归档（仅活跃行可归档，重复归档影响行数 0）。 */
    @Update("UPDATE memory_stores SET status = 'archived', archived_at = #{archivedAt}, updated_at = now() "
            + "WHERE store_id = #{storeId} AND status = 'active' AND is_deleted = 0")
    int archive(@Param("storeId") String storeId, @Param("archivedAt") OffsetDateTime archivedAt);

    /** 随条目内容变更维护统计列（增量式，避免读改写竞态）。 */
    @Update("UPDATE memory_stores SET entry_count = entry_count + #{deltaCount}, "
            + "total_size = total_size + #{deltaSize}, updated_at = now() "
            + "WHERE store_id = #{storeId} AND is_deleted = 0")
    int adjustStats(@Param("storeId") String storeId,
                    @Param("deltaCount") int deltaCount,
                    @Param("deltaSize") long deltaSize);

    /** 物理删除（BaseEntity 配置了 @TableLogic，mapper.delete 实为逻辑删；硬删需显式 DELETE）。 */
    @Delete("DELETE FROM memory_stores WHERE store_id = #{storeId}")
    int deleteByStoreId(@Param("storeId") String storeId);
}
