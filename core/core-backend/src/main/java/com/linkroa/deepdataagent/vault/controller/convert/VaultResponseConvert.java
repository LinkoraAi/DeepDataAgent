package com.linkroa.deepdataagent.vault.controller.convert;

import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.vault.controller.response.VaultResponse;
import com.linkroa.deepdataagent.vault.controller.response.VaultSearchResponse;
import com.linkroa.deepdataagent.vault.domain.model.Vault;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 保管库 → 响应 DTO 转换器（MapStruct 静态单例，不含任何凭证密文 / 明文）。
 *
 * <p>{@code metadata} 由 JSON 文本对象化为 key/value 对象（非法或空白收敛为空对象），
 * 与搜索端点的入参形态可往返；{@code credentials} 只在创建响应出现且固定为空数组。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface VaultResponseConvert {

    VaultResponseConvert INSTANCE = Mappers.getMapper(VaultResponseConvert.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 保管库 → 响应（列表 / 详情 / 更新场景，<b>不含</b> {@code credentials} 字段）。
     */
    default VaultResponse toResponse(Vault vault) {
        return new VaultResponse(
                vault.vaultId(),
                VaultResponse.TYPE,
                vault.displayName(),
                toMetadataMap(vault.metadata()),
                vault.archivedAt(),
                vault.createdAt(),
                vault.updatedAt(),
                null
        );
    }

    /**
     * 保管库 → 创建响应（{@code credentials} 固定为空数组，见公开契约）。
     */
    default VaultResponse toCreatedResponse(Vault vault) {
        VaultResponse response = toResponse(vault);
        return new VaultResponse(
                response.id(),
                response.type(),
                response.displayName(),
                response.metadata(),
                response.archivedAt(),
                response.createdAt(),
                response.updatedAt(),
                VaultResponse.EMPTY_CREDENTIALS
        );
    }

    /**
     * 保管库搜索结果 → 搜索响应（游标页 + 首页总数）。
     *
     * @param page  游标页（领域模型）
     * @param total 满足筛选条件的总数（仅首页非空）
     */
    default VaultSearchResponse toSearchResponse(CursorPage<Vault> page, Long total) {
        CursorPage<VaultResponse> mapped = page.map(this::toResponse);
        return new VaultSearchResponse(mapped.data(), mapped.firstId(), mapped.lastId(),
                mapped.hasMore(), mapped.nextPage(), total);
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