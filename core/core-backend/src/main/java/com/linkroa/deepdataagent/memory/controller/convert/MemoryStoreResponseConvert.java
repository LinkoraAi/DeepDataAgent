package com.linkroa.deepdataagent.memory.controller.convert;

import com.linkroa.deepdataagent.memory.controller.response.MemoryStoreResponse;
import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 记忆库 → 响应 DTO 转换器
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MemoryStoreResponseConvert {

    MemoryStoreResponseConvert INSTANCE = Mappers.getMapper(MemoryStoreResponseConvert.class);

    MemoryStoreResponse toResponse(MemoryStore store);
}