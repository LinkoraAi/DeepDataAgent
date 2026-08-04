package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.dto.ModelInfoDTO;
import com.linkroa.deepdataagent.agent.application.dto.ModelProviderDTO;
import com.linkroa.deepdataagent.agent.domain.model.AgentModelInfo;
import com.linkroa.deepdataagent.agent.domain.model.TestConnectionResult;
import com.linkroa.deepdataagent.agent.domain.repository.AgentModelInfoRepository;
import com.linkroa.deepdataagent.agent.infrastructure.client.LLMClient;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ModelConfigApplicationService 单元测试
 * <p>测试模型配置应用服务的核心业务逻辑，包括查询、添加、更新、删除、设置默认模型等操作。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class ModelConfigApplicationServiceTest {

    @Mock
    private AgentModelInfoRepository modelInfoRepository;

    @Mock
    private LLMClient llmClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    private ModelConfigApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ModelConfigApplicationService(modelInfoRepository, llmClient, transactionTemplate);
        // 模拟编程式事务：直接执行回调，不实际开启事务（部分只读测试不触发，故 lenient）
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    // ==================== listAllEnabled ====================

    @Test
    void should_returnAllEnabledModels_when_listAllEnabled_given_enabledModelsExist() {
        // given
        AgentModelInfo model1 = createAgentModelInfo(1L, "model1", true);
        AgentModelInfo model2 = createAgentModelInfo(2L, "model2", true);
        when(modelInfoRepository.findAllEnabled()).thenReturn(List.of(model1, model2));

        // when
        List<AgentModelInfo> result = service.listAllEnabled();

        // then
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals(2L, result.get(1).getId());
        verify(modelInfoRepository).findAllEnabled();
    }

    @Test
    void should_returnEmptyList_when_listAllEnabled_given_noEnabledModels() {
        // given
        when(modelInfoRepository.findAllEnabled()).thenReturn(List.of());

        // when
        List<AgentModelInfo> result = service.listAllEnabled();

        // then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==================== getDefaultModel ====================

    @Test
    void should_returnDefaultModel_when_getDefaultModel_given_defaultModelExists() {
        // given
        AgentModelInfo defaultModel = createAgentModelInfo(1L, "default-model", true);
        when(modelInfoRepository.findDefault()).thenReturn(Optional.of(defaultModel));

        // when
        AgentModelInfo result = service.getDefaultModel();

        // then
        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("default-model", result.getModelId());
        verify(modelInfoRepository).findDefault();
    }

    @Test
    void should_throwException_when_getDefaultModel_given_noDefaultModel() {
        // given
        when(modelInfoRepository.findDefault()).thenReturn(Optional.empty());

        // when & then
        DeepDataAgentException exception = assertThrows(
                DeepDataAgentException.class,
                () -> service.getDefaultModel()
        );
        assertTrue(exception.getMessage().contains("未配置默认模型"));
    }

    // ==================== getModelById ====================

    @Test
    void should_returnModel_when_getModelById_given_modelExists() {
        // given
        Long modelId = 1L;
        AgentModelInfo model = createAgentModelInfo(modelId, "test-model", true);
        when(modelInfoRepository.findById(modelId)).thenReturn(Optional.of(model));

        // when
        AgentModelInfo result = service.getModelById(modelId);

        // then
        assertNotNull(result);
        assertEquals(modelId, result.getId());
        assertEquals("test-model", result.getModelId());
    }

    @Test
    void should_throwException_when_getModelById_given_modelNotExists() {
        // given
        Long modelId = 999L;
        when(modelInfoRepository.findById(modelId)).thenReturn(Optional.empty());

        // when & then
        DeepDataAgentException exception = assertThrows(
                DeepDataAgentException.class,
                () -> service.getModelById(modelId)
        );
        assertTrue(exception.getMessage().contains("模型配置不存在"));
    }

    // ==================== deleteConfig ====================

    @Test
    void should_deleteModelAndSelectNewDefault_when_deleteConfig_given_defaultModelDeleted() {
        // given
        Long modelId = 1L;
        AgentModelInfo deletedModel = createAgentModelInfo(modelId, "deleted-model", true);
        deletedModel.setDefaultModel(1);

        AgentModelInfo newDefaultModel = createAgentModelInfo(2L, "new-default", true);

        when(modelInfoRepository.findById(modelId)).thenReturn(Optional.of(deletedModel));
        when(modelInfoRepository.findAllEnabled()).thenReturn(List.of(newDefaultModel));

        // when
        service.deleteConfig(modelId);

        // then
        verify(modelInfoRepository).markDeleted(modelId);
        verify(modelInfoRepository).update(newDefaultModel);
        assertEquals(1, newDefaultModel.getDefaultModel());
        verify(llmClient).evictCache(modelId);
    }

    @Test
    void should_deleteModelWithoutSelectingNewDefault_when_deleteConfig_given_nonDefaultModelDeleted() {
        // given
        Long modelId = 1L;
        AgentModelInfo deletedModel = createAgentModelInfo(modelId, "deleted-model", true);
        deletedModel.setDefaultModel(0);

        when(modelInfoRepository.findById(modelId)).thenReturn(Optional.of(deletedModel));

        // when
        service.deleteConfig(modelId);

        // then
        verify(modelInfoRepository).markDeleted(modelId);
        verify(modelInfoRepository, never()).findAllEnabled();
        verify(modelInfoRepository, never()).update(any());
        verify(llmClient).evictCache(modelId);
    }

    @Test
    void should_throwException_when_deleteConfig_given_modelNotExists() {
        // given
        Long modelId = 999L;
        when(modelInfoRepository.findById(modelId)).thenReturn(Optional.empty());

        // when & then
        DeepDataAgentException exception = assertThrows(
                DeepDataAgentException.class,
                () -> service.deleteConfig(modelId)
        );
        assertTrue(exception.getMessage().contains("模型配置不存在"));
        verify(modelInfoRepository, never()).markDeleted(any());
    }

    // ==================== listProviders ====================

    @Test
    void should_returnProviderDTOs_when_listProviders_given_providersExist() {
        // given
        AgentModelInfo provider1 = createAgentModelInfo(1L, "model1", true);
        provider1.setProviderDisplayName("阿里百炼");
        provider1.setProviderName("dashscope");
        provider1.setApiUrl("https://dashscope.aliyuncs.com");
        AgentModelInfo provider2 = createAgentModelInfo(2L, "model2", true);
        provider2.setProviderDisplayName("DeepSeek");
        provider2.setProviderName("deepseek");
        provider2.setApiUrl("https://api.deepseek.com");
        when(modelInfoRepository.findDistinctProviders()).thenReturn(List.of(provider1, provider2));

        // when
        List<ModelProviderDTO> result = service.listProviders();

        // then
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).id());
        assertEquals("阿里百炼", result.get(0).providerDisplayName());
        assertEquals("dashscope", result.get(0).providerName());
        assertEquals("https://dashscope.aliyuncs.com", result.get(0).apiUrl());
        assertEquals(2L, result.get(1).id());
        verify(modelInfoRepository).findDistinctProviders();
    }

    @Test
    void should_returnEmptyList_when_listProviders_given_noProviders() {
        // given
        when(modelInfoRepository.findDistinctProviders()).thenReturn(List.of());

        // when
        List<ModelProviderDTO> result = service.listProviders();

        // then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==================== getModelsByProvider ====================

    @Test
    void should_returnAvailableModelDTOs_when_getModelsByProvider_given_modelsExist() {
        // given
        AgentModelInfo available = createAgentModelInfo(1L, "qwen-plus", true);
        available.setProviderDisplayName("阿里百炼");
        AgentModelInfo unavailable = createAgentModelInfo(2L, "qwen-max", false);
        unavailable.setProviderDisplayName("阿里百炼");
        when(modelInfoRepository.findByProviderName("dashscope")).thenReturn(List.of(available, unavailable));

        // when
        List<ModelInfoDTO> result = service.getModelsByProvider("dashscope");

        // then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
        assertEquals("qwen-plus", result.get(0).modelKey());
        assertEquals("阿里百炼 - qwen-plus", result.get(0).displayName());
        verify(modelInfoRepository).findByProviderName("dashscope");
    }

    @Test
    void should_returnEmptyList_when_getModelsByProvider_given_noAvailableModels() {
        // given
        AgentModelInfo unavailable = createAgentModelInfo(1L, "qwen-max", false);
        when(modelInfoRepository.findByProviderName("dashscope")).thenReturn(List.of(unavailable));

        // when
        List<ModelInfoDTO> result = service.getModelsByProvider("dashscope");

        // then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void should_returnEmptyList_when_getModelsByProvider_given_providerNotFound() {
        // given
        when(modelInfoRepository.findByProviderName("unknown")).thenReturn(List.of());

        // when
        List<ModelInfoDTO> result = service.getModelsByProvider("unknown");

        // then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==================== testConnection ====================

    @Test
    void should_returnAvailableResult_when_testConnection_given_success() {
        // given
        AgentModelInfo model = createAgentModelInfo(1L, "test-model", true);
        when(modelInfoRepository.findById(1L)).thenReturn(Optional.of(model));
        when(llmClient.testConnection(1L)).thenReturn(new TestConnectionResult(true, "连接成功，模型可用", 150L));

        // when
        TestConnectionResult result = service.testConnection(1L);

        // then
        assertNotNull(result);
        assertTrue(result.available());
        assertEquals("连接成功，模型可用", result.message());
        assertEquals(150L, result.responseTime());
        verify(llmClient).testConnection(1L);
    }

    @Test
    void should_returnCooldownResult_when_testConnection_given_secondCallWithinCooldown() {
        // given
        AgentModelInfo model = createAgentModelInfo(1L, "test-model", true);
        when(modelInfoRepository.findById(1L)).thenReturn(Optional.of(model));
        when(llmClient.testConnection(1L)).thenReturn(new TestConnectionResult(true, "连接成功，模型可用", 150L));

        // when
        service.testConnection(1L);
        TestConnectionResult result = service.testConnection(1L);

        // then
        assertNotNull(result);
        assertFalse(result.available());
        assertTrue(result.message().contains("测试过于频繁"));
        verify(llmClient, times(1)).testConnection(1L);
    }

    @Test
    void should_throwException_when_testConnection_given_modelNotExists() {
        // given
        when(modelInfoRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        DeepDataAgentException exception = assertThrows(
                DeepDataAgentException.class,
                () -> service.testConnection(999L)
        );
        assertTrue(exception.getMessage().contains("模型配置不存在"));
        verify(llmClient, never()).testConnection(any());
    }

    // ==================== 辅助方法 ====================

    private AgentModelInfo createAgentModelInfo(Long id, String modelId, boolean enabled) {
        AgentModelInfo info = new AgentModelInfo();
        info.setId(id);
        info.setProviderDisplayName("Test Provider");
        info.setProviderName("test-provider");
        info.setModelId(modelId);
        info.setApiUrl("https://api.test.com");
        info.setApiKey("test-api-key");
        info.setEnabled(enabled ? 1 : 0);
        info.setDefaultModel(0);
        info.setSortOrder(0);
        info.setDeleted(0);
        return info;
    }
}
