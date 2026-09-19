package com.linkroa.deepdataagent.memory.domain.model.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * 记忆版本动作类型（MemoryVersion.action）。
 *
 * <p>版本历史为不可变快照，每条记忆（entry）的每次变更均落一个版本行：</p>
 * <ul>
 *   <li>{@link #CREATED}：条目创建（版本 1，携带内容）；</li>
 *   <li>{@link #UPDATED}：条目内容更新（版本递增，携带内容）；</li>
 *   <li>{@link #DELETED}：条目删除（tombstone 墓碑版本，不携带内容）。</li>
 * </ul>
 *
 * @see com.linkroa.deepdataagent.memory.domain.model.MemoryVersion
 */
public enum MemoryVersionAction {

    /** 条目创建 */
    CREATED("created"),
    /** 条目内容更新 */
    UPDATED("updated"),
    /** 条目删除（tombstone 墓碑） */
    DELETED("deleted");

    /** 对外暴露的小写源码取值（对齐规范枚举命名小写值域） */
    private final String value;

    MemoryVersionAction(String value) {
        this.value = value;
    }

    /**
     * 返回值域字符串（小写，如 {@code created} / {@code updated} / {@code deleted}）。
     *
     * @return 源码取值
     */
    public String getValue() {
        return value;
    }

    /**
     * 大小写不敏感地解析版本动作类型字符串。
     *
     * @param value 待解析字符串
     * @return 匹配的版本动作类型
     * @throws IllegalArgumentException 值域外或空白输入
     */
    public static MemoryVersionAction fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("记忆版本动作类型不能为空");
        }
        for (MemoryVersionAction action : values()) {
            if (action.value.equalsIgnoreCase(value.trim())) {
                return action;
            }
        }
        throw new IllegalArgumentException("未知记忆版本动作类型: " + value);
    }
}
