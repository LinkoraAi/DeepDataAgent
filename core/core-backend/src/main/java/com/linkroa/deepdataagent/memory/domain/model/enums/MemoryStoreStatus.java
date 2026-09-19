package com.linkroa.deepdataagent.memory.domain.model.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * 记忆库状态（MemoryStore.status）。
 *
 * <p>Memory Store 语义：</p>
 * <ul>
 *   <li>{@link #ACTIVE}：活跃，可读写；</li>
 *   <li>{@link #ARCHIVED}：已归档，memory 与 version 仍可读，创建/更新等写操作拒绝（409）。</li>
 * </ul>
 *
 * @see com.linkroa.deepdataagent.memory.domain.model.MemoryStore
 */
public enum MemoryStoreStatus {

    /** 活跃（可读写） */
    ACTIVE("active"),
    /** 已归档（只读，写操作 409） */
    ARCHIVED("archived");

    /** 对外暴露的小写源码取值（对齐规范枚举命名小写值域） */
    private final String value;

    MemoryStoreStatus(String value) {
        this.value = value;
    }

    /**
     * 返回值域字符串（小写，如 {@code active} / {@code archived}）。
     *
     * @return 源码取值
     */
    public String getValue() {
        return value;
    }

    /**
     * 大小写不敏感地解析记忆库状态字符串。
     *
     * @param value 待解析字符串
     * @return 匹配的记忆库状态
     * @throws IllegalArgumentException 值域外或空白输入
     */
    public static MemoryStoreStatus fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("记忆库状态不能为空");
        }
        for (MemoryStoreStatus status : values()) {
            if (status.value.equalsIgnoreCase(value.trim())) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知记忆库状态: " + value);
    }
}
