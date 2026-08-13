package com.linkroa.deepdataagent.agent.infrastructure.client;

import com.linkroa.deepdataagent.agent.domain.model.ModelConfig;
import com.linkroa.deepdataagent.agent.domain.model.TestConnectionResult;
import com.linkroa.deepdataagent.agent.domain.repository.ModelConfigRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.util.LogMasker;
import com.linkroa.deepdataagent.datasource.infrastructure.util.PasswordEncryptionUtil;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatModel 实例管理器
 * <p>负责 ChatModel 实例的创建与缓存管理，并对外提供连接测试与缓存失效能力。
 * 与业务生成逻辑（NL2SQL、图表、标题）解耦，作为各业务客户端与 Agent 构建的公共底座。</p>
 * <p>核心职责：</p>
 * <ul>
 *   <li>按 modelConfigId 缓存 ChatModel 实例，配置变更时通过 {@link #evictCache} 失效重建</li>
 *   <li>创建临时实例用于连接测试，并将底层异常映射为用户友好的错误提示</li>
 * </ul>
 */
@Component
public class ChatModelManager {

    private static final Logger log = LoggerFactory.getLogger(ChatModelManager.class);

    private final ModelConfigRepository modelConfigRepository;
    private final PasswordEncryptionUtil encryptionUtil;
    private final ChatModelFactoryRegistry factoryRegistry;
    private final Map<Long, ChatModelBase> chatModelCache = new ConcurrentHashMap<>();

    /**
     * 构造方法
     *
     * @param modelConfigRepository 模型配置仓储
     * @param encryptionUtil        密码加解密工具
     * @param factoryRegistry       模型工厂注册表
     */
    public ChatModelManager(ModelConfigRepository modelConfigRepository,
                            PasswordEncryptionUtil encryptionUtil,
                            ChatModelFactoryRegistry factoryRegistry) {
        this.modelConfigRepository = modelConfigRepository;
        this.encryptionUtil = encryptionUtil;
        this.factoryRegistry = factoryRegistry;
    }

    /**
     * 根据配置 ID 获取 ChatModel 实例（带缓存）
     * <p>供 Agent 构建与业务客户端使用。</p>
     *
     * @param modelConfigId 模型配置 ID
     * @return ChatModel 实例
     */
    public ChatModelBase getChatModel(Long modelConfigId) {
        return chatModelCache.computeIfAbsent(modelConfigId, id -> {
            ModelConfig info = modelConfigRepository.findById(id)
                    .orElseThrow(() -> new DeepDataAgentException("模型配置不存在: " + id));
            if (!info.isAvailable()) {
                throw new DeepDataAgentException("模型已禁用或删除: " + id);
            }
            String apiKey = encryptionUtil.decrypt(info.getApiKey());
            return createChatModel(info, apiKey);
        });
    }

    /**
     * 创建 ChatModel 实例
     *
     * @param info   模型配置实体
     * @param apiKey 解密后的 API Key
     * @return ChatModel 实例
     */
    private ChatModelBase createChatModel(ModelConfig info, String apiKey) {
        ChatModelTemplate template = ChatModelTemplate.from(info, apiKey);
        ChatModelFactory factory = factoryRegistry.getFactory(info.getProviderName());
        return factory.create(template);
    }

    /**
     * 清除指定模型的缓存（配置修改/删除时调用）
     *
     * @param modelConfigId 模型配置 ID
     */
    public void evictCache(Long modelConfigId) {
        chatModelCache.remove(modelConfigId);
    }

    /**
     * 测试模型连接
     * <p>创建临时 ChatModel 并发送测试消息，测量响应时间；实例不进入缓存。</p>
     *
     * @param modelConfigId 模型配置 ID
     * @return 连接测试结果
     */
    public TestConnectionResult testConnection(Long modelConfigId) {
        long startTime = System.currentTimeMillis();
        try {
            ModelConfig info = modelConfigRepository.findById(modelConfigId)
                    .orElse(null);
            if (info == null) {
                return new TestConnectionResult(false, "模型配置不存在", 0L);
            }
            String apiKey = encryptionUtil.decrypt(info.getApiKey());
            ChatModelBase model = createChatModel(info, apiKey);

            List<Msg> messages = List.of(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("你好，请回复OK")
                            .build()
            );

            List<ChatResponse> responses = model.stream(messages, null, null)
                    .timeout(Duration.ofSeconds(10))
                    .collectList().block();

            long responseTime = System.currentTimeMillis() - startTime;

            if (responses == null || responses.isEmpty()) {
                return new TestConnectionResult(false, "模型未响应", responseTime);
            }

            return new TestConnectionResult(true, "连接成功，模型可用", responseTime);
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            String message = mapConnectionError(e);
            return new TestConnectionResult(false, message, responseTime);
        }
    }

    /**
     * 将异常信息映射为用户友好的错误提示
     *
     * @param e 异常
     * @return 错误提示文案
     */
    private String mapConnectionError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("401") || msg.contains("unauthorized") || msg.contains("invalid api")) {
            return "API Key 无效，请检查后重新输入";
        }
        if (msg.contains("404") || msg.contains("model not found")) {
            return "模型名称不正确，请检查配置";
        }
        if (msg.contains("timeout") || e instanceof java.util.concurrent.TimeoutException) {
            return "请求超时，请检查网络连接";
        }
        return "连接失败: " + LogMasker.mask(e.getMessage());
    }
}