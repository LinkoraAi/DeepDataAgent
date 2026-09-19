package com.linkroa.deepdataagent.file.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.file.infrastructure.persistence.entity.FileEntity;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 文件 Mapper（数据库访问器）。
 * <p>此处以 {@code @SuppressWarnings("null")} 压制 JDT 空指针静态分析对 MyBatis-Plus
 * {@code SFunction} 方法引用（{@code Entity::getXxx}）的误报：该写法是 MP lambda 包装器
 * 解析列名的标准形式，运行时与 null 语义无关。</p>
 */
@SuppressWarnings("null")
@Mapper
public interface FileMapper extends BaseMapper<FileEntity> {

    default FileEntity selectByFileId(String fileId) {
        return selectOne(Wrappers.<FileEntity>lambdaQuery()
                .eq(FileEntity::getFileId, fileId)
                .last("LIMIT 1"));
    }

    /**
     * 按作用域资源 ID 查询全部文件（scope JSONB 的 id 键精确匹配，不区分 owner，
     * 供 Session 生命周期系统级清理；创建时间升序）。
     *
     * @param scopeId 作用域资源 ID（如会话 ID，前缀 sess_）
     * @return 满足条件的实体列表（升序）
     */
    default List<FileEntity> selectByScope(String scopeId) {
        return selectList(Wrappers.<FileEntity>lambdaQuery()
                .apply("scope->>'id' = {0}", scopeId)
                .orderByAsc(FileEntity::getCreatedAt)
                .orderByAsc(FileEntity::getId));
    }

    /**
     * owner 隔离的游标过滤查询（{@code (created_at, id)} 升序 keyset 分页，对齐会话列表范式）。
     *
     * @param ownerId         归属用户 ID（必填）
     * @param purposeCode     用途契约值过滤（可空）
     * @param scopeId         作用域资源 ID 过滤（可空，匹配 scope JSONB 的 id 键）
     * @param cursorCreatedAt 游标创建时间（与 cursorId 成对出现，可空=首页）
     * @param cursorId        游标主键
     * @param limit           本页最大条数
     * @return 满足条件的实体列表（升序）
     */
    default List<FileEntity> findByFilters(Long ownerId, String purposeCode, String scopeId,
                                           OffsetDateTime cursorCreatedAt, Long cursorId, int limit) {
        LambdaQueryWrapper<FileEntity> wrapper = Wrappers.<FileEntity>lambdaQuery()
                .eq(FileEntity::getOwnerId, ownerId);
        if (purposeCode != null && !purposeCode.isBlank()) {
            wrapper.eq(FileEntity::getPurpose, purposeCode);
        }
        if (scopeId != null && !scopeId.isBlank()) {
            wrapper.apply("scope->>'id' = {0}", scopeId);
        }
        if (cursorCreatedAt != null && cursorId != null) {
            wrapper.and(w -> w
                    .gt(FileEntity::getCreatedAt, cursorCreatedAt)
                    .or(o -> o.eq(FileEntity::getCreatedAt, cursorCreatedAt)
                            .gt(FileEntity::getId, cursorId)));
        }
        return selectList(wrapper
                .orderByAsc(FileEntity::getCreatedAt)
                .orderByAsc(FileEntity::getId)
                .last("LIMIT " + limit));
    }
}
