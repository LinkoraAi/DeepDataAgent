package com.linkroa.deepdataagent.memory.controller.convert;

import com.linkroa.deepdataagent.memory.controller.response.MemoryVersionResponse;
import com.linkroa.deepdataagent.memory.domain.model.MemoryVersion;
import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryVersionAction;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 记忆版本 → 响应 DTO 转换器（字段同名自动映射，动作枚举转小写取值）。
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MemoryVersionResponseConvert {

    MemoryVersionResponseConvert INSTANCE = Mappers.getMapper(MemoryVersionResponseConvert.class);

    MemoryVersionResponse toResponse(MemoryVersion version);

    /** 动作枚举 → 小写取值（created/updated/deleted） */
    default String toActionValue(MemoryVersionAction action) {
        return action == null ? null : action.getValue();
    }
}
