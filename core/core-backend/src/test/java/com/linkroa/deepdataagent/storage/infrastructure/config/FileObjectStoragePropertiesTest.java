package com.linkroa.deepdataagent.storage.infrastructure.config;

import com.linkroa.deepdataagent.storage.domain.model.enums.StorageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileObjectStorageProperties} 单元测试。
 */
class FileObjectStoragePropertiesTest {

    @Test
    void should_validateSuccess_when_validate_givenDefaultType() {
        FileObjectStorageProperties properties = new FileObjectStorageProperties();

        properties.validate();

        assertEquals(StorageType.RUSTFS, properties.resolveType());
    }

    @Test
    void should_resolveType_when_validate_givenLowerCaseType() {
        FileObjectStorageProperties properties = new FileObjectStorageProperties();
        properties.setType("rustfs");

        assertEquals(StorageType.RUSTFS, properties.resolveType());
    }

    @Test
    void should_throwException_when_validate_givenBlankType() {
        FileObjectStorageProperties properties = new FileObjectStorageProperties();
        properties.setType("  ");

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void should_throwException_when_validate_givenUnsupportedType() {
        FileObjectStorageProperties properties = new FileObjectStorageProperties();
        properties.setType("azure-blob");

        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(ex.getMessage().contains("azure-blob"));
    }
}