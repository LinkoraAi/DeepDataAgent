package com.linkroa.deepdataagent.file.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.model.FileCursor;
import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;
import com.linkroa.deepdataagent.file.domain.repository.FileRepository;
import com.linkroa.deepdataagent.file.infrastructure.convert.FilePersistenceConvert;
import com.linkroa.deepdataagent.file.infrastructure.persistence.entity.FileEntity;
import com.linkroa.deepdataagent.file.infrastructure.persistence.mapper.FileMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 文件元数据仓储实现（MyBatis-Plus，仅 files 表）。
 * <p>内容字节存于可配置对象存储，「对象 + 元数据」双载体编排（先写对象后入库、
 * 失败补偿、删除同删）由 {@code DefaultFileContentPort} 承担；本仓储只做元数据
 * 插入 / 查询 / 逻辑删除。</p>
 */
@Repository
public class JdbcFileRepository implements FileRepository {

    @Resource
    private FileMapper mapper;

    @Override
    public File save(File file) {
        FileEntity entity = FilePersistenceConvert.INSTANCE.toEntity(file);
        entity.setId(null);
        mapper.insert(entity);
        return findByFileId(file.fileId()).orElse(file);
    }

    @Override
    public Optional<File> findByFileId(String fileId) {
        return Optional.ofNullable(FilePersistenceConvert.INSTANCE.toDomain(mapper.selectByFileId(fileId)));
    }

    @Override
    public List<File> findByFilters(Long ownerId, FilePurpose purpose, String scopeId,
                                    FileCursor cursor, int limit) {
        OffsetDateTime cursorCreatedAt = null;
        Long cursorId = null;
        if (cursor != null) {
            cursorCreatedAt = cursor.createdAt().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
            cursorId = cursor.id();
        }
        return mapper.findByFilters(ownerId, purpose == null ? null : purpose.code(),
                        scopeId, cursorCreatedAt, cursorId, limit).stream()
                .map(FilePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public List<File> findByScope(String scopeId) {
        return mapper.selectByScope(scopeId).stream()
                .map(FilePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public int deleteByFileId(String fileId) {
        // 仅逻辑删除元数据；内容对象删除由 FileContentPort 编排
        return mapper.delete(Wrappers.<FileEntity>lambdaQuery()
                .eq(FileEntity::getFileId, fileId));
    }
}
