package com.linkroa.deepdataagent.file.domain.repository;

import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.model.FileCursor;
import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;

import java.util.List;
import java.util.Optional;

/**
 * 文件元数据仓储接口（依赖倒置，仅承载 files 表元数据）。
 * <p>内容字节存于可配置对象存储（key 为 {@code files/<file_id>}），由应用端口
 * {@code FileContentPort} 的基础设施实现统一编排「对象 + 元数据」双载体；
 * 本仓储只负责元数据持久化，不含任何内容字节存取。</p>
 */
public interface FileRepository {

    /**
     * 保存文件元数据（files 表插入）。
     *
     * @param file 文件聚合（未持久化）
     * @return 持久化后的文件聚合（含主键与时间戳）
     */
    File save(File file);

    /**
     * 按文件业务 ID 查询元数据。
     *
     * @param fileId 文件业务 ID
     * @return 文件聚合；不存在返回空
     */
    Optional<File> findByFileId(String fileId);

    /**
     * 按 owner 隔离的游标过滤查询（{@code (created_at, id)} 升序 keyset 分页）。
     *
     * @param ownerId 归属用户 ID（必填，owner 隔离）
     * @param purpose 用途过滤（可空）
     * @param scopeId 作用域资源 ID 过滤（可空，匹配 scope JSONB 的 id 字段）
     * @param cursor  游标（可空，空为首页）
     * @param limit   本页最大条数
     * @return 满足条件的文件列表（升序，至多 limit 条）
     */
    List<File> findByFilters(Long ownerId, FilePurpose purpose, String scopeId, FileCursor cursor, int limit);

    /**
     * 按作用域资源 ID 查询全部文件（scope JSONB 的 id 字段精确匹配，不区分 owner）。
     * <p>供系统级生命周期清理使用（Session 删除时清理 scope=session 文件），
     * 非分页查询语义，会话关联文件数量受单文件 50MB 与业务产出约束。</p>
     *
     * @param scopeId 作用域资源 ID（如会话 ID，前缀 sess_）
     * @return 该作用域下的文件列表（创建时间升序）
     */
    List<File> findByScope(String scopeId);

    /**
     * 仅逻辑删除文件元数据行（{@code is_deleted=1}）。
     * <p>内容对象的删除由 {@code FileContentPort} 在应用编排中协同执行，本方法不触碰对象存储。</p>
     *
     * @param fileId 文件业务 ID
     * @return 受影响行数
     */
    int deleteByFileId(String fileId);
}
