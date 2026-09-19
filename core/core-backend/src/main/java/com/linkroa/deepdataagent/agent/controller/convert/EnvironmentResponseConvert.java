package com.linkroa.deepdataagent.agent.controller.convert;

import com.linkroa.deepdataagent.agent.controller.response.EnvironmentConfigResponse;
import com.linkroa.deepdataagent.agent.controller.response.EnvironmentPackagesResponse;
import com.linkroa.deepdataagent.agent.controller.response.EnvironmentResponse;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentConfig;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentPackages;
import org.mapstruct.Mapper;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 运行环境 → 响应 DTO 转换器（config 值对象 → 对外契约形状，packages 判别字段 + 六类全量回显）。
 * <p>标准对象头 {@code id} / {@code type:"environment"} 在转换器内装配（领域模型不持有资源类型词）；
 * 归档仅以 {@code archived_at} 时间戳表达，无 archived 布尔。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface EnvironmentResponseConvert {

    EnvironmentResponseConvert INSTANCE = Mappers.getMapper(EnvironmentResponseConvert.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 环境资源类型词（标准对象头 {@code type}）。 */
    String TYPE_ENVIRONMENT = "environment";

    /**
     * 运行环境 → 响应（标准对象头 id/type；描述缺省空串；归档仅 archived_at）。
     */
    default EnvironmentResponse toResponse(Environment environment) {
        if (environment == null) {
            return null;
        }
        return new EnvironmentResponse(
                environment.environmentId(),
                TYPE_ENVIRONMENT,
                environment.name(),
                environment.description(),
                toConfigResponse(environment.config()),
                toMetadataMap(environment.metadata()),
                environment.archivedAt(),
                environment.createdAt(),
                environment.updatedAt());
    }

    @Named("toConfigResponse")
    default EnvironmentConfigResponse toConfigResponse(EnvironmentConfig config) {
        EnvironmentConfig effective = config == null ? EnvironmentConfig.cloudDefault() : config;
        return new EnvironmentConfigResponse(
                effective.type().value(),
                toPackagesResponse(effective.packages()),
                effective.setupScript());
    }

    /** packages 值对象 → 响应（判别字段恒 {@code "packages"}，未装配的四类恒空数组）。 */
    default EnvironmentPackagesResponse toPackagesResponse(EnvironmentPackages packages) {
        EnvironmentPackages effective = packages == null ? EnvironmentPackages.empty() : packages;
        return new EnvironmentPackagesResponse(
                EnvironmentPackagesResponse.TYPE_PACKAGES,
                effective.apt(), effective.npm(), effective.pip(),
                effective.cargo(), effective.gem(), effective.go());
    }

    /** metadata JSON 文本 → key/value 对象（非法 / 空白收敛为空对象，响应恒为对象形状）。 */
    @Named("toMetadataMap")
    default Map<String, Object> toMetadataMap(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(metadataJson,
                    new TypeReference<Map<String, Object>>() {
                    });
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException e) {
            return Map.of();
        }
    }
}