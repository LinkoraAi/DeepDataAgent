package com.linkroa.deepdataagent.vault.controller.convert;

import com.linkroa.deepdataagent.vault.application.service.VaultApplicationService.CredentialValidationResult;
import com.linkroa.deepdataagent.vault.controller.response.VaultCredentialValidationResponse;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialRefreshStatus;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialValidationStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 凭证校验结果 → 响应 DTO 转换器（契约形状，明文绝不出应用边界）。
 *
 * <p>结论枚举按 {@code getValue()} 落地为契约小写取值；{@code type} 取契约固定标识。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface VaultCredentialValidationResponseConvert {

    VaultCredentialValidationResponseConvert INSTANCE = Mappers.getMapper(VaultCredentialValidationResponseConvert.class);

    @Mapping(target = "type",
            expression = "java(com.linkroa.deepdataagent.vault.controller.response."
                    + "VaultCredentialValidationResponse.TYPE)")
    VaultCredentialValidationResponse toResponse(CredentialValidationResult result);

    /**
     * 校验结论 → 契约取值（{@code valid} / {@code invalid} / {@code unknown}）。
     */
    default String toValue(VaultCredentialValidationStatus status) {
        return status == null ? null : status.getValue();
    }

    /**
     * 刷新结论 → 契约取值（{@code no_refresh_token} / {@code succeeded} / {@code failed} / {@code connect_error}）。
     */
    default String toValue(VaultCredentialRefreshStatus status) {
        return status == null ? null : status.getValue();
    }
}