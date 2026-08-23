package com.linkroa.deepdataagent.vault.controller.convert;

import com.linkroa.deepdataagent.vault.controller.response.SecretResponse;
import com.linkroa.deepdataagent.vault.domain.model.Secret;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 密钥 → 响应 DTO 转换器（解密细节与密文一律脱敏）
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SecretResponseConvert {

    SecretResponseConvert INSTANCE = Mappers.getMapper(SecretResponseConvert.class);

    @Mapping(target = "maskedValue", constant = "****")
    SecretResponse toResponse(Secret secret);
}