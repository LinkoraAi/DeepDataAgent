package com.linkroa.deepdataagent.memory.application.convert;

import com.linkroa.deepdataagent.memory.application.command.CreateMemoryStoreCommand;
import com.linkroa.deepdataagent.memory.controller.request.CreateMemoryStoreRequest;
import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryType;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * 记忆库请求转换器（Request → Command）。
 */
@Mapper
public interface MemoryStoreCommandConvert {

    MemoryStoreCommandConvert INSTANCE = Mappers.getMapper(MemoryStoreCommandConvert.class);

    default CreateMemoryStoreCommand toCreateCommand(CreateMemoryStoreRequest request) {
        return new CreateMemoryStoreCommand(request.name(), parseType(request.type()));
    }

    private static MemoryType parseType(String type) {
        if (StringUtils.isBlank(type)) {
            throw new IllegalArgumentException("记忆类型不能为空");
        }
        try {
            return MemoryType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("记忆类型仅支持 SHORT_TERM / LONG_TERM");
        }
    }
}