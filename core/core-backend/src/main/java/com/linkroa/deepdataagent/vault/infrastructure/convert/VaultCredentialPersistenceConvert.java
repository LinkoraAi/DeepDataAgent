package com.linkroa.deepdataagent.vault.infrastructure.convert;

import com.linkroa.deepdataagent.vault.domain.model.VaultCredential;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialAuthType;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.entity.VaultCredentialEntity;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 凭证 ⇄ 持久化实体转换器（MapStruct 静态单例）。
 * <p>密文字节数组（BYTEA）字段同名直接透传，转换层不感知明文；
 * 鉴权类型以领域枚举 ⇄ 小写字域字符串（{@code static_bearer} / {@code mcp_oauth}）
 * 互转，不使用 {@code name()} 默认映射。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface VaultCredentialPersistenceConvert {

    VaultCredentialPersistenceConvert INSTANCE = Mappers.getMapper(VaultCredentialPersistenceConvert.class);

    VaultCredentialEntity toEntity(VaultCredential credential);

    VaultCredential toDomain(VaultCredentialEntity entity);

    /** 鉴权类型枚举 → 小写字域字符串（落库形态）。 */
    default String authTypeToString(VaultCredentialAuthType authType) {
        return authType == null ? null : authType.getValue();
    }

    /** 小写字域字符串 → 鉴权类型枚举（值域外抛 IllegalArgumentException，空值交由领域校验兜底）。 */
    default VaultCredentialAuthType stringToAuthType(String value) {
        return StringUtils.isBlank(value) ? null : VaultCredentialAuthType.fromValue(value);
    }
}