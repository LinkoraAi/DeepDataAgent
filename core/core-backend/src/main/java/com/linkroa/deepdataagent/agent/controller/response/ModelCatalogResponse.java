package com.linkroa.deepdataagent.agent.controller.response;

import com.linkroa.deepdataagent.agent.domain.model.ModelCatalogItem;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelEffort;

import java.util.List;

/**
 * 模型目录响应 DTO（{@code GET /api/cloud/models}，只读、不含任何供应商端点 / 凭证信息）。
 *
 * @param type                    固定对象类型（model）
 * @param id                      目录模型标识
 * @param displayName             展示名
 * @param source                  来源（system / user）
 * @param isEnabled               是否启用
 * @param isNew                   是否新模型标记
 * @param isVl                    是否视觉（VL）模型
 * @param isDefault               是否目录默认模型
 * @param efforts                 支持的推理 effort 档位（线格式词汇）
 * @param defaultEffort           缺省 effort 档位（可空）
 * @param maxInputTokens          最大输入 token（可空 = 目录未声明）
 * @param maxOutputTokens         最大输出 token（可空 = 目录未声明）
 * @param defaultContextWindow    缺省上下文窗口（可空）
 * @param availableContextWindows 可选上下文窗口档位
 */
public record ModelCatalogResponse(
        String type,
        String id,
        String displayName,
        String source,
        boolean isEnabled,
        boolean isNew,
        boolean isVl,
        boolean isDefault,
        List<String> efforts,
        String defaultEffort,
        Integer maxInputTokens,
        Integer maxOutputTokens,
        Integer defaultContextWindow,
        List<Integer> availableContextWindows
) {

    /**
     * 由目录条目值对象构建对外响应（枚举降为线格式字符串，供应商映射字段天然不在值对象内）。
     */
    public static ModelCatalogResponse of(ModelCatalogItem item) {
        return new ModelCatalogResponse(
                ModelCatalogItem.OBJECT_TYPE,
                item.id(),
                item.displayName(),
                item.source(),
                item.isEnabled(),
                item.isNew(),
                item.isVl(),
                item.isDefault(),
                item.efforts().stream().map(ModelEffort::value).toList(),
                item.defaultEffort() == null ? null : item.defaultEffort().value(),
                item.maxInputTokens(),
                item.maxOutputTokens(),
                item.defaultContextWindow(),
                item.availableContextWindows());
    }
}
