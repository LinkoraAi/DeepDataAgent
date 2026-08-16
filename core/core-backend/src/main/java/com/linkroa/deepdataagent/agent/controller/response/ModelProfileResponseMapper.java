package com.linkroa.deepdataagent.agent.controller.response;

import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * 模型配置 → 响应 DTO 转换器（凭证脱敏）
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ModelProfileResponseMapper {

    @Mapping(target = "credentialConfigured", source = "encryptedCredential")
    ModelProfileResponse toResponse(ModelProfile profile);

    /**
     * 判断凭证是否已配置（响应脱敏：不返回明文）
     */
    default boolean toCredentialConfigured(String encryptedCredential) {
        return StringUtils.isNotBlank(encryptedCredential);
    }
}