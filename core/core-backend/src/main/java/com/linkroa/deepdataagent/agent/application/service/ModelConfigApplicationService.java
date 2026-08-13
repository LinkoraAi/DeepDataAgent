package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.assembler.ModelConfigDTOAssembler;
import com.linkroa.deepdataagent.agent.application.command.AddModelConfigCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateModelConfigCommand;
import com.linkroa.deepdataagent.agent.application.dto.ModelConfigDTO;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 模型配置应用服务
 * <p>基于新的 AgentModelInfo 聚合根管理模型配置。
 * 接收应用层命令对象，返回应用层 DTO，由控制器层完成请求对象与命令对象、DTO 与响应对象的转换。</p>
 * <p>事务统一采用编程式事务（{@link TransactionTemplate}），事务边界在各方法内部自包含，不依赖调用方开启事务。</p>
 */
@Service
public class ModelConfigApplicationService {

    /** 模型连通性测试限流键前缀（按模型配置 ID 隔离） */
    private static final String MODEL_TEST_RATE_LIMIT_KEY_PREFIX = "dd:ratelimit:model-test:";

    /** 默认模型切换分布式锁键 */
    private static final String DEFAULT_MODEL_LOCK_KEY = "dd:lock:default-model";

    /** 默认模型切换分布式锁持有时间（到期自动释放，防止持有方异常退出后死锁） */
    private static final Duration DEFAULT_MODEL_LOCK_LEASE = Duration.ofSeconds(10);

    private final ModelConfigRepository modelInfoRepository;
    private final ChatModelManager chatModelManager;
    private final TransactionTemplate transactionTemplate;
    private final RateLimiterPort rateLimiterPort;
    private final DistributedLockPort distributedLockPort;

    /**
     * 构造方法
     *
     * @param modelInfoRepository 模型配置仓储
     * @param chatModelManager    ChatModel 实例管理器
     * @param transactionTemplate 编程式事务模板
     * @param rateLimiterPort     模型测试限流端口
     * @param distributedLockPort 默认模型切换分布式锁端口
     */
    public ModelConfigApplicationService(ModelConfigRepository modelInfoRepository,
                                         ChatModelManager chatModelManager,
                                         TransactionTemplate transactionTemplate,
                                         RateLimiterPort rateLimiterPort,
                                         DistributedLockPort distributedLockPort) {
        this.modelInfoRepository = modelInfoRepository;
        this.chatModelManager = chatModelManager;
        this.transactionTemplate = transactionTemplate;
        this.rateLimiterPort = rateLimiterPort;
        this.distributedLockPort = distributedLockPort;
    }

    /**
     * 获取所有启用的模型配置
     */
    public List<ModelConfig> listAllEnabled() {
        return modelInfoRepository.findAllEnabled();
    }

    /**
     * 获取默认模型
     */
    public ModelConfig getDefaultModel() {
        return modelInfoRepository.findDefault()
                .orElseThrow(() -> new DeepDataAgentException("未配置默认模型"));
    }

    /**
     * 获取默认模型（不抛出异常，不存在时返回 null）
     */
    public ModelConfig getDefaultModelOrNull() {
        return modelInfoRepository.findDefault().orElse(null);
    }

    /**
     * 查询所有启用的服务商（去重）
     *
     * @return 服务商 DTO 列表
     */
    public List<ModelProviderDTO> listProviders() {
        return modelInfoRepository.findProviders().stream()
                .map(p -> new ModelProviderDTO(
                        p.getId(), p.getProviderDisplayName(), p.getProviderName(), p.getApiUrl()))
                .toList();
    }

    /**
     * 根据服务商标识查询可用模型列表
     *
     * @param providerKey 服务商标识
     * @return 模型 DTO 列表（仅可用模型）
     */
    public List<ModelInfoDTO> getModelsByProvider(String providerKey) {
        return modelInfoRepository.findByProviderName(providerKey).stream()
                .filter(ModelConfig::isAvailable)
                .map(m -> new ModelInfoDTO(
                        m.getId(), m.getModelId(), m.getProviderDisplayName() + " - " + m.getModelId()))
                .toList();
    }

    /**
     * 根据 ID 获取模型配置
     */
    public ModelConfig getModelById(Long id) {
        return modelInfoRepository.findById(id)
                .orElseThrow(() -> new DeepDataAgentException("模型配置不存在: " + id));
    }

    /**
     * 获取所有启用的模型配置（返回 DTO）
     */
    public List<ModelConfigDTO> listConfigDTOs() {
        return ModelConfigDTOAssembler.toDTOList(modelInfoRepository.findAllEnabled());
    }

    /**
     * 根据 ID 获取模型配置（返回 DTO，默认脱敏）
     */
    public ModelConfigDTO getConfigDTO(Long id) {
        return ModelConfigDTOAssembler.toDTO(getModelById(id));
    }

