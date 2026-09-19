package com.linkroa.deepdataagent.agent.infrastructure.convert;

import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentConfig;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentPackages;
import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.EnvironmentEntity;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行环境 ⇄ 持久化实体转换器（MapStruct）。
 * <p>{@link EnvironmentConfig}（值对象）与 JSONB 字符串之间用 {@code @Named} 方法
 * 手工装配 / 解析（持久化键按契约约定：{@code type / packages / setup_script}，
 * packages 六类全量回显），不把领域值对象裸透传到基础设施层；
 * 环境类型权威形状在 {@code config} JSONB 内（无冗余 {@code type} 列），
 * {@code archived_at} 时间戳单列表达归档。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface EnvironmentPersistenceConvert {

    EnvironmentPersistenceConvert INSTANCE = Mappers.getMapper(EnvironmentPersistenceConvert.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mapping(target = "config", source = "config", qualifiedByName = "configToJson")
    EnvironmentEntity toEntity(Environment environment);

    @Mapping(target = "config", source = "config", qualifiedByName = "jsonToConfig")
    Environment toDomain(EnvironmentEntity entity);

    @Named("configToJson")
    default String configToJson(EnvironmentConfig config) {
        EnvironmentConfig effective = config == null ? EnvironmentConfig.cloudDefault() : config;
        Map<String, Object> packages = new LinkedHashMap<>();
        packages.put("apt", effective.packages().apt());
        packages.put("cargo", effective.packages().cargo());
        packages.put("gem", effective.packages().gem());
        packages.put("go", effective.packages().go());
        packages.put("npm", effective.packages().npm());
        packages.put("pip", effective.packages().pip());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("type", effective.type().value());
        values.put("packages", packages);
        values.put("setup_script", effective.setupScript());
        return OBJECT_MAPPER.writeValueAsString(values);
    }

    @Named("jsonToConfig")
    default EnvironmentConfig jsonToConfig(String json) {
        if (StringUtils.isBlank(json)) {
            return EnvironmentConfig.cloudDefault();
        }
        JsonNode root = OBJECT_MAPPER.readTree(json);
        EnvironmentType type = root.hasNonNull("type")
                ? EnvironmentType.fromValue(root.get("type").asString())
                : EnvironmentType.CLOUD;
        EnvironmentPackages packages = parsePackages(root.get("packages"));
        String setupScript = root.hasNonNull("setup_script") ? root.get("setup_script").asString() : null;
        return new EnvironmentConfig(type, packages, setupScript);
    }

    /** 解析 packages 节点（缺失键 / 非数组收敛为空列表，六类全量归一）。 */
    default EnvironmentPackages parsePackages(JsonNode node) {
        return new EnvironmentPackages(
                stringList(node, "apt"),
                stringList(node, "cargo"),
                stringList(node, "gem"),
                stringList(node, "go"),
                stringList(node, "npm"),
                stringList(node, "pip"));
    }

    default List<String> stringList(JsonNode node, String key) {
        JsonNode array = node == null ? null : node.get(key);
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(element -> values.add(element.asString()));
        }
        return values;
    }
}