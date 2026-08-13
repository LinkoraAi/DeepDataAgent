package com.linkroa.deepdataagent.agent.application.assembler;

import com.linkroa.deepdataagent.agent.application.dto.ModelConfigDTO;
import com.linkroa.deepdataagent.agent.domain.model.ModelConfig;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ModelConfigDTO 组装器
 * <p>将领域模型 {@link ModelConfig} 转换为应用层 DTO {@link ModelConfigDTO}，
 * 包含 API Key 脱敏、字段映射、时间格式化等逻辑，由控制器层进一步转换为
 * {@link com.linkroa.deepdataagent.agent.controller.response.ModelConfigResponse}。</p>
 */
public final class ModelConfigDTOAssembler {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DEFAULT_API_FORMAT = "openai";

    private ModelConfigDTOAssembler() {
    }

    /**
     * 将领域模型转换为 DTO
     *
     * @param info   领域模型
     * @param doMask 是否脱敏 API Key
     * @return 模型配置 DTO
     */
    public static ModelConfigDTO toDTO(ModelConfig info, boolean doMask) {
        if (info == null) {
            return null;
        }
        String apiKey = info.getApiKey();
        String masked = (apiKey == null || apiKey.isBlank())
                ? ""
                : (doMask ? maskApiKey(apiKey) : apiKey);
        return new ModelConfigDTO(
                info.getId(),
                info.getProviderName(),
                info.getProviderDisplayName(),
                info.getModelId(),
                info.getApiUrl(),
                masked,
                DEFAULT_API_FORMAT,
                info.getDefaultModel() != null && info.getDefaultModel() == 1,
                formatDateTime(info.getCreatedTime()),
                formatDateTime(info.getUpdatedTime())
        );
    }

    /**
     * 脱敏转换（默认脱敏）
     */
    public static ModelConfigDTO toDTO(ModelConfig info) {
        if (info == null) {
            return null;
        }
        return toDTO(info, true);
    }

    /**
     * 批量转换
     */
    public static List<ModelConfigDTO> toDTOList(List<ModelConfig> list) {
        if (list == null) {
            return List.of();
        }
        return list.stream()
                .map(ModelConfigDTOAssembler::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * API Key 脱敏：前 4 位 + **** + 后 4 位
     */
    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return apiKey.substring(0, Math.min(4, apiKey.length())) + "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * 时间格式化
     */
    private static String formatDateTime(LocalDateTime dt) {
        return dt == null ? null : dt.format(DTF);
    }
}