package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.ApiFormat;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * 模型标识值对象：承载 {@code api_format + model_name} 契约，派生运行时装配的模型字符串 ID。
 * <p>拼接规则作为领域规则内聚于此：AGENTSCOPE 直接使用注册表模型名（如 dashscope:qwen-plus），
 * 其余格式以 {@code 小写apiFormat:modelName} 标识（如 openai:gpt-4）。</p>
 *
 * @param apiFormat API 格式
 * @param modelName 模型名称
 */
public record ModelIndicator(ApiFormat apiFormat, String modelName) {

    private static final int MAX_MODEL_NAME_LENGTH = 128;

    /**
     * 紧凑构造器：不变量校验
     */
    public ModelIndicator {
        if (ObjectUtils.isEmpty(apiFormat)) {
            throw new IllegalArgumentException("API格式不能为空");
        }
        if (StringUtils.isBlank(modelName)) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        if (modelName.length() > MAX_MODEL_NAME_LENGTH) {
            throw new IllegalArgumentException("模型名称长度不能超过128个字符");
        }
    }

    /**
     * 静态工厂方法。
     *
     * @param apiFormat API 格式
     * @param modelName 模型名称
     */
    public static ModelIndicator of(ApiFormat apiFormat, String modelName) {
        return new ModelIndicator(apiFormat, modelName);
    }

    /**
     * 解析为运行时模型标识字符串。
     *
     * @return AGENTSCOPE 原样返回注册表模型名，其余格式返回 {@code 小写apiFormat:modelName}
     */
    public String resolved() {
        if (apiFormat == ApiFormat.AGENTSCOPE) {
            return modelName;
        }
        return apiFormat.name().toLowerCase() + ":" + modelName;
    }
}