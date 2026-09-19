package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.domain.model.ModelCatalogItem;
import com.linkroa.deepdataagent.agent.domain.model.ModelRef;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelEffort;
import com.linkroa.deepdataagent.agent.infrastructure.config.ModelCatalogProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelCatalogService} 单元测试：目录查询、发布校验与供应商映射解析。
 */
class ModelCatalogServiceTest {

    private ModelCatalogService service;
    private ModelCatalogProperties properties;

    @BeforeEach
    void setUp() {
        service = new ModelCatalogService();
        properties = new ModelCatalogProperties();
        properties.setItems(List.of(
                seedItem("ultimate", List.of("high", "xhigh", "max"), "high", List.of(200000), 200000),
                seedItem("lite", List.of(), null, List.of(), null),
                disabledItem("retired")));
        properties.setProviderMappings(Map.of(
                "*", Map.of("ultimate", "mp-default", "lite", "mp-cheap"),
                "42", Map.of("ultimate", "mp-tenant42")));
        ReflectionTestUtils.setField(service, "properties", properties);
    }

    @Test
    void should_listEnabledOnly_when_listModels_given_seededCatalog() {
        // given & when
        List<ModelCatalogItem> items = service.listModels();

        // then
        assertEquals(List.of("ultimate", "lite"), items.stream().map(ModelCatalogItem::id).toList());
        ModelCatalogItem ultimate = items.get(0);
        assertEquals("model", ModelCatalogItem.OBJECT_TYPE);
        assertEquals(ModelCatalogItem.SOURCE_SYSTEM, ultimate.source());
        assertEquals(List.of(ModelEffort.HIGH, ModelEffort.XHIGH, ModelEffort.MAX), ultimate.efforts());
        assertEquals(ModelEffort.HIGH, ultimate.defaultEffort());
        assertEquals(List.of(200000), ultimate.availableContextWindows());
    }

    @Test
    void should_throw_when_listModels_given_emptyCatalog() {
        // given
        properties.setItems(List.of());

        // when & then
        assertThrows(IllegalStateException.class, () -> service.listModels());
    }

    @Test
    void should_findDisabled_when_find_given_disabledId() {
        // given & when
        Optional<ModelCatalogItem> item = service.find("retired");

        // then（目录端点过滤禁用，但引用校验须能识别“存在但禁用”）
        assertTrue(item.isPresent());
        assertEquals(false, item.get().isEnabled());
    }

    @Test
    void should_pass_when_validateModel_given_catalogedModelWithSupportedTuning() {
        // given
        ModelRef ref = new ModelRef("ultimate", ModelEffort.XHIGH, 200000, false);

        // when & then
        service.validateModel(ref);
    }

    @Test
    void should_skip_when_validateModel_given_nullRef() {
        // given & when & then
        service.validateModel(null);
    }

    @Test
    void should_throw_when_validateModel_given_unknownModelId() {
        // given
        ModelRef ref = new ModelRef("gpt-99", null, null, true);

        // when & then
        assertThrows(IllegalArgumentException.class, () -> service.validateModel(ref));
    }

    @Test
    void should_throw_when_validateModel_given_disabledModel() {
        // given
        ModelRef ref = new ModelRef("retired", null, null, true);

        // when & then
        assertThrows(IllegalArgumentException.class, () -> service.validateModel(ref));
    }

    @Test
    void should_throw_when_validateModel_given_unsupportedEffort() {
        // given
        ModelRef ref = new ModelRef("ultimate", ModelEffort.LOW, null, false);

        // when & then
        assertThrows(IllegalArgumentException.class, () -> service.validateModel(ref));
    }

    @Test
    void should_throw_when_validateModel_given_unsupportedContextWindow() {
        // given
        ModelRef ref = new ModelRef("ultimate", null, 128000, false);

        // when & then
        assertThrows(IllegalArgumentException.class, () -> service.validateModel(ref));
    }

    @Test
    void should_preferTenantMapping_when_resolveProfileId_given_tenantOverride() {
        // given & when
        String profileId = service.resolveProfileId("ultimate", 42L);

        // then
        assertEquals("mp-tenant42", profileId);
    }

    @Test
    void should_fallbackToDefaultKey_when_resolveProfileId_given_noTenantEntry() {
        // given & when
        String profileId = service.resolveProfileId("ultimate", 7L);

        // then
        assertEquals("mp-default", profileId);
    }

    @Test
    void should_returnNull_when_resolveProfileId_given_noMappingConfigured() {
        // given & when & then
        assertNull(service.resolveProfileId("no-such", null));
        assertNull(service.resolveProfileId(null, 42L));
    }

    private static ModelCatalogProperties.CatalogItem seedItem(String id, List<String> efforts,
                                                               String defaultEffort, List<Integer> windows,
                                                               Integer defaultWindow) {
        ModelCatalogProperties.CatalogItem item = new ModelCatalogProperties.CatalogItem();
        item.setId(id);
        item.setDisplayName(id + " 展示名");
        item.setEfforts(efforts);
        item.setDefaultEffort(defaultEffort);
        item.setIsVl(true);
        item.setMaxInputTokens(180000);
        item.setAvailableContextWindows(windows);
        item.setDefaultContextWindow(defaultWindow);
        return item;
    }

    private static ModelCatalogProperties.CatalogItem disabledItem(String id) {
        ModelCatalogProperties.CatalogItem item = seedItem(id, List.of(), null, List.of(), null);
        item.setIsEnabled(false);
        return item;
    }
}
