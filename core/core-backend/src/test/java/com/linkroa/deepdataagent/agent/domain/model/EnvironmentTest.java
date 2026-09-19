package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentTest {

    private static EnvironmentConfig config() {
        return EnvironmentConfig.cloudDefault();
    }

    @Test
    void should_createEnvironment_when_create_given_validFields() {
        // given // when
        Environment environment = Environment.create(
                "env-1", "云端环境", "团队环境", config(), "{\"team\":\"data\"}", 1L);

        // then
        assertEquals("env-1", environment.environmentId());
        assertEquals("云端环境", environment.name());
        assertEquals("团队环境", environment.description());
        assertEquals(EnvironmentType.CLOUD, environment.config().type());
        assertEquals("{\"team\":\"data\"}", environment.metadata());
        assertEquals(1L, environment.ownerId());
        assertNull(environment.archivedAt());
        assertFalse(environment.isArchived());
    }

    @Test
    void should_defaultToCloudConfig_when_create_given_nullConfig() {
        // given // when
        Environment environment = Environment.create("env-1", "云端环境", null, null, null, 1L);

        // then
        assertEquals(EnvironmentType.CLOUD, environment.config().type());
        assertNull(environment.config().setupScript());
    }

    @Test
    void should_normalizeBlankMetadata_when_create_given_blankMetadata() {
        // given // when
        Environment environment = Environment.create("env-1", "云端环境", null, config(), "  ", 1L);

        // then
        assertEquals("{}", environment.metadata());
    }

    @Test
    void should_normalizeBlankDescription_when_create_given_nullDescription() {
        // given // when
        Environment environment = Environment.create("env-1", "云端环境", null, config(), null, 1L);

        // then
        assertEquals("", environment.description());
    }

    @Test
    void should_throwException_when_create_given_blankName() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Environment.create("env-1", " ", null, config(), null, 1L));
    }

    @Test
    void should_acceptLongName_when_create_given_nameWithoutLengthLimit() {
        // given：公开契约不设名称长度上限，仅做非空校验
        String longName = "环".repeat(200);

        // when
        Environment environment = Environment.create("env-1", longName, null, config(), null, 1L);

        // then
        assertEquals(longName, environment.name());
    }

    @Test
    void should_throwException_when_create_given_descriptionExceedsLimit() {
        // given
        String longDescription = "描".repeat(Environment.MAX_DESCRIPTION_LENGTH + 1);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Environment.create("env-1", "云端环境", longDescription, config(), null, 1L));
    }

    @Test
    void should_throwException_when_construct_given_nullConfig() {
        // given
        OffsetDateTime now = OffsetDateTime.now();

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new Environment(null, "env-1", "云端环境", null, null, "{}", 1L,
                        null, now, now, null, null));
    }

    @Test
    void should_defaultToCloudConfig_when_restore_given_nullConfig() {
        // given
        OffsetDateTime now = OffsetDateTime.now();

        // when
        Environment environment = Environment.restore(1L, "env-1", "云端环境", null, null, null, 1L,
                null, now, now, "tester", "tester");

        // then
        assertEquals(EnvironmentType.CLOUD, environment.config().type());
        assertEquals("{}", environment.metadata());
    }

    @Test
    void should_markArchived_when_withArchivedAt_given_archivedAt() {
        // given
        Environment environment = Environment.create("env-1", "云端环境", null, config(), null, 1L);
        OffsetDateTime archivedAt = OffsetDateTime.now();

        // when
        Environment archived = environment.withArchivedAt(archivedAt);

        // then
        assertEquals(archivedAt, archived.archivedAt());
        assertTrue(archived.isArchived());
    }

    @Test
    void should_clearArchived_when_withArchivedAt_given_null() {
        // given
        Environment archived = Environment.restore(1L, "env-1", "云端环境", null, config(), null, 1L,
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), "tester", "tester");

        // when
        Environment restored = archived.withArchivedAt(null);

        // then
        assertNull(restored.archivedAt());
        assertFalse(restored.isArchived());
    }
}