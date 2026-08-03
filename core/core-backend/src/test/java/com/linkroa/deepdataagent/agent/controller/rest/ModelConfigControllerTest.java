package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.command.AddModelConfigCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateModelConfigCommand;
import com.linkroa.deepdataagent.agent.application.dto.ModelConfigDTO;
import com.linkroa.deepdataagent.agent.application.dto.ModelInfoDTO;
import com.linkroa.deepdataagent.agent.application.dto.ModelProviderDTO;
import com.linkroa.deepdataagent.agent.application.service.ModelConfigApplicationService;
import com.linkroa.deepdataagent.agent.controller.request.AddModelConfigRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateModelConfigRequest;
import com.linkroa.deepdataagent.agent.controller.response.ModelConfigResponse;
import com.linkroa.deepdataagent.agent.controller.response.ModelInfoResponse;
import com.linkroa.deepdataagent.agent.controller.response.ModelProviderResponse;
import com.linkroa.deepdataagent.agent.domain.model.TestConnectionResult;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ModelConfigController 单元测试
 * <p>测试模型配置 REST 接口的响应封装与委托调用。</p>
 */
@ExtendWith(MockitoExtension.class)
class ModelConfigControllerTest {

    @Mock
    private ModelConfigApplicationService service;

    private ModelConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new ModelConfigController(service);
    }

    // ==================== listProviders ====================

    @Test
    void should_returnProviderList_when_listProviders_given_providersExist() {
        // given
        ModelProviderDTO provider1 = new ModelProviderDTO(
                1L, "阿里云通义", "dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        ModelProviderDTO provider2 = new ModelProviderDTO(
                2L, "OpenAI", "openai", "https://api.openai.com/v1");

        when(service.listProviders()).thenReturn(List.of(provider1, provider2));

        // when
        ApiResponse<List<ModelProviderResponse>> result = controller.listProviders();

        // then
        assertTrue(result.success());
        assertEquals(2, result.data().size());

        ModelProviderResponse first = result.data().get(0);
        assertEquals(1L, first.id());
        assertEquals("阿里云通义", first.name());
        assertEquals("dashscope", first.providerKey());
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", first.baseUrl());

        ModelProviderResponse second = result.data().get(1);
        assertEquals(2L, second.id());
        assertEquals("OpenAI", second.name());
        assertEquals("openai", second.providerKey());
        assertEquals("https://api.openai.com/v1", second.baseUrl());

        verify(service).listProviders();
    }

    @Test
    void should_returnEmptyList_when_listProviders_given_noProviders() {
        // given
        when(service.listProviders()).thenReturn(List.of());

        // when
        ApiResponse<List<ModelProviderResponse>> result = controller.listProviders();

        // then
        assertTrue(result.success());
        assertTrue(result.data().isEmpty());
        verify(service).listProviders();
    }

    // ==================== listModelsByProvider ====================

    @Test
    void should_returnAvailableModels_when_listModelsByProvider_given_validProviderKey() {
        // given
        ModelInfoDTO model1 = new ModelInfoDTO(1L, "qwen-plus", "阿里云通义 - qwen-plus");
        ModelInfoDTO model2 = new ModelInfoDTO(2L, "qwen-max", "阿里云通义 - qwen-max");

        when(service.getModelsByProvider("dashscope")).thenReturn(List.of(model1, model2));

        // when
        ApiResponse<List<ModelInfoResponse>> result = controller.listModelsByProvider("dashscope");

        // then
        assertTrue(result.success());
        assertEquals(2, result.data().size());

        ModelInfoResponse first = result.data().get(0);
        assertEquals(1L, first.id());
        assertEquals("qwen-plus", first.modelKey());
        assertEquals("阿里云通义 - qwen-plus", first.displayName());

        verify(service).getModelsByProvider("dashscope");
    }

    // ==================== listConfigs ====================

    @Test
    void should_returnConfigList_when_listConfigs_given_configsExist() {
        // given
        ModelConfigDTO dto1 = new ModelConfigDTO(
                1L, "dashscope", "阿里云通义", "qwen-plus",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "sk-t****5678", "openai", true,
                "2025-01-01 10:00:00", "2025-01-02 10:00:00");

        ModelConfigDTO dto2 = new ModelConfigDTO(
                2L, "openai", "OpenAI", "gpt-4o",
                "https://api.openai.com/v1",
                "sk-a****mnop", "openai", false,
                "2025-02-01 10:00:00", "2025-02-02 10:00:00");

        when(service.listConfigDTOs()).thenReturn(List.of(dto1, dto2));

        // when
        ApiResponse<List<ModelConfigResponse>> result = controller.listConfigs();

        // then
        assertTrue(result.success());
        assertEquals(2, result.data().size());

        ModelConfigResponse first = result.data().get(0);
        assertEquals(1L, first.id());
        assertEquals("dashscope", first.providerKey());
        assertEquals("阿里云通义", first.providerName());
        assertEquals("qwen-plus", first.modelKey());
        assertEquals("sk-t****5678", first.apiKeyMasked());
        assertTrue(first.isDefault());
        assertEquals("2025-01-01 10:00:00", first.createdAt());
        assertEquals("2025-01-02 10:00:00", first.updatedAt());

        ModelConfigResponse second = result.data().get(1);
        assertEquals(2L, second.id());
        assertEquals("openai", second.providerKey());
        assertEquals("OpenAI", second.providerName());
        assertEquals("gpt-4o", second.modelKey());
        assertFalse(second.isDefault());

        verify(service).listConfigDTOs();
    }

    // ==================== getDefaultConfig ====================

    @Test
    void should_returnDefaultConfig_when_getDefaultConfig_given_defaultModelExists() {
        // given
        ModelConfigDTO defaultDto = new ModelConfigDTO(
                1L, "dashscope", "阿里云通义", "qwen-plus",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "sk-t****5678", "openai", true,
                "2025-01-01 10:00:00", "2025-01-02 10:00:00");

        when(service.getDefaultConfigDTO()).thenReturn(defaultDto);

        // when
        ApiResponse<ModelConfigResponse> result = controller.getDefaultConfig();

        // then
        assertTrue(result.success());
        ModelConfigResponse response = result.data();
        assertNotNull(response);
        assertEquals(1L, response.id());
        assertEquals("dashscope", response.providerKey());
        assertEquals("qwen-plus", response.modelKey());
        assertTrue(response.isDefault());
        assertEquals("sk-t****5678", response.apiKeyMasked());

        verify(service).getDefaultConfigDTO();
    }

    @Test
    void should_returnNullData_when_getDefaultConfig_given_noDefaultModel() {
        // given
        when(service.getDefaultConfigDTO()).thenReturn(null);

        // when
        ApiResponse<ModelConfigResponse> result = controller.getDefaultConfig();

        // then
        assertTrue(result.success());
        assertNull(result.data());
        verify(service).getDefaultConfigDTO();
    }

    // ==================== getConfigForEdit ====================

    @Test
    void should_returnConfigForEdit_when_getConfigForEdit_given_validId() {
        // given
        ModelConfigDTO editDto = new ModelConfigDTO(
                1L, "dashscope", "阿里云通义", "qwen-plus",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "sk-test-key-12345678", "openai", true,
                "2025-01-01 10:00:00", "2025-01-02 10:00:00");

        when(service.getConfigForEditDTO(1L)).thenReturn(editDto);

        // when
        ApiResponse<ModelConfigResponse> result = controller.getConfigForEdit(1L);

        // then
        assertTrue(result.success());
        ModelConfigResponse response = result.data();
        assertNotNull(response);
        assertEquals(1L, response.id());
        assertEquals("dashscope", response.providerKey());
        assertEquals("qwen-plus", response.modelKey());
        // doMask=false, so apiKey is not masked
        assertEquals("sk-test-key-12345678", response.apiKeyMasked());

        verify(service).getConfigForEditDTO(1L);
    }

    // ==================== getConfigById ====================

    @Test
    void should_returnConfigById_when_getConfigById_given_validId() {
        // given
        ModelConfigDTO dto = new ModelConfigDTO(
                1L, "dashscope", "阿里云通义", "qwen-plus",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "sk-t****5678", "openai", true,
                "2025-01-01 10:00:00", "2025-01-02 10:00:00");

        when(service.getConfigDTO(1L)).thenReturn(dto);

        // when
        ApiResponse<ModelConfigResponse> result = controller.getConfigById(1L);

        // then
        assertTrue(result.success());
        ModelConfigResponse response = result.data();
        assertNotNull(response);
        assertEquals(1L, response.id());
        assertEquals("dashscope", response.providerKey());
        assertEquals("qwen-plus", response.modelKey());
        // doMask=true (default), so apiKey is masked
        assertEquals("sk-t****5678", response.apiKeyMasked());

        verify(service).getConfigDTO(1L);
    }

    // ==================== addConfig ====================

    @Test
    void should_returnSuccess_when_addConfig_given_validRequest() {
        // given
        AddModelConfigRequest request = new AddModelConfigRequest(
                "dashscope", "qwen-plus",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "openai", "sk-test-key", true);
        AddModelConfigCommand command = new AddModelConfigCommand(
                "dashscope", "qwen-plus",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "openai", "sk-test-key", true);
        doNothing().when(service).addConfig(command);

        // when
        ApiResponse<Void> result = controller.addConfig(request);

        // then
        assertTrue(result.success());
        assertNull(result.data());
        verify(service).addConfig(command);
    }

    // ==================== updateConfig ====================

    @Test
    void should_returnSuccess_when_updateConfig_given_validIdAndRequest() {
        // given
        UpdateModelConfigRequest request = new UpdateModelConfigRequest("sk-new-key", "https://new-url.com/v1");
        UpdateModelConfigCommand command = new UpdateModelConfigCommand("sk-new-key", "https://new-url.com/v1");
        doNothing().when(service).updateConfig(1L, command);

        // when
        ApiResponse<Void> result = controller.updateConfig(1L, request);

        // then
        assertTrue(result.success());
        assertNull(result.data());
        verify(service).updateConfig(1L, command);
    }

    // ==================== deleteConfig ====================

    @Test
    void should_returnSuccess_when_deleteConfig_given_validId() {
        // given
        doNothing().when(service).deleteConfig(1L);

        // when
        ApiResponse<Void> result = controller.deleteConfig(1L);

        // then
        assertTrue(result.success());
        assertNull(result.data());
        verify(service).deleteConfig(1L);
    }

    // ==================== setDefaultModel ====================

    @Test
    void should_returnSuccess_when_setDefaultModel_given_validId() {
        // given
        doNothing().when(service).setDefaultModel(1L);

        // when
        ApiResponse<Void> result = controller.setDefaultModel(1L);

        // then
        assertTrue(result.success());
        assertNull(result.data());
        verify(service).setDefaultModel(1L);
    }

    // ==================== testConnection ====================

    @Test
    void should_returnTestResult_when_testConnection_given_validId() {
        // given
        TestConnectionResult expectedResult = new TestConnectionResult(true, "连接成功", 150L);
        when(service.testConnection(1L)).thenReturn(expectedResult);

        // when
        ApiResponse<TestConnectionResult> result = controller.testConnection(1L);

        // then
        assertTrue(result.success());
        TestConnectionResult testResult = result.data();
        assertNotNull(testResult);
        assertTrue(testResult.available());
        assertEquals("连接成功", testResult.message());
        assertEquals(Long.valueOf(150L), testResult.responseTime());
        verify(service).testConnection(1L);
    }
}