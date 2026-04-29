package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiAuthTypeTest {

    @Test
    void should_containAllTypes_when_values_given_called() {
        ApiAuthType[] values = ApiAuthType.values();
        assertEquals(3, values.length);
    }

    @Test
    void should_returnType_when_valueOf_given_validName() {
        assertEquals(ApiAuthType.NO_AUTH, ApiAuthType.valueOf("NO_AUTH"));
        assertEquals(ApiAuthType.BASIC_AUTH, ApiAuthType.valueOf("BASIC_AUTH"));
        assertEquals(ApiAuthType.BEARER_TOKEN, ApiAuthType.valueOf("BEARER_TOKEN"));
    }
}
