package com.linkroa.deepdataagent.agent.domain.model.enums;

/**
 * 模型推理 effort 档位（模型目录 {@code efforts} 枚举词汇）。
 * <p>目录模型的可用档位由 {@code efforts} 字段声明，Agent 版本引用的 {@code model.effort}
 * 须为其中之一；本枚举承载词汇合法性校验，目录级（模型是否支持该档位）校验由模型目录完成。</p>
 */
public enum ModelEffort {

    /** 关闭推理。 */
    NONE("none"),
    /** 低推理强度。 */
    LOW("low"),
    /** 中推理强度。 */
    MEDIUM("medium"),
    /** 高推理强度。 */
    HIGH("high"),
    /** 超高推理强度。 */
    XHIGH("xhigh"),
    /** 最大推理强度。 */
    MAX("max");

    private final String value;

    ModelEffort(String value) {
        this.value = value;
    }

    /** JSON 线格式取值。 */
    public String value() {
        return value;
    }

    /**
     * 按线格式取值解析 effort 档位。
     *
     * @param raw 原始字符串（可空 = 未声明）
     * @return 解析结果；{@code raw} 为空返回 {@code null}
     * @throws IllegalArgumentException 取值不在 none|low|medium|high|xhigh|max 之内
     */
    public static ModelEffort from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (ModelEffort effort : values()) {
            if (effort.value.equals(raw.trim())) {
                return effort;
            }
        }
        throw new IllegalArgumentException("模型 effort 档位非法，须为 none|low|medium|high|xhigh|max");
    }
}
