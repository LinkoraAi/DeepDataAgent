package com.linkroa.deepdataagent.memory.application.convert;

import com.linkroa.deepdataagent.memory.application.command.CreateMemoryEntryCommand;
import com.linkroa.deepdataagent.memory.application.command.CreateMemoryStoreCommand;
import com.linkroa.deepdataagent.memory.application.command.UpdateMemoryEntryCommand;
import com.linkroa.deepdataagent.memory.controller.request.CreateMemoryEntryRequest;
import com.linkroa.deepdataagent.memory.controller.request.CreateMemoryStoreRequest;
import com.linkroa.deepdataagent.memory.controller.request.UpdateMemoryEntryRequest;
import com.linkroa.deepdataagent.memory.domain.model.MemoryMetadata;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

import java.util.Map;

/**
 * 记忆库请求转换器（Request → Command）。
 * <p>元数据 Map → 领域值对象经 {@link #toMetadata(Map)} 转换（null 保持 null，
 * 空值归一与不变量校验由 {@link MemoryMetadata} 紧凑构造器承担）。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MemoryStoreCommandConvert {

    MemoryStoreCommandConvert INSTANCE = Mappers.getMapper(MemoryStoreCommandConvert.class);

    CreateMemoryStoreCommand toCreateCommand(CreateMemoryStoreRequest request);

    @Mapping(target = "storeId", source = "storeId")
    @Mapping(target = "path", source = "request.path")
    @Mapping(target = "content", source = "request.content")
    @Mapping(target = "metadata", source = "request.metadata")
    CreateMemoryEntryCommand toCreateEntryCommand(String storeId, CreateMemoryEntryRequest request);

    @Mapping(target = "storeId", source = "storeId")
    @Mapping(target = "memoryId", source = "memoryId")
    @Mapping(target = "content", source = "request.content")
    @Mapping(target = "expectedVersion", source = "request.version")
    @Mapping(target = "metadata", source = "request.metadata")
    UpdateMemoryEntryCommand toUpdateEntryCommand(String storeId, String memoryId, UpdateMemoryEntryRequest request);

    /** 元数据 Map → 领域值对象（null=不设置，由领域归一为空元数据） */
    default MemoryMetadata toMetadata(Map<String, String> metadata) {
        return metadata == null ? null : MemoryMetadata.of(metadata);
    }
}
