package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.ApiFormat;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelProfileStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * 模型配置领域模型（对应 model_profile 表）
 *
 * @param profileId           业务唯一ID
 * @param displayName         显示名称（≤32字符）
 * @param description         描述
 * @param apiFormat           API格式
 * @param apiEndpointUrl      API端点URL
 * @param modelName           模型名称
 * @param encryptedCredential 加密后的凭证（AES/GCM，独立密钥；无鉴权时可空）
 * @param modelSeries         模型系列
 * @param contextWindowInput  输入上下文窗口大小
 * @param contextWindowOutput 输出上下文窗口大小
 * @param toolCallRounds      工具调用轮次上限
 * @param modelType           模型类型（CHAT / EMBEDDING）
 * @param vectorDimension     向量维度（EMBEDDING 类型必填）
 * @param status              状态
 * @param createdAt           创建时间
 * @param updatedAt           更新时间
 * @param createdBy           创建人
 * @param updatedBy           更新人
 */
public record ModelProfile(
        String profileId,
        String displayName,
        String description,
        ApiFormat apiFormat,
        String apiEndpointUrl,
        String modelName,
        String encryptedCredential,
        String modelSeries,
        Integer contextWindowInput,
        Integer contextWindowOutput,
        Integer toolCallRounds,
        ModelType modelType,
        Integer vectorDimension,
        ModelProfileStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\p{IsHan}a-zA-Z][\\p{IsHan}a-zA-Z0-9_\\-]{0,31}$");

    /**
     * 紧凑构造器：不变量校验
     */
    public ModelProfile {
        if (StringUtils.isBlank(displayName)) {
            throw new IllegalArgumentException("模型配置名称不能为空");
        }
        if (displayName.length() > 32) {
            throw new IllegalArgumentException("模型配置名称长度不能超过32个字符");
        }
        if (!NAME_PATTERN.matcher(displayName).matches()) {
            throw new IllegalArgumentException("模型配置名称只能包含中文、英文字母、数字、下划线和连字符，且不能以数字或特殊字符开头");
        }
        if (StringUtils.isBlank(apiEndpointUrl)) {
            throw new IllegalArgumentException("API端点URL不能为空");
        }
        if (apiEndpointUrl.length() > 512) {
            throw new IllegalArgumentException("API端点URL长度不能超过512个字符");
        }
        if (StringUtils.isBlank(modelName)) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        if (modelName.length() > 128) {
            throw new IllegalArgumentException("模型名称长度不能超过128个字符");
        }
        if (ObjectUtils.isEmpty(apiFormat)) {
            throw new IllegalArgumentException("API格式不能为空");
        }
        if (ObjectUtils.isEmpty(modelType)) {
            throw new IllegalArgumentException("模型类型不能为空");
        }
        // EMBEDDING 类型必须配置向量维度
        if (modelType == ModelType.EMBEDDING && ObjectUtils.isEmpty(vectorDimension)) {
            throw new IllegalArgumentException("向量嵌入模型必须配置向量维度(vectorDimension)");
        }
        if (ObjectUtils.isEmpty(toolCallRounds) || toolCallRounds < 1) {
            throw new IllegalArgumentException("工具调用轮次必须为正整数");
        }
        if (ObjectUtils.isNotEmpty(description) && description.length() > 500) {
            throw new IllegalArgumentException("描述不能超过500个字符");
        }
        if (ObjectUtils.isNotEmpty(modelSeries) && modelSeries.length() > 64) {
            throw new IllegalArgumentException("模型系列长度不能超过64个字符");
        }
        if (ObjectUtils.isNotEmpty(encryptedCredential) && encryptedCredential.length() > 4000) {
            throw new IllegalArgumentException("加密凭证内容过长");
        }
        if (ObjectUtils.isNotEmpty(contextWindowInput) && contextWindowInput < 0) {
            throw new IllegalArgumentException("输入上下文窗口大小不能为负数");
        }
        if (ObjectUtils.isNotEmpty(contextWindowOutput) && contextWindowOutput < 0) {
            throw new IllegalArgumentException("输出上下文窗口大小不能为负数");
        }
    }

    /**
     * 创建新的模型配置（默认启用）
     */
    public static ModelProfile create(
            String profileId,
            String displayName,
            String description,
            ApiFormat apiFormat,
            String apiEndpointUrl,
            String modelName,
            String encryptedCredential,
            String modelSeries,
            Integer contextWindowInput,
            Integer contextWindowOutput,
            Integer toolCallRounds,
            ModelType modelType,
            Integer vectorDimension
    ) {
        return new ModelProfile(
                profileId,
                displayName,
                description,
                apiFormat,
                apiEndpointUrl,
                modelName,
                encryptedCredential,
                modelSeries,
                contextWindowInput,
                contextWindowOutput,
                toolCallRounds,
                modelType,
                vectorDimension,
                ModelProfileStatus.ENABLED,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                null,
                null
        );
    }

    /**
     * 从数据库恢复模型配置（查询场景）
     */
    public static ModelProfile restore(
            String profileId,
            String displayName,
            String description,
            ApiFormat apiFormat,
            String apiEndpointUrl,
            String modelName,
            String encryptedCredential,
            String modelSeries,
            Integer contextWindowInput,
            Integer contextWindowOutput,
            Integer toolCallRounds,
            ModelType modelType,
            Integer vectorDimension,
            ModelProfileStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new ModelProfile(
                profileId, displayName, description, apiFormat, apiEndpointUrl, modelName,
                encryptedCredential, modelSeries, contextWindowInput, contextWindowOutput,
                toolCallRounds, modelType, vectorDimension, status, createdAt, updatedAt, createdBy, updatedBy
        );
    }
}