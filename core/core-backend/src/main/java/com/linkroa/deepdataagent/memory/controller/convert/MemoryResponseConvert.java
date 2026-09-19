package com.linkroa.deepdataagent.memory.controller.convert;

import com.linkroa.deepdataagent.memory.controller.response.MemoryDetailResponse;
import com.linkroa.deepdataagent.memory.controller.response.MemoryResponse;
import com.linkroa.deepdataagent.memory.domain.model.Memory;
import com.linkroa.deepdataagent.memory.domain.model.MemoryDetail;
import com.linkroa.deepdataagent.memory.domain.model.MemoryMetadata;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

import java.util.Map;

/**
 * 记忆条目 → 响应 DTO 转换器（列表项不含内容；详情展平条目 + 头版本内容）。
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MemoryResponseConvert {

    MemoryResponseConvert INSTANCE = Mappers.getMapper(MemoryResponseConvert.class);

    MemoryResponse toResponse(Memory entry);

    /** 详情展平：条目元数据 + 头版本内容（已脱敏时 content 为 null，序列化省略） */
    default MemoryDetailResponse toDetailResponse(MemoryDetail detail) {
        if (detail == null) {
            return null;
        }
        Memory entry = detail.entry();
        return new MemoryDetailResponse(entry.memoryId(), entry.storeId(), entry.path(),
                entry.version(), entry.size(), entry.contentSha256(),
                toMetadataMap(entry.metadata()), detail.content(), entry.createdAt(), entry.updatedAt());
    }

    /** 元数据值对象 → JSON 对象出参 */
    default Map<String, String> toMetadataMap(MemoryMetadata metadata) {
        return metadata == null ? Map.of() : metadata.entries();
    }
}
