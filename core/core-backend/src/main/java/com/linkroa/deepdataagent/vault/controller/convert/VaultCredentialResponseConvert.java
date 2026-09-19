package com.linkroa.deepdataagent.vault.controller.convert;

import com.linkroa.deepdataagent.vault.application.dto.VaultCredentialViewDTO;
import com.linkroa.deepdataagent.vault.controller.response.VaultCredentialResponse;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 凭证脱敏视图 → 响应 DTO 转换器（MapStruct 静态单例，响应永不出现密文 / 明文）。
 *
 * <p>入口只接受应用层装配的 {@link VaultCredentialViewDTO}（密文成分已在装配时丢弃），
 * 协议层不接触 {@code VaultCredential.ciphertext()}；{@code display_name} 当前固定为
 * {@code null}（不持久化），保留在形状中以稳定前端取字段路径。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface VaultCredentialResponseConvert {

    VaultCredentialResponseConvert INSTANCE = Mappers.getMapper(VaultCredentialResponseConvert.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 凭证脱敏视图 → 响应。
     */
    default VaultCredentialResponse toResponse(VaultCredentialViewDTO view) {
        return new VaultCredentialResponse(
                view.credentialId(),
                VaultCredentialResponse.TYPE,
                view.vaultId(),
                new VaultCredentialResponse.Auth(
                        view.authType(),
                        view.mcpServerUrl(),
                        view.expiresAt(),
                        toRefresh(view.refresh())),
                null,
                toMetadataMap(view.metadata()),
                view.archivedAt(),
                view.createdAt(),
                view.updatedAt()
        );
    }

    /** 脱敏刷新配置 → 响应子对象（无刷新配置时整键省略）。 */
    default VaultCredentialResponse.Refresh toRefresh(VaultCredentialViewDTO.Refresh refresh) {
        if (refresh == null) {
            return null;
        }
        return new VaultCredentialResponse.Refresh(
                refresh.clientId(),
                refresh.tokenEndpoint(),
                refresh.resource(),
                refresh.scope(),
                toTokenEndpointAuth(refresh.tokenEndpointAuthType())
        );
    }

    /** 令牌端点鉴权方式 → 响应子对象（只下发放方式本身，密钥不返回）。 */
    default VaultCredentialResponse.TokenEndpointAuth toTokenEndpointAuth(String type) {
        return type == null ? null : new VaultCredentialResponse.TokenEndpointAuth(type);
    }

    /** metadata JSON 文本 → key/value 对象（空白 / 非法 / 非对象收敛为空对象，读侧不因脏值失败）。 */
    default Map<String, Object> toMetadataMap(String metadata) {
        if (StringUtils.isBlank(metadata)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(metadata, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }
}