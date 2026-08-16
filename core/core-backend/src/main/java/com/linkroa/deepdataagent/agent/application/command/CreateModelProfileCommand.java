package com.linkroa.deepdataagent.agent.application.command;

import com.linkroa.deepdataagent.agent.domain.model.enums.ApiFormat;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;

/**
 * 创建模型配置命令
 *
 * @param displayName          显示名称
 * @param description          描述
 * @param apiFormat            API格式
 * @param apiEndpointUrl       API端点URL
 * @param modelName            模型名称
 * @param credential           凭证明文（可选，空串/空值表示无鉴权）
 * @param modelSeries          模型系列
 * @param contextWindowInput   输入上下文窗口大小
 * @param contextWindowOutput  输出上下文窗口大小
 * @param toolCallRounds       工具调用轮次上限
 * @param modelType            模型类型
 * @param vectorDimension      向量维度（EMBEDDING 必填）
 */
public record CreateModelProfileCommand(
        String displayName,
        String description,
        ApiFormat apiFormat,
        String apiEndpointUrl,
        String modelName,
        String credential,
        String modelSeries,
        Integer contextWindowInput,
        Integer contextWindowOutput,
        Integer toolCallRounds,
        ModelType modelType,
        Integer vectorDimension
) {
}