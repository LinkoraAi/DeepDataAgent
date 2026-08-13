package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.dto.ModelInfoDTO;
import com.linkroa.deepdataagent.agent.application.dto.ModelProviderDTO;
import com.linkroa.deepdataagent.agent.domain.model.ModelConfig;
import com.linkroa.deepdataagent.agent.domain.model.TestConnectionResult;
import com.linkroa.deepdataagent.agent.domain.repository.ModelConfigRepository;
import com.linkroa.deepdataagent.agent.infrastructure.client.ChatModelManager;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.infrastructure.redis.port.DistributedLock;
import com.linkroa.deepdataagent.shared.infrastructure.redis.port.DistributedLockPort;
import com.linkroa.deepdataagent.shared.infrastructure.redis.port.RateLimiterPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
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
    private ModelConfigRepository modelConfigRepository;

    @Mock
    private ChatModelManager chatModelManager;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private RateLimiterPort rateLimiterPort;

    @Mock
    private DistributedLockPort distributedLockPort;

    private ModelConfigApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ModelConfigApplicationService(
                modelConfigRepository, chatModelManager, transactionTemplate, rateLimiterPort, distributedLockPort);
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
        ModelConfig model1 = createAgentModelInfo(1L, "model1", true);
        ModelConfig model2 = createAgentModelInfo(2L, "model2", true);
        when(modelConfigRepository.findAllEnabled()).thenReturn(List.of(model1, model2));

        // when
        List<ModelConfig> result = service.listAllEnabled();

        // then
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals(2L, result.get(1).getId());
        verify(modelConfigRepository).findAllEnabled();
    }

    @Test
    void should_returnEmptyList_when_listAllEnabled_given_noEnabledModels() {
        // given
        when(modelConfigRepository.findAllEnabled()).thenReturn(List.of());

        // when
        List<ModelConfig> result = service.listAllEnabled();

        // then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==================== getDefaultModel ====================

    @Test
    void should_returnDefaultModel_when_getDefaultModel_given_defaultModelExists() {
        // given
        ModelConfig defaultModel = createAgentModelInfo(1L, "default-model", true);
        when(modelConfigRepository.findDefault()).thenReturn(Optional.of(defaultModel));

        // when
        ModelConfig result = service.getDefaultModel();

        // then
        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("default-model", result.getModelId());
        verify(modelConfigRepository).findDefault();
    }

    @Test
    void should_throwException_when_getDefaultModel_given_noDefaultModel() {
        // given
        when(modelConfigRepository.findDefault()).thenReturn(Optional.empty());

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
        ModelConfig model = createAgentModelInfo(modelId, "test-model", true);
        when(modelConfigRepository.findById(modelId)).thenReturn(Optional.of(model));

        // when
        ModelConfig result = service.getModelById(modelId);

        // then
        assertNotNull(result);
        assertEquals(modelId, result.getId());
        assertEquals("test-model", result.getModelId());
    }

    @Test
    void should_throwException_when_getModelById_given_modelNotExists() {
        // given
        Long modelId = 999L;
        when(modelConfigRepository.findById(modelId)).thenReturn(Optional.empty());

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
        ModelConfig deletedModel = createAgentModelInfo(modelId, "deleted-model", true);
        deletedModel.setDefaultModel(1);

        ModelConfig newDefaultModel = createAgentModelInfo(2L, "new-default", true);

        when(modelConfigRepository.findById(modelId)).thenReturn(Optional.of(deletedModel));
        when(modelConfigRepository.findAllEnabled()).thenReturn(List.of(newDefaultModel));

        // when
        service.deleteConfig(modelId);

        // then
        verify(modelConfigRepository).markDeleted(modelId);
        verify(modelConfigRepository).update(newDefaultModel);
        assertEquals(1, newDefaultModel.getDefaultModel());
        verify(chatModelManager).evictCache(modelId);
    }

    @Test
    void should_deleteModelWithoutSelectingNewDefault_when_deleteConfig_given_nonDefaultModelDeleted() {
        // given
        Long modelId = 1L;
        ModelConfig deletedModel = createAgentModelInfo(modelId, "deleted-model", true);
        deletedModel.setDefaultModel(0);

        when(modelConfigRepository.findById(modelId)).thenReturn(Optional.of(deletedModel));

        // when
        service.deleteConfig(modelId);

        // then
        verify(modelConfigRepository).markDeleted(modelId);
        verify(modelConfigRepository, never()).findAllEnabled();
        verify(modelConfigRepository, never()).update(any());
        verify(chatModelManager).evictCache(modelId);
    }

    @Test
    void should_throwException_when_deleteConfig_given_modelNotExists() {
        // given
        Long modelId = 999L;
        when(modelConfigRepository.findById(modelId)).thenReturn(Optional.empty());

        // when & then
        DeepDataAgentException exception = assertThrows(
                DeepDataAgentException.class,
                () -> service.deleteConfig(modelId)
        );
        assertTrue(exception.getMessage().contains("模型配置不存在"));
        verify(modelConfigRepository, never()).markDeleted(any());
    }

    // ==================== listProviders ====================

    @Test
    void should_returnProviderDTOs_when_listProviders_given_providersExist() {
        // given
        ModelConfig provider1 = createAgentModelInfo(1L, "model1", true);
        provider1.setProviderDisplayName("阿里百炼");
        provider1.setProviderName("dashscope");
        provider1.setApiUrl("https://dashscope.aliyuncs.com");
        ModelConfig provider2 = createAgentModelInfo(2L, "model2", true);
        provider2.setProviderDisplayName("DeepSeek");
        provider2.setProviderName("deepseek");
        provider2.setApiUrl("https://api.deepseek.com");
        when(modelConfigRepository.findProviders()).thenReturn(List.of(provider1, provider2));

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
        verify(modelConfigRepository).findProviders();
    }

    @Test
    void should_returnEmptyList_when_listProviders_given_noProviders() {
        // given
        when(modelConfigRepository.findProviders()).thenReturn(List.of());

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
        ModelConfig available = createAgentModelInfo(1L, "qwen-plus", true);
        available.setProviderDisplayName("阿里百炼");
        ModelConfig unavailable = createAgentModelInfo(2L, "qwen-max", false);
        unavailable.setProviderDisplayName("阿里百炼");
        when(modelConfigRepository.findByProviderName("dashscope")).thenReturn(List.of(available, unavailable));

        // when
        List<ModelInfoDTO> result = service.getModelsByProvider("dashscope");

        // then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
        assertEquals("qwen-plus", result.get(0).modelKey());
        assertEquals("阿里百炼 - qwen-plus", result.get(0).displayName());
        verify(modelConfigRepository).findByProviderName("dashscope");
    }

    @Test
    void should_returnEmptyList_when_getModelsByProvider_given_noAvailableModels() {
        // given
        ModelConfig unavailable = createAgentModelInfo(1L, "qwen-max", false);
        when(modelConfigRepository.findByProviderName("dashscope")).thenReturn(List.of(unavailable));

        // when
        List<ModelInfoDTO> result = service.getModelsByProvider("dashscope");

        // then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void should_returnEmptyList_when_getModelsByProvider_given_providerNotFound() {
        // given
        when(modelConfigRepository.findByProviderName("unknown")).thenReturn(List.of());

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
        ModelConfig model = createAgentModelInfo(1L, "test-model", true);
        when(modelConfigRepository.findById(1L)).thenReturn(Optional.of(model));
        when(rateLimiterPort.tryAcquire("dd:ratelimit:model-test:1")).thenReturn(true);
        when(chatModelManager.testConnection(1L)).thenReturn(new TestConnectionResult(true, "连接成功，模型可用", 150L));

        // when
        TestConnectionResult result = service.testConnection(1L);

        // then
        assertNotNull(result);
        assertTrue(result.available());
        assertEquals("连接成功，模型可用", result.message());
        assertEquals(150L, result.responseTime());
        verify(chatModelManager).testConnection(1L);
    }

    @Test
    void should_returnCooldownResult_when_testConnection_given_secondCallWithinCooldown() {
        // given - 首次放行、第二次被限流拦截
        ModelConfig model = createAgentModelInfo(1L, "test-model", true);
        when(modelConfigRepository.findById(1L)).thenReturn(Optional.of(model));
        when(rateLimiterPort.tryAcquire("dd:ratelimit:model-test:1")).thenReturn(true, false);
        when(chatModelManager.testConnection(1L)).thenReturn(new TestConnectionResult(true, "连接成功，模型可用", 150L));

        // when
        service.testConnection(1L);
        TestConnectionResult result = service.testConnection(1L);

        // then
        assertNotNull(result);
        assertFalse(result.available());
        assertTrue(result.message().contains("测试过于频繁"));
        verify(chatModelManager, times(1)).testConnection(1L);
    }

    @Test
    void should_throwException_when_testConnection_given_modelNotExists() {
        // given
        when(modelConfigRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        DeepDataAgentException exception = assertThrows(
                DeepDataAgentException.class,
                () -> service.testConnection(999L)
        );
        assertTrue(exception.getMessage().contains("模型配置不存在"));
        verify(chatModelManager, never()).testConnection(any());
        verify(rateLimiterPort, never()).tryAcquire(any());
    }

    // ==================== setDefaultModel ====================

    @Test
    void should_setDefaultModel_when_setDefaultModel_given_lockAcquired() {
        // given - 获取分布式锁成功，事务内设置默认
        ModelConfig model = createAgentModelInfo(1L, "test-model", true);
        DistributedLock lock = mock(DistributedLock.class);
        when(distributedLockPort.tryLock("dd:lock:default-model", Duration.ofSeconds(10)))
                .thenReturn(Optional.of(lock));
        when(modelConfigRepository.findById(1L)).thenReturn(Optional.of(model));
        when(modelConfigRepository.findAllEnabled()).thenReturn(List.of(model));

        // when
        service.setDefaultModel(1L);

        // then - 默认标记被更新，锁随 try-with-resources 释放
        assertEquals(1, model.getDefaultModel());
        verify(modelConfigRepository).update(model);
        verify(lock).close();
    }

    @Test
    void should_throwException_when_setDefaultModel_given_lockNotAcquired() {
        // given - 锁被其他持有者占用
        when(distributedLockPort.tryLock("dd:lock:default-model", Duration.ofSeconds(10)))
                .thenReturn(Optional.empty());

        // when & then
        DeepDataAgentException exception = assertThrows(
                DeepDataAgentException.class,
                () -> service.setDefaultModel(1L)
        );
        assertTrue(exception.getMessage().contains("系统繁忙"));
        verify(modelConfigRepository, never()).findById(any());
    }

    @Test
    void should_throwException_when_setDefaultModel_given_modelNotExistsWithinLock() {
        // given - 已获取锁但模型配置不存在
        DistributedLock lock = mock(DistributedLock.class);
        when(distributedLockPort.tryLock("dd:lock:default-model", Duration.ofSeconds(10)))
                .thenReturn(Optional.of(lock));
        when(modelConfigRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        DeepDataAgentException exception = assertThrows(
                DeepDataAgentException.class,
                () -> service.setDefaultModel(999L)
        );
        assertTrue(exception.getMessage().contains("模型配置不存在"));
        // 异常后锁仍须释放
        verify(lock).close();
    }

    // ==================== 辅助方法 ====================

    private ModelConfig createAgentModelInfo(Long id, String modelId, boolean enabled) {
        ModelConfig info = new ModelConfig();
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
