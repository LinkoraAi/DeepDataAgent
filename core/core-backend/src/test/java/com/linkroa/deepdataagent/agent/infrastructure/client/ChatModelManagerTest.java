package com.linkroa.deepdataagent.agent.infrastructure.client;

import com.linkroa.deepdataagent.agent.domain.model.ModelConfig;
import com.linkroa.deepdataagent.agent.domain.model.TestConnectionResult;
import com.linkroa.deepdataagent.agent.domain.repository.ModelConfigRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.util.PasswordEncryptionUtil;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatModelManager 单元测试
 * <p>覆盖 ChatModel 实例的创建与缓存、连接测试及异常映射等行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class ChatModelManagerTest {

    @Mock
    private ModelConfigRepository modelConfigRepository;

    @Mock
    private PasswordEncryptionUtil encryptionUtil;

    @Mock
    private ChatModelFactoryRegistry factoryRegistry;

    @Mock
    private ChatModelFactory chatModelFactory;

    @Mock
    private ChatModelBase chatModel;

    @InjectMocks
    private ChatModelManager chatModelManager;

    /**
     * 构造一个可用的模型配置实体
     *
     * @return 可用模型配置
     */
    private ModelConfig availableConfig() {
        ModelConfig config = new ModelConfig();
        config.setId(1L);
        config.setProviderName("dashscope");
        config.setModelId("qwen-max");
        config.setApiUrl("https://dashscope.aliyuncs.com");
        config.setApiKey("enc-key");
        config.setDeleted(0);
        config.setEnabled(1);
        return config;
    }

    @Test
    void should_createAndCacheModel_when_getChatModel_given_validConfig() {
        // given
        when(modelConfigRepository.findById(1L)).thenReturn(Optional.of(availableConfig()));
        when(encryptionUtil.decrypt(anyString())).thenReturn("sk-real");
        when(factoryRegistry.getFactory("dashscope")).thenReturn(chatModelFactory);
        when(chatModelFactory.create(any(ChatModelTemplate.class))).thenReturn(chatModel);

        // when
        ChatModelBase first = chatModelManager.getChatModel(1L);
        ChatModelBase second = chatModelManager.getChatModel(1L);

        // then: 第二次命中缓存，同一实例且工厂只创建一次
        assertSame(chatModel, first);
        assertSame(first, second);
        verify(modelConfigRepository, times(1)).findById(1L);
        verify(chatModelFactory, times(1)).create(any(ChatModelTemplate.class));
    }

    @Test
    void should_returnSuccess_when_testConnection_given_validModelResponse() {
        // given
        when(modelConfigRepository.findById(1L)).thenReturn(Optional.of(availableConfig()));
        when(encryptionUtil.decrypt(anyString())).thenReturn("sk-real");
        when(factoryRegistry.getFactory("dashscope")).thenReturn(chatModelFactory);
        when(chatModelFactory.create(any(ChatModelTemplate.class))).thenReturn(chatModel);
        // testConnection 只校验是否有响应，不读取 content 内容
        Flux<ChatResponse> responseFlux = Flux.just(org.mockito.Mockito.mock(ChatResponse.class));
        when(chatModel.stream(anyList(), any(), any())).thenReturn(responseFlux);

        // when
        TestConnectionResult result = chatModelManager.testConnection(1L);

        // then
        assertTrue(result.available());
        assertTrue(result.message().contains("连接成功"));
        verify(chatModel).stream(anyList(), any(), any());
    }

    @Test
    void should_useDecryptedApiKey_when_getChatModel_given_encryptedConfig() {
        // given
        when(modelConfigRepository.findById(1L)).thenReturn(Optional.of(availableConfig()));
        when(encryptionUtil.decrypt("enc-key")).thenReturn("sk-real");
        when(factoryRegistry.getFactory("dashscope")).thenReturn(chatModelFactory);
        when(chatModelFactory.create(any(ChatModelTemplate.class))).thenReturn(chatModel);

        // when
        chatModelManager.getChatModel(1L);

        // then: 传给工厂的模板使用解密后的 API Key
        ArgumentCaptor<ChatModelTemplate> captor = ArgumentCaptor.forClass(ChatModelTemplate.class);
        verify(chatModelFactory).create(captor.capture());
        assertEquals("sk-real", captor.getValue().apiKey());
        assertEquals("dashscope", captor.getValue().providerName());
    }

    @Test
    void should_throwException_when_getChatModel_given_configNotFound() {
        // given
        when(modelConfigRepository.findById(99L)).thenReturn(Optional.empty());

        // when & then
        DeepDataAgentException exception = assertThrows(
                DeepDataAgentException.class,
                () -> chatModelManager.getChatModel(99L)
        );
        assertTrue(exception.getMessage().contains("模型配置不存在"));
        verify(chatModelFactory, never()).create(any());
    }

    @Test
    void should_throwException_when_getChatModel_given_disabledModel() {
        // given
        ModelConfig config = availableConfig();
        config.setEnabled(0);
        when(modelConfigRepository.findById(1L)).thenReturn(Optional.of(config));

        // when & then
        DeepDataAgentException exception = assertThrows(
                DeepDataAgentException.class,
                () -> chatModelManager.getChatModel(1L)
        );
        assertTrue(exception.getMessage().contains("模型已禁用或删除"));
    }

    @Test
    void should_recreateModel_when_getChatModel_given_evictCache() {
        // given
        when(modelConfigRepository.findById(1L)).thenReturn(Optional.of(availableConfig()));
        when(encryptionUtil.decrypt(anyString())).thenReturn("sk-real");
        when(factoryRegistry.getFactory("dashscope")).thenReturn(chatModelFactory);
        when(chatModelFactory.create(any(ChatModelTemplate.class)))
                .thenReturn(org.mockito.Mockito.mock(ChatModelBase.class),
                        org.mockito.Mockito.mock(ChatModelBase.class));

        // when
        ChatModelBase first = chatModelManager.getChatModel(1L);
        chatModelManager.evictCache(1L);
        ChatModelBase second = chatModelManager.getChatModel(1L);

        // then: 失效后重建新实例
        assertNotSame(first, second);
        verify(chatModelFactory, times(2)).create(any(ChatModelTemplate.class));
    }

    @Test
    void should_returnNotConfigured_when_testConnection_given_modelNotFound() {
        // given
        when(modelConfigRepository.findById(99L)).thenReturn(Optional.empty());

        // when
        TestConnectionResult result = chatModelManager.testConnection(99L);

        // then
        assertFalse(result.available());
        assertEquals("模型配置不存在", result.message());
    }

    @Test
    void should_returnNoResponse_when_testConnection_given_emptyStream() {
        // given
        when(modelConfigRepository.findById(1L)).thenReturn(Optional.of(availableConfig()));
        when(encryptionUtil.decrypt(anyString())).thenReturn("sk-real");
        when(factoryRegistry.getFactory("dashscope")).thenReturn(chatModelFactory);
        when(chatModelFactory.create(any(ChatModelTemplate.class))).thenReturn(chatModel);
        when(chatModel.stream(anyList(), any(), any())).thenReturn(Flux.empty());

        // when
        TestConnectionResult result = chatModelManager.testConnection(1L);

        // then
        assertFalse(result.available());
        assertEquals("模型未响应", result.message());
    }

    @Test
    void should_returnAuthError_when_testConnection_given_unauthorizedMessage() {
        // given
        when(modelConfigRepository.findById(1L)).thenReturn(Optional.of(availableConfig()));
        when(encryptionUtil.decrypt(anyString())).thenReturn("sk-real");
        when(factoryRegistry.getFactory("dashscope")).thenReturn(chatModelFactory);
        when(chatModelFactory.create(any(ChatModelTemplate.class))).thenReturn(chatModel);
        when(chatModel.stream(anyList(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("401 Unauthorized")));

        // when
        TestConnectionResult result = chatModelManager.testConnection(1L);

        // then
        assertFalse(result.available());
        assertTrue(result.message().contains("API Key 无效"));
    }

    @Test
    void should_returnModelNameError_when_testConnection_given_modelNotFoundMessage() {
        // given
        when(modelConfigRepository.findById(1L)).thenReturn(Optional.of(availableConfig()));
        when(encryptionUtil.decrypt(anyString())).thenReturn("sk-real");
        when(factoryRegistry.getFactory("dashscope")).thenReturn(chatModelFactory);
        when(chatModelFactory.create(any(ChatModelTemplate.class))).thenReturn(chatModel);
        when(chatModel.stream(anyList(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("404 model not found")));

        // when
        TestConnectionResult result = chatModelManager.testConnection(1L);

        // then
        assertFalse(result.available());
        assertTrue(result.message().contains("模型名称不正确"));
    }

    @Test
    void should_returnTimeout_when_testConnection_given_timeoutException() {
        // given
        when(modelConfigRepository.findById(1L)).thenReturn(Optional.of(availableConfig()));
        when(encryptionUtil.decrypt(anyString())).thenReturn("sk-real");
        when(factoryRegistry.getFactory("dashscope")).thenReturn(chatModelFactory);
        when(chatModelFactory.create(any(ChatModelTemplate.class))).thenReturn(chatModel);
        when(chatModel.stream(anyList(), any(), any()))
                .thenReturn(Flux.error(new TimeoutException("request timeout")));

        // when
        TestConnectionResult result = chatModelManager.testConnection(1L);

        // then
        assertFalse(result.available());
        assertTrue(result.message().contains("请求超时"));
    }

    @Test
    void should_returnFailure_when_testConnection_given_unknownError() {
        // given
        when(modelConfigRepository.findById(1L)).thenReturn(Optional.of(availableConfig()));
        when(encryptionUtil.decrypt(anyString())).thenReturn("sk-real");
        when(factoryRegistry.getFactory("dashscope")).thenReturn(chatModelFactory);
        when(chatModelFactory.create(any(ChatModelTemplate.class))).thenReturn(chatModel);
        when(chatModel.stream(anyList(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("gateway 502")));

        // when
        TestConnectionResult result = chatModelManager.testConnection(1L);

        // then
        assertFalse(result.available());
        assertTrue(result.message().contains("连接失败"));
    }
}