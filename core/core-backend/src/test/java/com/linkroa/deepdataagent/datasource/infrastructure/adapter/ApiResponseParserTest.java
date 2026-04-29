package com.linkroa.deepdataagent.datasource.infrastructure.adapter;

import com.linkroa.deepdataagent.datasource.controller.response.ParsedFieldResponse;
import com.linkroa.deepdataagent.datasource.domain.model.ApiField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseParserTest {

    private ApiResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new ApiResponseParser();
    }

    @Test
    void should_parseNestedObjectFields_when_parseFields_given_objectContainingNestedMap() {
        String jsonResponse = "{\"id\":1,\"name\":\"test\",\"address\":{\"city\":\"上海\",\"street\":\"南京路\"}}";
        List<ApiField> result = parser.parseFields(jsonResponse, "$");

        assertEquals(5, result.size());

        boolean hasCity = result.stream().anyMatch(f -> "city".equals(f.originalName()) && "$.address.city".equals(f.jsonPath()));
        boolean hasStreet = result.stream().anyMatch(f -> "street".equals(f.originalName()) && "$.address.street".equals(f.jsonPath()));

        assertTrue(hasCity);
        assertTrue(hasStreet);
    }

    @Test
    void should_parseNestedArrayFields_when_parseFields_given_objectContainingArrayOfMaps() {
        String jsonResponse = "{\"id\":1,\"items\":[{\"sku\":\"A001\",\"price\":99.9},{\"sku\":\"A002\",\"price\":199.9}]}";
        List<ApiField> result = parser.parseFields(jsonResponse, "$");

        assertTrue(result.stream().anyMatch(f -> "sku".equals(f.originalName()) && "$.items.*.sku".equals(f.jsonPath())));
        assertTrue(result.stream().anyMatch(f -> "price".equals(f.originalName()) && "$.items.*.price".equals(f.jsonPath())));
    }

    @Test
    void should_parseDeepNestedFields_when_parseFields_given_multiLevelNestedStructure() {
        String jsonResponse = "{\"data\":{\"user\":{\"profile\":{\"age\":25,\"avatar\":\"url\"}}}}";
        List<ApiField> result = parser.parseFields(jsonResponse, "$");

        assertTrue(result.stream().anyMatch(f -> "data".equals(f.originalName()) && "$.data".equals(f.jsonPath())));
        assertTrue(result.stream().anyMatch(f -> "user".equals(f.originalName()) && "$.data.user".equals(f.jsonPath())));
        assertTrue(result.stream().anyMatch(f -> "profile".equals(f.originalName()) && "$.data.user.profile".equals(f.jsonPath())));
        assertTrue(result.stream().anyMatch(f -> "age".equals(f.originalName()) && "$.data.user.profile.age".equals(f.jsonPath())));
        assertTrue(result.stream().anyMatch(f -> "avatar".equals(f.originalName()) && "$.data.user.profile.avatar".equals(f.jsonPath())));
    }

    @Test
    void should_parseNestedArrayInsideObject_when_parseFields_given_complexNestedStructure() {
        String jsonResponse = "{\"orders\":[{\"orderId\":\"O001\",\"items\":[{\"name\":\"商品A\",\"quantity\":2}]}]}";
        List<ApiField> result = parser.parseFields(jsonResponse, "$");

        assertTrue(result.stream().anyMatch(f -> "orders".equals(f.originalName()) && "$.orders".equals(f.jsonPath())));
        assertTrue(result.stream().anyMatch(f -> "orderId".equals(f.originalName()) && "$.orders.*.orderId".equals(f.jsonPath())));
        assertTrue(result.stream().anyMatch(f -> "items".equals(f.originalName()) && "$.orders.*.items".equals(f.jsonPath())));
    }

    @Test
    void should_returnFieldList_when_parseFields_given_arrayRootPath() {
        String jsonResponse = "{\"showapi_res_code\":0,\"showapi_res_body\":{\"list\":[{\"time\":\"14:26:57\",\"code\":\"AED\",\"name\":\"阿联酋迪拉姆\"},{\"time\":\"14:27:00\",\"code\":\"AUD\",\"name\":\"澳大利亚元\"}]}}";
        String rootPath = "$.showapi_res_body.list.*";

        List<ApiField> result = parser.parseFields(jsonResponse, rootPath);

        assertNotNull(result);
        assertEquals(3, result.size());

        ApiField timeField = result.stream().filter(f -> "time".equals(f.originalName())).findFirst().orElseThrow();
        assertEquals("$.showapi_res_body.list.*.time", timeField.jsonPath());
        assertEquals("string", timeField.fieldType());

        ApiField codeField = result.stream().filter(f -> "code".equals(f.originalName())).findFirst().orElseThrow();
        assertEquals("$.showapi_res_body.list.*.code", codeField.jsonPath());
        assertEquals("string", codeField.fieldType());
    }

    @Test
    void should_returnFieldList_when_parseFields_given_mapRootPath() {
        String jsonResponse = "{\"city\":\"上海市\",\"temperature\":25,\"isRaining\":false,\"forecast\":{\"day\":\"2024-01-01\"}}";
        String rootPath = "$";

        List<ApiField> result = parser.parseFields(jsonResponse, rootPath);

        assertNotNull(result);
        assertEquals(5, result.size());

        ApiField cityField = result.stream().filter(f -> "city".equals(f.originalName())).findFirst().orElseThrow();
        assertEquals("$.city", cityField.jsonPath());
        assertEquals("string", cityField.fieldType());

        ApiField forecastField = result.stream().filter(f -> "forecast".equals(f.originalName())).findFirst().orElseThrow();
        assertEquals("$.forecast", forecastField.jsonPath());
        assertEquals("object", forecastField.fieldType());

        ApiField dayField = result.stream().filter(f -> "day".equals(f.originalName())).findFirst().orElseThrow();
        assertEquals("$.forecast.day", dayField.jsonPath());
        assertEquals("string", dayField.fieldType());
    }

    @Test
    void should_returnEmptyList_when_parseFields_given_emptyArray() {
        String jsonResponse = "{\"data\":[]}";
        String rootPath = "$.data";

        List<ApiField> result = parser.parseFields(jsonResponse, rootPath);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void should_throwException_when_parseFields_given_invalidRootPathType() {
        String jsonResponse = "{\"count\":100}";
        String rootPath = "$.count";

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parseFields(jsonResponse, rootPath));

        assertTrue(exception.getMessage().contains("根路径必须指向Map或Array类型"));
    }

    @Test
    void should_throwException_when_parseFields_given_nullResponse() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parseFields(null, "$"));
    }

    @Test
    void should_throwException_when_parseFields_given_emptyResponse() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parseFields("", "$"));
    }

    @Test
    void should_throwException_when_parseFields_given_nullRootPath() {
        String jsonResponse = "{\"data\":[]}";
        assertThrows(IllegalArgumentException.class,
                () -> parser.parseFields(jsonResponse, null));
    }

    @Test
    void should_throwException_when_parseFields_given_emptyRootPath() {
        String jsonResponse = "{\"data\":[]}";
        assertThrows(IllegalArgumentException.class,
                () -> parser.parseFields(jsonResponse, ""));
    }

    @Test
    void should_inferStringType_when_fieldValueIsString() {
        String jsonResponse = "{\"name\":\"test\"}";
        List<ApiField> result = parser.parseFields(jsonResponse, "$");

        assertEquals("string", result.stream().findFirst().orElseThrow().fieldType());
    }

    @Test
    void should_inferNumberType_when_fieldValueIsInteger() {
        String jsonResponse = "{\"count\":100}";
        List<ApiField> result = parser.parseFields(jsonResponse, "$");

        assertEquals("number", result.stream().findFirst().orElseThrow().fieldType());
    }

    @Test
    void should_inferNumberType_when_fieldValueIsDouble() {
        String jsonResponse = "{\"price\":19.99}";
        List<ApiField> result = parser.parseFields(jsonResponse, "$");

        assertEquals("number", result.stream().findFirst().orElseThrow().fieldType());
    }

    @Test
    void should_inferBooleanType_when_fieldValueIsBoolean() {
        String jsonResponse = "{\"active\":true}";
        List<ApiField> result = parser.parseFields(jsonResponse, "$");

        assertEquals("boolean", result.stream().findFirst().orElseThrow().fieldType());
    }

    @Test
    void should_inferObjectType_when_fieldValueIsMap() {
        String jsonResponse = "{\"info\":{\"key\":\"value\"}}";
        List<ApiField> result = parser.parseFields(jsonResponse, "$");

        assertEquals("object", result.stream().findFirst().orElseThrow().fieldType());
    }

    @Test
    void should_inferArrayType_when_fieldValueIsList() {
        String jsonResponse = "{\"items\":[1,2,3]}";
        List<ApiField> result = parser.parseFields(jsonResponse, "$");

        assertEquals("array", result.stream().findFirst().orElseThrow().fieldType());
    }

    @Test
    void should_inferStringType_when_fieldValueIsNull() {
        String jsonResponse = "{\"value\":null}";
        List<ApiField> result = parser.parseFields(jsonResponse, "$");

        assertEquals("string", result.stream().findFirst().orElseThrow().fieldType());
    }

    @Test
    void should_returnFieldList_when_parseFieldsFromData_given_parsedMapData() {
        Map<String, Object> data = Map.of("id", 1, "name", "test");
        List<ApiField> result = parser.parseFieldsFromData(data, "$");

        assertEquals(2, result.size());
    }

    @Test
    void should_returnFieldList_when_parseFieldsFromData_given_parsedListData() {
        List<Map<String, Object>> data = List.of(
                Map.of("id", 1, "name", "test1"),
                Map.of("id", 2, "name", "test2")
        );
        List<ApiField> result = parser.parseFieldsFromData(data, "$.*");

        assertEquals(2, result.size());
    }

    @Test
    void should_returnEmptyList_when_parseFieldsFromData_given_nullData() {
        List<ApiField> result = parser.parseFieldsFromData(null, "$");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void should_throwException_when_parseFieldsFromData_given_nullRootPath() {
        Map<String, Object> data = Map.of("key", "value");
        assertThrows(IllegalArgumentException.class,
                () -> parser.parseFieldsFromData(data, null));
    }

    @Test
    void should_buildCorrectJsonPath_when_arrayRootPath() {
        String jsonResponse = "{\"list\":[{\"id\":1}]}";
        List<ApiField> result = parser.parseFields(jsonResponse, "$.list.*");

        assertTrue(result.stream().allMatch(f -> f.jsonPath().startsWith("$.list.*.")));
    }

    @Test
    void should_buildCorrectJsonPath_when_mapRootPath() {
        String jsonResponse = "{\"data\":{\"id\":1,\"name\":\"test\"}}";
        List<ApiField> result = parser.parseFields(jsonResponse, "$.data");

        assertTrue(result.stream().allMatch(f -> f.jsonPath().startsWith("$.data.")));
    }

    // ===== parseFieldsAsTree tests =====

    @Test
    void should_returnTreeWithChildren_when_parseFieldsAsTree_given_nestedObject() {
        String jsonResponse = "{\"city\":\"上海\",\"forecast\":{\"day\":\"2024-01-01\",\"temp\":25}}";
        List<ParsedFieldResponse> result = parser.parseFieldsAsTree(jsonResponse, "$");

        assertEquals(2, result.size());

        ParsedFieldResponse cityField = result.stream()
                .filter(f -> "city".equals(f.originalName())).findFirst().orElseThrow();
        assertEquals("string", cityField.fieldType());
        assertTrue(cityField.children().isEmpty());

        ParsedFieldResponse forecastField = result.stream()
                .filter(f -> "forecast".equals(f.originalName())).findFirst().orElseThrow();
        assertEquals("object", forecastField.fieldType());
        assertEquals(2, forecastField.children().size());

        ParsedFieldResponse dayField = forecastField.children().stream()
                .filter(f -> "day".equals(f.originalName())).findFirst().orElseThrow();
        assertEquals("$.forecast.day", dayField.jsonPath());
        assertEquals("string", dayField.fieldType());
        assertTrue(dayField.children().isEmpty());
    }

    @Test
    void should_returnTreeWithArrayChildren_when_parseFieldsAsTree_given_arrayOfObjects() {
        String jsonResponse = "{\"items\":[{\"sku\":\"A001\",\"price\":99.9}]}";
        List<ParsedFieldResponse> result = parser.parseFieldsAsTree(jsonResponse, "$");

        assertEquals(1, result.size());
        ParsedFieldResponse itemsField = result.getFirst();
        assertEquals("array", itemsField.fieldType());
        assertEquals(2, itemsField.children().size());

        ParsedFieldResponse skuField = itemsField.children().stream()
                .filter(f -> "sku".equals(f.originalName())).findFirst().orElseThrow();
        assertEquals("$.items.*.sku", skuField.jsonPath());
    }

    @Test
    void should_returnDeepNestedTree_when_parseFieldsAsTree_given_multiLevelStructure() {
        String jsonResponse = "{\"data\":{\"user\":{\"profile\":{\"age\":25}}}}";
        List<ParsedFieldResponse> result = parser.parseFieldsAsTree(jsonResponse, "$");

        assertEquals(1, result.size());
        ParsedFieldResponse dataField = result.getFirst();
        assertEquals("object", dataField.fieldType());
        assertEquals(1, dataField.children().size());

        ParsedFieldResponse userField = dataField.children().getFirst();
        assertEquals("$.data.user", userField.jsonPath());
        assertEquals(1, userField.children().size());

        ParsedFieldResponse profileField = userField.children().getFirst();
        assertEquals("$.data.user.profile", profileField.jsonPath());
        assertEquals(1, profileField.children().size());

        ParsedFieldResponse ageField = profileField.children().getFirst();
        assertEquals("$.data.user.profile.age", ageField.jsonPath());
        assertEquals("number", ageField.fieldType());
        assertTrue(ageField.children().isEmpty());
    }

    @Test
    void should_returnEmptyTree_when_parseFieldsAsTree_given_emptyArray() {
        String jsonResponse = "{\"data\":[]}";
        List<ParsedFieldResponse> result = parser.parseFieldsAsTree(jsonResponse, "$.data");

        assertTrue(result.isEmpty());
    }

    @Test
    void should_returnTreeFromArrayRoot_when_parseFieldsAsTree_given_arrayRootPath() {
        String jsonResponse = "{\"list\":[{\"time\":\"14:26\",\"code\":\"AED\"}]}";
        List<ParsedFieldResponse> result = parser.parseFieldsAsTree(jsonResponse, "$.list.*");

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(f -> f.children().isEmpty()));
    }
}