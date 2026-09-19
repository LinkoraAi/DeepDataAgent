package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.ModelEffort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelRef} 单元测试：双形态解析、回显序列化与不变量校验。
 */
class ModelRefTest {

    @Test
    void should_parseShorthand_when_parse_given_stringForm() {
        // given & when
        ModelRef ref = ModelRef.parse("\"ultimate\"");

        // then
        assertEquals("ultimate", ref.id());
        assertNull(ref.effort());
        assertTrue(ref.shorthand());
    }

    @Test
    void should_parseObjectForm_when_parse_given_idAndTuning() {
        // given & when
        ModelRef ref = ModelRef.parse("{\"id\":\"ultimate\",\"effort\":\"high\",\"context_window\":200000}");

        // then
        assertEquals("ultimate", ref.id());
        assertEquals(ModelEffort.HIGH, ref.effort());
        assertEquals(200000, ref.contextWindow());
        assertFalse(ref.shorthand());
    }

    @Test
    void should_returnNull_when_parse_given_blankJson() {
        // given & when & then
        assertNull(ModelRef.parse(null));
        assertNull(ModelRef.parse(" "));
    }

    @Test
    void should_throw_when_parse_given_illegalEffort() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> ModelRef.parse("{\"id\":\"ultimate\",\"effort\":\"ultra\"}"));
    }

    @Test
    void should_throw_when_parse_given_numberForm() {
        // given & when & then
        assertThrows(IllegalStateException.class, () -> ModelRef.parse("42"));
    }

    @Test
    void should_throwWithEffortHint_when_parse_given_deprecatedSpeedField() {
        // given（speed 为公开契约早期字段，已被 effort 取代）
        // when
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ModelRef.parse("{\"id\":\"ultimate\",\"speed\":\"fast\"}"));

        // then（报错须提示改用 effort，不得静默忽略）
        assertTrue(ex.getMessage().contains("effort"));
    }

    @Test
    void should_ignoreNullSpeed_when_parse_given_explicitNullSpeed() {
        // given & when（speed 显式 null 视为未提交，与 effort/context_window 的 null 口径一致）
        ModelRef ref = ModelRef.parse("{\"id\":\"ultimate\",\"speed\":null}");

        // then
        assertEquals("ultimate", ref.id());
        assertNull(ref.effort());
    }

    @Test
    void should_throw_when_constructor_given_shorthandWithTuning() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRef("ultimate", ModelEffort.HIGH, null, true));
    }

    @Test
    void should_throw_when_constructor_given_nonPositiveContextWindow() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRef("ultimate", null, 0, false));
    }

    @Test
    void should_echoSubmittedForm_when_toJson_given_bothForms() {
        // given
        ModelRef shorthand = new ModelRef("ultimate", null, null, true);
        ModelRef object = new ModelRef("ultimate", ModelEffort.HIGH, 200000, false);

        // when
        String shorthandJson = ModelRef.toJson(shorthand);
        String objectJson = ModelRef.toJson(object);

        // then
        assertEquals("\"ultimate\"", shorthandJson);
        assertTrue(objectJson.startsWith("{\"id\":\"ultimate\""));
        assertTrue(objectJson.contains("\"effort\":\"high\""));
        assertTrue(objectJson.contains("\"context_window\":200000"));
        assertNull(ModelRef.toJson(null));
    }

    @Test
    void should_omitAbsentTuning_when_toJson_given_objectFormWithoutTuning() {
        // given
        ModelRef object = new ModelRef("ultimate", null, null, false);

        // when
        String json = ModelRef.toJson(object);

        // then
        assertEquals("{\"id\":\"ultimate\"}", json);
    }
}
