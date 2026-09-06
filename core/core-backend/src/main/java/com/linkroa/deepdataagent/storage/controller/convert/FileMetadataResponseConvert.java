package com.linkroa.deepdataagent.storage.controller.convert;

import com.linkroa.deepdataagent.storage.controller.response.FileMetadataResponse;
import com.linkroa.deepdataagent.storage.domain.model.FileMetadata;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 文件对象元数据 → 响应 DTO 转换器。
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FileMetadataResponseConvert {

    FileMetadataResponseConvert INSTANCE = Mappers.getMapper(FileMetadataResponseConvert.class);

    /**
     * 将领域元数据转换为响应 DTO。
     *
     * @param metadata 领域元数据（可为 null）
     * @return 响应 DTO（入参为 null 时返回 null）
     */
    FileMetadataResponse toResponse(FileMetadata metadata);
}