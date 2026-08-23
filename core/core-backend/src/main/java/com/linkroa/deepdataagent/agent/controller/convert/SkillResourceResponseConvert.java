package com.linkroa.deepdataagent.agent.controller.convert;

import com.linkroa.deepdataagent.agent.controller.response.SkillResourceResponse;
import com.linkroa.deepdataagent.agent.domain.model.SkillResource;
import com.linkroa.deepdataagent.agent.domain.model.SkillResourceManifest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * 技能资源 → 响应 DTO 转换器（storageKey 不外露）
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SkillResourceResponseConvert {

    SkillResourceResponseConvert INSTANCE = Mappers.getMapper(SkillResourceResponseConvert.class);

    @Mapping(target = "skillType", source = "skillType.code")
    // storageType/status 为无自定义 getter 的枚举，MapStruct 以 name() 自动映射为字符串
    @Mapping(target = "storageType", source = "storageType")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "references", source = "resources", qualifiedByName = "toReferences")
    @Mapping(target = "scripts", source = "resources", qualifiedByName = "toScripts")
    SkillResourceResponse toResponse(SkillResource skill);

    @Named("toReferences")
    default List<String> toReferences(SkillResourceManifest resources) {
        return resources == null ? List.of() : resources.references();
    }

    @Named("toScripts")
    default List<String> toScripts(SkillResourceManifest resources) {
        return resources == null ? List.of() : resources.scripts();
    }
}