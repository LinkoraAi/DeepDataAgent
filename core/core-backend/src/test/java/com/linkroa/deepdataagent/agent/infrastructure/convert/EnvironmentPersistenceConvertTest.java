package com.linkroa.deepdataagent.agent.infrastructure.convert;

import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentConfig;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentPackages;
import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.EnvironmentEntity;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EnvironmentPersistenceConvert} 持久化转换单测（config 值对象 ⇄ JSONB 文本）。
 */
class EnvironmentPersistenceConvertTest {

    private static Environment buildEnvironment(EnvironmentConfig config) {
        OffsetDateTime now = OffsetDateTime.now();
        return Environment.restore(1L, "env-1", "数据分析环境", "团队环境", config, "{\"team\":\"data\"}",
                1L, null, now, now, "tester", "tester");
    }

    @Test
    void should_writeConfigJsonAndDescription_when_toEntity_given_fullConfig() {
        // given
        EnvironmentConfig config = new EnvironmentConfig(EnvironmentType.CLOUD,
                new EnvironmentPackages(List.of("git"), null, null, null, null, List.of("pandas==2.2.0")),
                "echo ready");

        // when
        EnvironmentEntity entity = EnvironmentPersistenceConvert.INSTANCE.toEntity(buildEnvironment(config));

        // then（无冗余 type 列，类型权威形状在 config JSONB；持久化键为 setup_script）
        assertEquals("团队环境", entity.getDescription());
        assertNull(entity.getArchivedAt());
        assertTrue(entity.getConfig().contains("\"type\":\"cloud\""));
        assertTrue(entity.getConfig().contains("\"setup_script\":\"echo ready\""));
        assertTrue(entity.getConfig().contains("pandas==2.2.0"));
        assertEquals("{\"team\":\"data\"}", entity.getMetadata());
    }

    @Test
    void should_roundTripConfig_when_toDomain_given_convertedEntity() {
        // given
        EnvironmentConfig config = new EnvironmentConfig(EnvironmentType.SELF_HOSTED,
                EnvironmentPackages.empty(), "apt-get update");

        // when
        EnvironmentEntity entity = EnvironmentPersistenceConvert.INSTANCE.toEntity(buildEnvironment(config));
        Environment domain = EnvironmentPersistenceConvert.INSTANCE.toDomain(entity);

        // then（值对象经 JSON 往返保持一致）
        assertEquals(EnvironmentType.SELF_HOSTED, domain.config().type());
        assertEquals("团队环境", domain.description());
        assertEquals("apt-get update", domain.config().setupScript());
        assertTrue(domain.config().packages().isEmpty());
        assertEquals("{\"team\":\"data\"}", domain.metadata());
    }

    @Test
    void should_echoAllSixManagers_when_configToJson_given_partialPackages() {
        // given
        EnvironmentConfig config = new EnvironmentConfig(EnvironmentType.CLOUD,
                new EnvironmentPackages(List.of("git"), null, null, null, null, null), null);

        // when
        String json = EnvironmentPersistenceConvert.INSTANCE.configToJson(config);

        // then（六类键全量回显，缺省为空数组）
        assertTrue(json.contains("\"apt\":[\"git\"]"));
        assertTrue(json.contains("\"cargo\":[]"));
        assertTrue(json.contains("\"gem\":[]"));
        assertTrue(json.contains("\"go\":[]"));
        assertTrue(json.contains("\"npm\":[]"));
        assertTrue(json.contains("\"pip\":[]"));
    }

    @Test
    void should_returnCloudDefault_when_jsonToConfig_given_blankJson() {
        // given // when
        EnvironmentConfig config = EnvironmentPersistenceConvert.INSTANCE.jsonToConfig(" ");

        // then
        assertEquals(EnvironmentType.CLOUD, config.type());
        assertTrue(config.packages().isEmpty());
        assertNull(config.setupScript());
    }

    @Test
    void should_tolerateMissingKeys_when_jsonToConfig_given_partialJson() {
        // given // when（缺 packages / setup_script 键的极简 config）
        EnvironmentConfig config = EnvironmentPersistenceConvert.INSTANCE.jsonToConfig("{\"type\":\"self_hosted\"}");

        // then
        assertEquals(EnvironmentType.SELF_HOSTED, config.type());
        assertTrue(config.packages().isEmpty());
        assertNull(config.setupScript());
    }

    @Test
    void should_throwException_when_jsonToConfig_given_legacyTypeValue() {
        // given // when // then（旧自建类型值域已废弃，读回即拒）
        assertThrows(IllegalArgumentException.class,
                () -> EnvironmentPersistenceConvert.INSTANCE.jsonToConfig("{\"type\":\"local\"}"));
    }
}
