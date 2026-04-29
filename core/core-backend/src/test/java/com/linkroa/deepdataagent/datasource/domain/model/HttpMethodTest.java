package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpMethodTest {

    @Test
    void should_containGetAndPost_when_values_given_called() {
        HttpMethod[] values = HttpMethod.values();
        assertEquals(2, values.length);
    }

    @Test
    void should_returnType_when_valueOf_given_validName() {
        assertEquals(HttpMethod.GET, HttpMethod.valueOf("GET"));
        assertEquals(HttpMethod.POST, HttpMethod.valueOf("POST"));
    }
}
