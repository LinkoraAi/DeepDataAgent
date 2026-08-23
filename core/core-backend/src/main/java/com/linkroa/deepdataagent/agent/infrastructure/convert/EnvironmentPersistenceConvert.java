package com.linkroa.deepdataagent.agent.infrastructure.convert;

import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.SandboxSpec;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.EnvironmentEntity;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 运行环境 ⇄ 持久化实体转换器（MapStruct）。
 * <p>{@link SandboxSpec}（值对象）与 JSONB 字符串之间用 {@code @Named} 方法转换，
 * 不把领域值对象裸透传到基础设施层。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface EnvironmentPersistenceConvert {

    EnvironmentPersistenceConvert INSTANCE = Mappers.getMapper(EnvironmentPersistenceConvert.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mapping(target = "sandboxSpec", source = "sandboxSpec", qualifiedByName = "specToJson")
    EnvironmentEntity toEntity(Environment environment);

    @Mapping(target = "sandboxSpec", source = "sandboxSpec", qualifiedByName = "jsonToSpec")
    Environment toDomain(EnvironmentEntity entity);

    @Named("specToJson")
    default String specToJson(SandboxSpec spec) {
        if (spec == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(spec);
        } catch (JacksonException e) {
            throw new IllegalStateException("沙箱规格序列化失败", e);
        }
    }

    @Named("jsonToSpec")
    default SandboxSpec jsonToSpec(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, SandboxSpec.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("沙箱规格解析失败", e);
        }
    }
}