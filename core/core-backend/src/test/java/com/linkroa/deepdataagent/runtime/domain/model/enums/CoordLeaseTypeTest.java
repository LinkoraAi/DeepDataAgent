package com.linkroa.deepdataagent.runtime.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CoordLeaseType} 领域枚举与租约键派生不变量单测
 * （move-coordination-leases-to-redis PR-C4：原 {@code CoordLease} 聚合删除后，
 * 键派生规则由本枚举承载，故一并接管原聚合的键不变量覆盖）。
 */
class CoordLeaseTypeTest {

    @Test
    void should_returnTurn_when_fromValue_given_lowercaseSourceValue() {
        // given
        String value = "turn";

        // when
        CoordLeaseType type = CoordLeaseType.fromValue(value);

        // then
        assertSame(CoordLeaseType.TURN, type);
        assertEquals("turn", type.getValue());
    }

    @Test
    void should_returnFire_when_fromValue_given_uppercaseWithBlank() {
        // given（大小写不敏感 + 首尾空白容忍）
        String value = "  FIRE ";

        // when
        CoordLeaseType type = CoordLeaseType.fromValue(value);

        // then
        assertSame(CoordLeaseType.FIRE, type);
        assertEquals("fire", type.getValue());
    }

    @Test
    void should_throwIllegalArgument_when_fromValue_given_blankOrUnknownValue() {
        // given / when / then（空白与未知值均快速失败，防非法类型进入下游）
        assertThrows(IllegalArgumentException.class, () -> CoordLeaseType.fromValue(" "));
        assertThrows(IllegalArgumentException.class, () -> CoordLeaseType.fromValue(null));
        assertThrows(IllegalArgumentException.class, () -> CoordLeaseType.fromValue("interrupt"));
    }

    @Test
    void should_buildTypedKey_when_keyOf_given_validBizId() {
        // given / when / then（键格式「类型:对象:<业务 ID>」，两类租约前缀互不重叠）
        assertEquals("turn:session:sess_1", CoordLeaseType.TURN.keyOf("sess_1"));
        assertEquals("fire:scheduler:dep_1", CoordLeaseType.FIRE.keyOf("dep_1"));
    }

    @Test
    void should_throwIllegalArgument_when_keyOf_given_blankBizId() {
        // given / when / then（报错携带业务 ID 语义名，便于定位调用方）
        IllegalArgumentException turnEx = assertThrows(IllegalArgumentException.class,
                () -> CoordLeaseType.TURN.keyOf(" "));
        assertEquals("sessionId 不能为空", turnEx.getMessage());
        IllegalArgumentException fireEx = assertThrows(IllegalArgumentException.class,
                () -> CoordLeaseType.FIRE.keyOf(null));
        assertEquals("schedulerId 不能为空", fireEx.getMessage());
    }

    @Test
    void should_throwIllegalArgument_when_keyOf_given_keyExceedsMaxLength() {
        // given（键长上限为防御性不变量：禁止无界业务 ID 拼入租约键 / owner 索引成员）
        String oversized = "s".repeat(CoordLeaseType.MAX_KEY_LENGTH);

        // when // then
        assertThrows(IllegalArgumentException.class, () -> CoordLeaseType.TURN.keyOf(oversized));
    }

    @Test
    void should_parseBizId_when_bizIdFromKey_given_matchingPrefix() {
        // given
        String turnKey = CoordLeaseType.TURN.keyOf("sess_42");
        String fireKey = CoordLeaseType.FIRE.keyOf("dep_42");

        // when
        String sessionId = CoordLeaseType.TURN.bizIdFromKey(turnKey);
        String schedulerId = CoordLeaseType.FIRE.bizIdFromKey(fireKey);

        // then（派生 / 反解互逆，启动恢复据此把 turn 租约映射回会话）
        assertEquals("sess_42", sessionId);
        assertEquals("dep_42", schedulerId);
    }

    @Test
    void should_throwIllegalArgument_when_bizIdFromKey_given_prefixMismatchOrBlankKey() {
        // given（fire 键不得被 turn 类型反解，反之亦然）
        String fireKey = CoordLeaseType.FIRE.keyOf("dep_1");

        // when // then（含前缀但键段为空亦视为非法）
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> CoordLeaseType.TURN.bizIdFromKey(fireKey));
        assertEquals("非法 turn 租约键: " + fireKey, mismatch.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CoordLeaseType.TURN.bizIdFromKey(" "));
        assertThrows(IllegalArgumentException.class, () -> CoordLeaseType.TURN.bizIdFromKey(null));
        assertThrows(IllegalArgumentException.class, () -> CoordLeaseType.TURN.bizIdFromKey("turn:session:"));
    }
}
