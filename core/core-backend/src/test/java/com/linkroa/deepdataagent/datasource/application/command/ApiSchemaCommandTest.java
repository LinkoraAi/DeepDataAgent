package com.linkroa.deepdataagent.datasource.application.command;

import com.linkroa.deepdataagent.datasource.domain.model.PreOperationConfig;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiSchemaCommandTest {

    @Test
    void should_createCommand_when_constructor_given_validInput() {
        ApiFieldCommand field = new ApiFieldCommand("id", "ID", "$.id", "NUMBER", "desc");
        ApiSchemaCommand command = new ApiSchemaCommand(
                "users", HttpMethod.GET, "http://api.example.com",
                Map.of("Accept", "application/json"), Map.of("page", "1"),
                "{}", "JSON", "$.data", 30, 3,
                ApiAuthType.BASIC_AUTH, "user", "pass",
                "PAGE_BASED", "pageSize", "pageNum", "$.total", 20, 100,
                null, List.of(field)
        );

        assertEquals("users", command.name());
        assertEquals(HttpMethod.GET, command.method());
        assertEquals("http://api.example.com", command.url());
        assertEquals(Map.of("Accept", "application/json"), command.headers());
        assertEquals("{}", command.body());
        assertEquals("$.data", command.jsonPathConfig());
        assertEquals(30, command.timeout());
        assertEquals(3, command.retryCount());
        assertEquals(ApiAuthType.BASIC_AUTH, command.authType());
        assertEquals("user", command.authUsername());
        assertEquals("pass", command.authPassword());
        assertEquals("PAGE_BASED", command.paginationType());
        assertEquals(20, command.pageSize());
        assertEquals(100, command.maxPages());
        assertEquals(1, command.fields().size());
    }

    @Test
    void should_createCommand_when_constructor_given_minimalInput() {
        ApiSchemaCommand command = new ApiSchemaCommand(
                "schema", HttpMethod.POST, "http://example.com",
                null, null, null, null, null, null, null,
                null, null, null,
                null, null, null, null, null, null,
                null, null
        );

        assertEquals("schema", command.name());
        assertEquals(HttpMethod.POST, command.method());
        assertEquals("http://example.com", command.url());
        assertNull(command.headers());
        assertNull(command.authType());
        assertNull(command.paginationType());
        assertNull(command.fields());
    }
}
