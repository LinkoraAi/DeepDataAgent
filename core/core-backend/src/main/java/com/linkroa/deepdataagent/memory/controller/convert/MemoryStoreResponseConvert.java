package com.linkroa.deepdataagent.memory.controller.convert;

import com.linkroa.deepdataagent.memory.controller.response.MemoryStoreResponse;
import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryStoreStatus;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 记忆库 → 响应 DTO 转换器（字段同名自动映射，状态枚举转小写取值）。
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MemoryStoreResponseConvert {

    MemoryStoreResponseConvert INSTANCE = Mappers.getMapper(MemoryStoreResponseConvert.class);

    MemoryStoreResponse toResponse(MemoryStore store);

    /** 状态枚举 → 小写取值（active/archived） */
    default String toStatusValue(MemoryStoreStatus status) {
        return status == null ? null : status.getValue();
    }
}
