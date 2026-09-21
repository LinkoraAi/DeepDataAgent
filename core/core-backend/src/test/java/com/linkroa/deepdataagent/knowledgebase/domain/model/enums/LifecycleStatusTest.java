package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LifecycleStatus} 值域单测：锁定三态值域，
 * 「已删除」不再是持久状态（收口为条件 DELETE、行缺失即已删除），DELETED 常量必须保持移除。
 */
class LifecycleStatusTest {

    @Test
    void should_containExactlyThreeStates_when_values_given_currentDomain() {
        // when & then：值域锁定为 ACTIVE / DELETING / DELETE_FAILED 三态
        assertEquals(3, LifecycleStatus.values().length);
        assertTrue(Arrays.asList(LifecycleStatus.values()).contains(LifecycleStatus.ACTIVE));
        assertTrue(Arrays.asList(LifecycleStatus.values()).contains(LifecycleStatus.DELETING));
        assertTrue(Arrays.asList(LifecycleStatus.values()).contains(LifecycleStatus.DELETE_FAILED));
    }

    @Test
    void should_notContainDeleted_when_valueOf_given_deletedName() {
        // given & when & then：DELETED 已退出持久状态值域，按名解析必须抛异常
        assertFalse(Arrays.stream(LifecycleStatus.values())
                .anyMatch(status -> "DELETED".equals(status.name())));
        assertThrows(IllegalArgumentException.class, () -> LifecycleStatus.valueOf("DELETED"));
    }
}
