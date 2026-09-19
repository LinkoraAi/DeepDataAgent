package com.linkroa.deepdataagent.skill.infrastructure.convert;

import com.linkroa.deepdataagent.skill.domain.model.SkillAsset;
import com.linkroa.deepdataagent.skill.domain.model.enums.SkillSource;
import com.linkroa.deepdataagent.skill.infrastructure.persistence.entity.SkillEntity;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 技能壳 ⇄ 持久化实体转换器（MapStruct 静态单例）。
 * <p>两处形态差异由 default 方法收敛：</p>
 * <ul>
 *   <li>技能来源在领域侧为枚举 {@link SkillSource}、持久化侧为小写字符串列（{@code catalog}/{@code custom}）；</li>
 *   <li>业务元数据在领域侧为 {@code Map<String,Object>}、持久化侧为 JSONB 列（字符串承载），
 *       由本转换器内聚 JSON 序列化 / 反序列化，领域模型不外泄字符串形态。</li>
 * </ul>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SkillPersistenceConvert {

    SkillPersistenceConvert INSTANCE = Mappers.getMapper(SkillPersistenceConvert.class);

    /** JSON 工具（接口字段隐式 {@code public static final}）。 */
    ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 元数据反序列化类型引用。 */
    TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    @Mapping(target = "source", source = "source", qualifiedByName = "fromSkillSource")
    @Mapping(target = "metadata", source = "metadata", qualifiedByName = "fromMetadata")
    SkillEntity toEntity(SkillAsset asset);

    @Mapping(target = "source", source = "source", qualifiedByName = "toSkillSource")
    @Mapping(target = "metadata", source = "metadata", qualifiedByName = "toMetadata")
    SkillAsset toDomain(SkillEntity entity);

    /** 枚举 → 小写字符串列。 */
    @Named("fromSkillSource")
    default String fromSkillSource(SkillSource source) {
        return source == null ? null : source.value();
    }

    /** 小写字符串列 → 枚举（未知值抛非法参数）。 */
    @Named("toSkillSource")
    default SkillSource toSkillSource(String value) {
        return SkillSource.fromValue(value);
    }

    /** 元数据 Map → JSONB 列字符串（空对象序列化为 {@code {}}）。 */
    @Named("fromMetadata")
    default String fromMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata);
        } catch (RuntimeException e) {
            throw new IllegalStateException("技能元数据序列化失败", e);
        }
    }

    /** JSONB 列字符串 → 元数据 Map（空白 / 非法回落空对象）。 */
    @Named("toMetadata")
    default Map<String, Object> toMetadata(String metadata) {
        if (StringUtils.isBlank(metadata)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(metadata, METADATA_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException e) {
            throw new IllegalStateException("技能元数据反序列化失败", e);
        }
    }
}