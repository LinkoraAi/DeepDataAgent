package com.linkroa.deepdataagent.agent.controller.response;

import java.time.OffsetDateTime;

/**
 * 模型配置响应 DTO（凭证一律脱敏）
 */
public record ModelProfileResponse(
        String profileId,
        String displayName,
        String description,
        String apiFormat,
        String apiEndpointUrl,
        String modelName,
        /** 是否已配置凭证（响应中不返回明文） */
        boolean credentialConfigured,
        String modelSeries,
        Integer contextWindowInput,
        Integer contextWindowOutput,
        Integer toolCallRounds,
        Integer modelType,
        Integer vectorDimension,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}