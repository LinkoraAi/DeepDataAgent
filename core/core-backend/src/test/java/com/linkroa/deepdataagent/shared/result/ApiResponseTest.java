package com.linkroa.deepdataagent.shared.result;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTest {

    @Test
    void should_setDefaultCodeAndMessage_when_constructor_given_nullCodeAndMessageWithSuccess() {
        ApiResponse<String> response = new ApiResponse<>(true, null, null, "data");

        assertTrue(response.success());
        assertEquals("200", response.code());
        assertEquals("操作成功", response.message());
        assertEquals("data", response.data());
    }

    @Test
    void should_setDefaultCodeAndMessage_when_constructor_given_nullCodeAndMessageWithFailure() {
        ApiResponse<String> response = new ApiResponse<>(false, null, null, null);

        assertFalse(response.success());
        assertEquals("500", response.code());
        assertEquals("操作失败", response.message());
        assertNull(response.data());
    }

    @Test
    void should_keepCustomCode_when_constructor_given_customCode() {
        ApiResponse<String> response = new ApiResponse<>(true, "999", null, "data");

        assertEquals("999", response.code());
    }

    @Test
    void should_keepCustomCode_when_constructor_given_customCodeWithFailure() {
        ApiResponse<String> response = new ApiResponse<>(false, "400", null, null);

        assertEquals("400", response.code());
    }

    @Test
    void should_setDefaultMessage_when_constructor_given_nullMessageWithSuccess() {
        ApiResponse<String> response = new ApiResponse<>(true, "200", null, "data");

        assertEquals("操作成功", response.message());
    }

    @Test
    void should_setDefaultMessage_when_constructor_given_nullMessageWithFailure() {
        ApiResponse<String> response = new ApiResponse<>(false, "500", null, null);

        assertEquals("操作失败", response.message());
    }

    @Test
    void should_createSuccessResponse_when_successFactory_given_data() {
        ApiResponse<String> response = ApiResponse.success("test-data");

        assertTrue(response.success());
        assertEquals("200", response.code());
        assertEquals("操作成功", response.message());
        assertEquals("test-data", response.data());
    }

    @Test
    void should_createErrorResponse_when_errorFactory_given_codeAndMessage() {
        ApiResponse<String> response = ApiResponse.error("400", "参数错误");

        assertFalse(response.success());
        assertEquals("400", response.code());
        assertEquals("参数错误", response.message());
        assertNull(response.data());
    }

    @Test
    void should_createErrorResponseWithData_when_errorFactory_given_codeMessageAndData() {
        ApiResponse<String> response = ApiResponse.error("500", "服务器错误", "error-detail");

        assertFalse(response.success());
        assertEquals("500", response.code());
        assertEquals("服务器错误", response.message());
        assertEquals("error-detail", response.data());
    }
}
