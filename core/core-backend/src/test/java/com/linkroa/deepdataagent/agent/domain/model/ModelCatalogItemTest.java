package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.ModelEffort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelCatalogItem} 单元测试：不变量校验与档位支持判定。
 */
class ModelCatalogItemTest {

    @Test
    void should_holdInvariants_when_construct_given_validSeed() {
        // given & when
        ModelCatalogItem item = item("ultimate", List.of(ModelEffort.HIGH, ModelEffort.MAX),
                ModelEffort.HIGH, List.of(200000), 200000);

        // then
        assertTrue(item.supportsEffort(ModelEffort.MAX));
        assertFalse(item.supportsEffort(ModelEffort.LOW));
        assertTrue(item.supportsEffort(null));
        assertTrue(item.supportsContextWindow(200000));
        assertFalse(item.supportsContextWindow(128000));
        assertTrue(item.supportsContextWindow(null));
    }

    @Test
    void should_supportAnyWindow_when_supportsContextWindow_given_noDeclaredWindows() {
        // given
        ModelCatalogItem item = item("lite", List.of(), null, List.of(), null);

        // then
        assertTrue(item.supportsContextWindow(999999));
    }

    @Test
    void should_throw_when_construct_given_defaultEffortOutsideEfforts() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> item("ultimate", List.of(ModelEffort.HIGH), ModelEffort.LOW, List.of(200000), 200000));
    }

    @Test
    void should_throw_when_construct_given_defaultWindowOutsideAvailable() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> item("ultimate", List.of(), null, List.of(200000), 128000));
    }

    @Test
    void should_throw_when_construct_given_illegalSource() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> new ModelCatalogItem(
                "ultimate", "Ultimate", "external", true, false, true, false,
                List.of(), null, null, null, null, List.of()));
    }

    @Test
    void should_throw_when_construct_given_nonPositiveWindow() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> new ModelCatalogItem(
                "ultimate", "Ultimate", ModelCatalogItem.SOURCE_SYSTEM, true, false, true, false,
                List.of(), null, null, null, null, List.of(0)));
    }

    private static ModelCatalogItem item(String id, List<ModelEffort> efforts, ModelEffort defaultEffort,
                                         List<Integer> windows, Integer defaultWindow) {
        return new ModelCatalogItem(id, "展示名", ModelCatalogItem.SOURCE_SYSTEM, true, false,
                true, false, efforts, defaultEffort, 180000, 32000, defaultWindow, windows);
    }
}
