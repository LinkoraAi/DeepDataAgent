package com.linkroa.deepdataagent.agent.controller.convert;

import com.linkroa.deepdataagent.agent.controller.response.ModelProfileResponse;
import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;
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

    /**
     * 模型配置 → 响应 DTO。
     * <p>{@code modelType} 经 {@link #toModelTypeCode(ModelType)} 显式映射为业务码，
     * 覆盖 MapStruct 默认的 enum→Integer（ordinal）映射（集成测试 D5）。</p>
     */
    @Mapping(target = "credentialConfigured", source = "profile")
    @Mapping(target = "modelType", expression = "java(toModelTypeCode(profile.modelType()))")
    ModelProfileResponse toResponse(ModelProfile profile);

    /**
     * 判断凭证是否已配置（内嵌密文非空即视为已配置；响应脱敏不返回明文）
     */
    default boolean toCredentialConfigured(ModelProfile profile) {
        return StringUtils.isNotBlank(profile.encryptedCredential());
    }

    /**
     * 模型类型 → 业务码（1=对话 / 2=向量嵌入）。
     * <p>显式映射：MapStruct 对 enum→Integer 默认取 {@code ordinal()}（CHAT=0 / EMBEDDING=1），
     * 与请求契约「1=CHAT 2=EMBEDDING」错位，导致嵌入型配置回显为 1（集成测试 D5）。</p>
     *
     * @param modelType 模型类型枚举（可为空）
     * @return 业务码；入参为空时返回 null
     */
    default Integer toModelTypeCode(ModelType modelType) {
        return modelType == null ? null : modelType.getCode();
    }
}