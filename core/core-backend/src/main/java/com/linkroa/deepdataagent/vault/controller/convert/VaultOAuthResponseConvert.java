package com.linkroa.deepdataagent.vault.controller.convert;

import com.linkroa.deepdataagent.vault.application.dto.OAuthStartResultDTO;
import com.linkroa.deepdataagent.vault.controller.response.OAuthStartResponse;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 发起授权结果 → 响应 DTO 转换器（契约形状，凭证材料绝不出应用边界）。
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface VaultOAuthResponseConvert {

    VaultOAuthResponseConvert INSTANCE = Mappers.getMapper(VaultOAuthResponseConvert.class);

    OAuthStartResponse toResponse(OAuthStartResultDTO result);
}