    /**
     * 获取默认模型配置（返回 DTO，默认脱敏）
     */
    public ModelConfigDTO getDefaultConfigDTO() {
        return ModelConfigDTOAssembler.toDTO(getDefaultModelOrNull());
    }

    /**
     * 根据 ID 获取模型配置（返回 DTO，不脱敏 API Key，用于编辑）
     */
    public ModelConfigDTO getConfigForEditDTO(Long id) {
        return ModelConfigDTOAssembler.toDTO(getModelById(id), false);
    }

    /**
     * 添加模型配置
     * <p>新增配置并（若设置为默认）清除其他默认配置，二者在同一编程式事务内原子提交。</p>
     */
    public void addConfig(AddModelConfigCommand command) {
        transactionTemplate.executeWithoutResult(status -> {
            ModelConfig info = new ModelConfig();
            info.setProviderDisplayName(command.providerKey());
            info.setProviderName(command.providerKey());
            info.setModelId(command.modelKey());
            info.setApiUrl(command.baseUrl());
            info.setApiKey(command.apiKey());
            info.setDefaultModel(Boolean.TRUE.equals(command.setDefault()) ? 1 : 0);
            info.setEnabled(1);
            info.setSortOrder(0);

            modelInfoRepository.save(info);

            // 如果设为默认，清除其他默认
            if (Boolean.TRUE.equals(command.setDefault())) {
                modelInfoRepository.findAllEnabled().stream()
                        .filter(m -> !m.getId().equals(info.getId()) && m.getDefaultModel() != null && m.getDefaultModel() == 1)
                        .forEach(m -> {
                            m.setDefaultModel(0);
                            modelInfoRepository.update(m);
                        });
            }
        });
    }

    /**
     * 更新模型配置
     * <p>更新操作在编程式事务内完成；缓存失效为非 DB 操作，置于事务外执行。</p>
     */
    public void updateConfig(Long id, UpdateModelConfigCommand command) {
        transactionTemplate.executeWithoutResult(status -> {
            ModelConfig info = getModelById(id);

            if (command.baseUrl() != null) {
                info.setApiUrl(command.baseUrl());
            }
            if (command.apiKey() != null && !command.apiKey().isBlank()) {
                info.setApiKey(command.apiKey());
            }

            modelInfoRepository.update(info);
        });
        chatModelManager.evictCache(id);
    }

    /**
     * 删除模型配置（软删除）
     * <p>软删除并（若删除的是默认模型）迁移默认配置，二者在同一编程式事务内原子提交；
     * 缓存失效为非 DB 操作，置于事务外执行。</p>
     */
    public void deleteConfig(Long id) {
        transactionTemplate.executeWithoutResult(status -> {
            ModelConfig info = getModelById(id);

            modelInfoRepository.markDeleted(id);

            // 如果删除的是默认模型，选择下一个作为默认
            if (info.getDefaultModel() != null && info.getDefaultModel() == 1) {
                modelInfoRepository.findAllEnabled().stream()
                        .findFirst()
                        .ifPresent(m -> {
                            m.setDefaultModel(1);
                            modelInfoRepository.update(m);
                        });
            }
        });
        chatModelManager.evictCache(id);
    }

    /**
     * 设置默认模型
     * <p>先获取分布式锁保证"唯一默认模型"并发约束（跨实例互斥，锁到期自动释放），
     * 再在编程式事务内取消所有默认并设置指定模型为默认。</p>
     */
    public void setDefaultModel(Long id) {
        Optional<DistributedLock> lockOpt = distributedLockPort.tryLock(DEFAULT_MODEL_LOCK_KEY, DEFAULT_MODEL_LOCK_LEASE);
        if (lockOpt.isEmpty()) {
            throw new DeepDataAgentException("系统繁忙，请稍后重试");
        }
        try (DistributedLock ignored = lockOpt.get()) {
            transactionTemplate.executeWithoutResult(status -> {
                ModelConfig info = getModelById(id);

                // 取消所有默认
                modelInfoRepository.findAllEnabled().stream()
                        .filter(m -> m.getDefaultModel() != null && m.getDefaultModel() == 1)
                        .forEach(m -> {
                            m.setDefaultModel(0);
                            modelInfoRepository.update(m);
                        });

                info.setDefaultModel(1);
                modelInfoRepository.update(info);
            });
        }
    }

    /**
     * 测试模型连接（带频率限制）
     * <p>通过 {@link RateLimiterPort} 按模型配置 ID 隔离限流，窗口内重复请求被拒绝；
     * Redis 不可用时限流自动放行。</p>
     */
    public TestConnectionResult testConnection(Long id) {
        ModelConfig info = getModelById(id);

        // 频率限制
        boolean allowed = rateLimiterPort.tryAcquire(MODEL_TEST_RATE_LIMIT_KEY_PREFIX + id);
        if (!allowed) {
            return new TestConnectionResult(false, "测试过于频繁，请稍后再试", 0L);
        }

        return chatModelManager.testConnection(id);
    }
}
