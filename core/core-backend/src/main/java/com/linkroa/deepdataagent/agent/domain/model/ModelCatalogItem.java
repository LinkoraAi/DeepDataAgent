package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.ModelEffort;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 模型目录条目值对象（模型目录的本地种子清单，配置驱动、只读，对应模型目录对象）。
 * <p>对外可见字段：id / displayName / source / isEnabled / isNew / isVl / isDefault /
 * efforts / defaultEffort / maxInputTokens / maxOutputTokens / defaultContextWindow /
 * availableContextWindows。供应商映射（内部 model_profile 凭证配置）不在本值对象内，
 * 由模型目录服务解析。</p>
 * <p>不变量：id / displayName 非空；defaultEffort（若声明）须在 efforts 内；
 * defaultContextWindow（若声明且目录提供档位）须在 availableContextWindows 内；窗口与 token 数均为正。</p>
 *
 * @param id                       目录模型标识（Agent 版本 {@code model} 引用值）
 * @param displayName              展示名
 * @param source                   来源（system / user）
 * @param isEnabled                是否启用（禁用条目不出目录、不可被新引用）
 * @param isNew                    是否新模型标记
 * @param isVl                     是否视觉（VL）模型
 * @param isDefault                是否目录默认模型
 * @param efforts                  支持的推理 effort 档位（空列表 = 不支持调参）
 * @param defaultEffort            缺省 effort 档位（可空）
 * @param maxInputTokens           最大输入 token（可空 = 目录未声明）
 * @param maxOutputTokens          最大输出 token（可空 = 目录未声明）
 * @param defaultContextWindow     缺省上下文窗口（可空）
 * @param availableContextWindows  可选上下文窗口档位（空列表 = 不支持调参）
 */
public record ModelCatalogItem(
        String id,
        String displayName,
        String source,
        boolean isEnabled,
        boolean isNew,
        boolean isVl,
        boolean isDefault,
        List<ModelEffort> efforts,
        ModelEffort defaultEffort,
        Integer maxInputTokens,
        Integer maxOutputTokens,
        Integer defaultContextWindow,
        List<Integer> availableContextWindows
) {

    /** 模型目录对象的固定 type 词汇。 */
    public static final String OBJECT_TYPE = "model";
    /** 系统内置来源。 */
    public static final String SOURCE_SYSTEM = "system";
    /** 用户来源。 */
    public static final String SOURCE_USER = "user";

    /**
     * 紧凑构造器：不变量校验（非空、档位成员关系、正数）。
     */
    public ModelCatalogItem {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("模型目录条目 id 不能为空");
        }
        if (StringUtils.isBlank(displayName)) {
            throw new IllegalArgumentException("模型目录条目 displayName 不能为空");
        }
        if (!SOURCE_SYSTEM.equals(source) && !SOURCE_USER.equals(source)) {
            throw new IllegalArgumentException("模型目录条目 source 非法，须为 system/user");
        }
        efforts = efforts == null ? List.of() : List.copyOf(efforts);
        availableContextWindows = availableContextWindows == null ? List.of() : List.copyOf(availableContextWindows);
        if (defaultEffort != null && !efforts.contains(defaultEffort)) {
            throw new IllegalArgumentException("模型目录 default_effort 须在 efforts 之内");
        }
        if (defaultContextWindow != null && defaultContextWindow < 1) {
            throw new IllegalArgumentException("模型目录 default_context_window 必须大于0");
        }
        if (availableContextWindows.stream().anyMatch(window -> window == null || window < 1)) {
            throw new IllegalArgumentException("模型目录 available_context_windows 档位必须大于0");
        }
        if (defaultContextWindow != null && !availableContextWindows.isEmpty()
                && !availableContextWindows.contains(defaultContextWindow)) {
            throw new IllegalArgumentException("模型目录 default_context_window 须在可选档位之内");
        }
        if (maxInputTokens != null && maxInputTokens < 1) {
            throw new IllegalArgumentException("模型目录 max_input_tokens 必须大于0");
        }
        if (maxOutputTokens != null && maxOutputTokens < 1) {
            throw new IllegalArgumentException("模型目录 max_output_tokens 必须大于0");
        }
    }

    /** 目录是否声明该 effort 档位。 */
    public boolean supportsEffort(ModelEffort effort) {
        return effort == null || efforts.contains(effort);
    }

    /** 目录是否声明该上下文窗口档位。 */
    public boolean supportsContextWindow(Integer contextWindow) {
        return contextWindow == null || availableContextWindows.isEmpty()
                || availableContextWindows.contains(contextWindow);
    }
}
