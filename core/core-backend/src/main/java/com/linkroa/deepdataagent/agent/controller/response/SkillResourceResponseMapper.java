package com.linkroa.deepdataagent.agent.controller.response;

import com.linkroa.deepdataagent.agent.domain.model.SkillResource;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 技能资源 → 响应 DTO 转换器（storageKey 不外露）
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SkillResourceResponseMapper {

    @Mapping(target = "skillType", source = "skillType.code")
    // storageType/status 为无自定义 getter 的枚举，MapStruct 以 name() 自动映射为字符串
    @Mapping(target = "storageType", source = "storageType")
    @Mapping(target = "status", source = "status")
    SkillResourceResponse toResponse(SkillResource skill);
}