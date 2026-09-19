package com.linkroa.deepdataagent.agent.controller.convert;

import com.linkroa.deepdataagent.agent.controller.response.ModelProfileResponse;
import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 模型配置 → 响应 DTO 转换器（凭证脱敏）
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ModelProfileResponseConvert {

    ModelProfileResponseConvert INSTANCE = Mappers.getMapper(ModelProfileResponseConvert.class);

    @Mapping(target = "credentialConfigured", source = "profile")
    ModelProfileResponse toResponse(ModelProfile profile);

    /**
     * 判断凭证是否已配置（内嵌密文非空即视为已配置；响应脱敏不返回明文）
     */
    default boolean toCredentialConfigured(ModelProfile profile) {
        return StringUtils.isNotBlank(profile.encryptedCredential());
    }
}