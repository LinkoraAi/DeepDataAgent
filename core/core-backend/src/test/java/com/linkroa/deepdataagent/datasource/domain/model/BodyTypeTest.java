package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.BodyType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BodyTypeTest {

    @Test
    void should_haveThreeValues() {
        BodyType[] values = BodyType.values();
        assertEquals(3, values.length);
    }

    @Test
    void should_containJson() {
        assertNotNull(BodyType.valueOf("JSON"));
    }

    @Test
    void should_containFormUrlencoded() {
        assertNotNull(BodyType.valueOf("FORM_URLENCODED"));
    }

    @Test
    void should_containRaw() {
        assertNotNull(BodyType.valueOf("RAW"));
    }

    @Test
    void should_throwException_when_invalidName() {
        assertThrows(IllegalArgumentException.class, () -> BodyType.valueOf("INVALID"));
    }
}
