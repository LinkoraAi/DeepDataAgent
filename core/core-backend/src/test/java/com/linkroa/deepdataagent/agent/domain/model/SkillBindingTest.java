package com.linkroa.deepdataagent.agent.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillBindingTest {

    /** 钉版版本键（创建时刻 epoch 微秒字符串）。 */
    private static final String EPOCH = "1759178010641129";

    @Test
    void should_parseBindings_when_parse_given_snakeCaseJson() {
        // given（custom 钉版 + catalog 动态版）
        String json = "[{\"type\":\"custom\",\"skill_id\":\"skill_a\",\"version\":\"1759178010641129\"},"
                + "{\"type\":\"catalog\",\"skill_id\":\"skill_b\"}]";

        // when
        List<SkillBinding> bindings = SkillBinding.parse(json);

        // then
        assertEquals(2, bindings.size());
        assertEquals("skill_a", bindings.get(0).skillId());
        assertEquals(EPOCH, bindings.get(0).version());
        assertFalse(bindings.get(0).isDynamicVersion());
        assertTrue(bindings.get(0).isCustom());
        assertNull(bindings.get(1).version());
        assertTrue(bindings.get(1).isDynamicVersion());
        assertFalse(bindings.get(1).isCustom());
    }

    @Test
    void should_returnEmptyList_when_parse_given_blankJson() {
        // given // when
        List<SkillBinding> bindings = SkillBinding.parse("  ");

        // then
        assertTrue(bindings.isEmpty());
    }

    @Test
    void should_normalizeToDynamic_when_parse_given_latestVersion() {
        // given（"latest" 忽略大小写归一为动态版）
        String json = "[{\"type\":\"custom\",\"skill_id\":\"skill_a\",\"version\":\"LATEST\"}]";

        // when
        List<SkillBinding> bindings = SkillBinding.parse(json);

        // then
        assertNull(bindings.get(0).version());
        assertTrue(bindings.get(0).isDynamicVersion());
    }

    @Test
    void should_keepEpochString_when_parse_given_stringVersion() {
        // given // when
        List<SkillBinding> bindings = SkillBinding.parse(
                "[{\"type\":\"custom\",\"skill_id\":\"skill_a\",\"version\":\"1759178010641129\"}]");

        // then（epoch 字符串原样保留，不做数值转换）
        assertEquals(EPOCH, bindings.get(0).version());
    }

    @Test
    void should_omitNullVersion_when_toJson_given_bindingWithoutVersion() {
        // given
        List<SkillBinding> bindings = List.of(new SkillBinding("catalog", "skill_a", null));

        // when
        String json = SkillBinding.toJson(bindings);

        // then
        assertEquals("[{\"type\":\"catalog\",\"skill_id\":\"skill_a\"}]", json);
    }

    @Test
    void should_returnNull_when_toJson_given_emptyBindings() {
        // given // when // then
        assertNull(SkillBinding.toJson(List.of()));
    }

    @Test
    void should_roundTrip_when_toJson_given_parsedBindings() {
        // given
        String json = "[{\"type\":\"custom\",\"skill_id\":\"skill_a\",\"version\":\"1759178010641129\"}]";

        // when
        String roundTripped = SkillBinding.toJson(SkillBinding.parse(json));

        // then
        assertEquals(json, roundTripped);
    }

    @Test
    void should_pinVersion_when_withVersion_given_epochKey() {
        // given（动态版绑定）
        SkillBinding binding = new SkillBinding("custom", "skill_a", null);

        // when
        SkillBinding pinned = binding.withVersion(EPOCH);

        // then
        assertEquals(EPOCH, pinned.version());
        assertEquals("skill_a", pinned.skillId());
        assertFalse(pinned.isDynamicVersion());
    }

    @Test
    void should_throwException_when_constructor_given_unknownType() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> new SkillBinding("workspace", "skill_a", null));
    }

    @Test
    void should_throwException_when_constructor_given_invalidSkillIdPrefix() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> new SkillBinding("custom", "bad-id", null));
    }

    @Test
    void should_throwException_when_constructor_given_blankVersion() {
        // given // when // then（空白版本键须显式省略或为 "latest"，不得为空白字符串）
        assertThrows(IllegalArgumentException.class, () -> new SkillBinding("custom", "skill_a", "  "));
    }

    @Test
    void should_throwException_when_validateCount_given_moreThanMaxBindings() {
        // given
        List<SkillBinding> bindings = IntStream.range(0, SkillBinding.MAX_BINDINGS + 1)
                .mapToObj(i -> new SkillBinding("custom", "skill_" + i, EPOCH))
                .toList();

        // when // then
        assertThrows(IllegalArgumentException.class, () -> SkillBinding.validateCount(bindings));
    }

    @Test
    void should_pass_when_validateCount_given_exactlyMaxBindings() {
        // given
        List<SkillBinding> bindings = IntStream.range(0, SkillBinding.MAX_BINDINGS)
                .mapToObj(i -> new SkillBinding("custom", "skill_" + i, EPOCH))
                .toList();

        // when // then
        SkillBinding.validateCount(bindings);
    }
}