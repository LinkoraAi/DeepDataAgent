package com.linkroa.deepdataagent.memory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.entity.MemoryEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记忆条目 Mapper（活跃行语义：deleted_at IS NULL）。
 */
@Mapper
public interface MemoryMapper extends BaseMapper<MemoryEntity> {

    default MemoryEntity selectByMemoryId(String memoryId) {
        return selectOne(Wrappers.<MemoryEntity>lambdaQuery()
                .eq(MemoryEntity::getMemoryId, memoryId)
                .isNull(MemoryEntity::getDeletedAt)
                .last("LIMIT 1"));
    }

    default MemoryEntity selectByMemoryIdForUpdate(String memoryId) {
        return selectOne(Wrappers.<MemoryEntity>lambdaQuery()
                .eq(MemoryEntity::getMemoryId, memoryId)
                .isNull(MemoryEntity::getDeletedAt)
                .last("LIMIT 1 FOR UPDATE"));
    }

    default List<MemoryEntity> selectActiveByStoreId(String storeId) {
        return selectList(Wrappers.<MemoryEntity>lambdaQuery()
                .eq(MemoryEntity::getStoreId, storeId)
                .isNull(MemoryEntity::getDeletedAt)
                .orderByAsc(MemoryEntity::getPath));
    }

    /** 插入新条目（并发同 path 首写由活跃唯一索引抛 DuplicateKeyException → 409）。 */
    @Insert("INSERT INTO memories (memory_id, store_id, path, version, size, content_sha256, metadata, created_at, updated_at) "
            + "VALUES (#{memoryId}, #{storeId}, #{path}, #{version}, #{size}, #{contentSha256}, "
            + "#{metadata, typeHandler=com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler}, "
            + "#{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertEntry(MemoryEntity entity);

    /** OCC 原子更新（CAS：期望版本不符或已删除时影响行数 0）。 */
    @Update("UPDATE memories SET version = #{entity.version}, size = #{entity.size}, "
            + "content_sha256 = #{entity.contentSha256}, "
            + "metadata = #{entity.metadata, typeHandler=com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler}, "
            + "updated_at = #{entity.updatedAt} "
            + "WHERE memory_id = #{entity.memoryId} AND version = #{expectedVersion} AND deleted_at IS NULL")
    int updateContentCas(@Param("entity") MemoryEntity entity, @Param("expectedVersion") int expectedVersion);

    /** tombstone 软删（置删除时间，仅活跃行）。 */
    @Update("UPDATE memories SET deleted_at = #{deletedAt}, updated_at = #{deletedAt} "
            + "WHERE memory_id = #{memoryId} AND deleted_at IS NULL")
    int tombstone(@Param("memoryId") String memoryId, @Param("deletedAt") OffsetDateTime deletedAt);

    /** 硬删某记忆库全部条目（删除记忆库级联用）。 */
    @Delete("DELETE FROM memories WHERE store_id = #{storeId}")
    int deleteByStoreId(@Param("storeId") String storeId);
}
