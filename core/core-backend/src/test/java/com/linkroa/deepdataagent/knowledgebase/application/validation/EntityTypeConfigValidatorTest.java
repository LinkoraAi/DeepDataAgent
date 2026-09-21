package com.linkroa.deepdataagent.knowledgebase.application.validation;

import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntityTypeConfigValidator} 单元测试。
 */
class EntityTypeConfigValidatorTest {

    @Test
    void should_pass_when_validate_given_emptyTypesList() {
        // given
        String configJson = "{\"entityTypes\":[]}";

        // when // then
        assertDoesNotThrow(() -> EntityTypeConfigValidator.validate(configJson));
    }

    @Test
    void should_pass_when_validate_given_validCustomTypes() {
        // given
        String configJson = "{\"entityTypes\":[{\"entityType\":\"Product\"},{\"entityType\":\"产品手册\"},"
                + "{\"entityType\":\"api_v2\"}]}";

        // when // then
        assertDoesNotThrow(() -> EntityTypeConfigValidator.validate(configJson));
    }

    @Test
    void should_pass_when_validate_given_nameAtMaxLengthBoundary() {
        // given：恰好 30 个字符
        String name = "A".repeat(30);
        String configJson = "{\"entityTypes\":[{\"entityType\":\"" + name + "\"}]}";

        // when // then
        assertDoesNotThrow(() -> EntityTypeConfigValidator.validate(configJson));
    }

    @Test
    void should_throwBadRequest_when_validate_given_blankConfig() {
        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> EntityTypeConfigValidator.validate(" "));
        assertTrue(exception.getMessage().contains("不能为空"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_invalidJson() {
        // when // then
        assertThrows(DeepDataAgentException.class, () -> EntityTypeConfigValidator.validate("{invalid"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_nonObjectRoot() {
        // when // then
        assertThrows(DeepDataAgentException.class, () -> EntityTypeConfigValidator.validate("[\"Product\"]"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_entityTypesNotArray() {
        // given
        String configJson = "{\"entityTypes\":\"Product\"}";

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> EntityTypeConfigValidator.validate(configJson));
        assertTrue(exception.getMessage().contains("JSON数组"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_tooManyTypes() {
        // given：11 个合法名称，超过上限 10
        StringBuilder builder = new StringBuilder("{\"entityTypes\":[");
        for (int i = 1; i <= 11; i++) {
            if (i > 1) {
                builder.append(",");
            }
            builder.append("{\"entityType\":\"类型").append(i).append("\"}");
        }
        builder.append("]}");

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> EntityTypeConfigValidator.validate(builder.toString()));
        assertTrue(exception.getMessage().contains("不得超过10个"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_nameWithIllegalChar() {
        // given
        String configJson = "{\"entityTypes\":[{\"entityType\":\"用户@A\"}]}";

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> EntityTypeConfigValidator.validate(configJson));
        assertTrue(exception.getMessage().contains("用户@A"));
        assertTrue(exception.getMessage().contains("仅允许中英文字母、数字与下划线"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_nameTooShort() {
        // given：1 个字符低于下限 2
        String configJson = "{\"entityTypes\":[{\"entityType\":\"A\"}]}";

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> EntityTypeConfigValidator.validate(configJson));
        assertTrue(exception.getMessage().contains("2到30"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_nameTooLong() {
        // given：31 个字符超过上限 30
        String configJson = "{\"entityTypes\":[{\"entityType\":\"" + "B".repeat(31) + "\"}]}";

        // when // then
        assertThrows(DeepDataAgentException.class, () -> EntityTypeConfigValidator.validate(configJson));
    }

    @Test
    void should_throwBadRequest_when_validate_given_missingNameField() {
        // given
        String configJson = "{\"entityTypes\":[{\"code\":\"X1\"}]}";

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> EntityTypeConfigValidator.validate(configJson));
        assertTrue(exception.getMessage().contains("第1个"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_nonObjectItem() {
        // given
        String configJson = "{\"entityTypes\":[\"Product\"]}";

        // when // then
        assertThrows(DeepDataAgentException.class, () -> EntityTypeConfigValidator.validate(configJson));
    }

    @Test
    void should_throwBadRequest_when_validate_given_caseInsensitiveDuplicate() {
        // given：大小写归一后重名
        String configJson = "{\"entityTypes\":[{\"entityType\":\"Product\"},{\"entityType\":\"product\"}]}";

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> EntityTypeConfigValidator.validate(configJson));
        assertTrue(exception.getMessage().contains("重复"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_builtinCnName() {
        // given：撞内置类型中文名「组织」
        String configJson = "{\"entityTypes\":[{\"entityType\":\"组织\"}]}";

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> EntityTypeConfigValidator.validate(configJson));
        assertTrue(exception.getMessage().contains("不得与内置类型重名"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_builtinEnglishCodeIgnoreCase() {
        // given：撞内置类型英文代码（大小写不敏感）
        String configJson = "{\"entityTypes\":[{\"entityType\":\"organization\"}]}";

        // when // then
        assertThrows(DeepDataAgentException.class, () -> EntityTypeConfigValidator.validate(configJson));
    }

    @Test
    void should_throwBadRequest_when_validate_given_reservedOtherEnglishCode() {
        // given
        String configJson = "{\"entityTypes\":[{\"entityType\":\"OTHER\"}]}";

        // when // then
        DeepDataAgentException exception = assertThrows(DeepDataAgentException.class,
                () -> EntityTypeConfigValidator.validate(configJson));
        assertTrue(exception.getMessage().contains("强制保留"));
    }

    @Test
    void should_throwBadRequest_when_validate_given_reservedOtherCnName() {
        // given
        String configJson = "{\"entityTypes\":[{\"entityType\":\"其他\"}]}";

        // when // then
        assertThrows(DeepDataAgentException.class, () -> EntityTypeConfigValidator.validate(configJson));
    }
}
