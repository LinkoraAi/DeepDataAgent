package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.assembler.ModelConfigDTOAssembler;
import com.linkroa.deepdataagent.agent.application.command.AddModelConfigCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateModelConfigCommand;
import com.linkroa.deepdataagent.agent.application.dto.ModelConfigDTO;
import com.linkroa.deepdataagent.agent.application.dto.ModelInfoDTO;
import com.linkroa.deepdataagent.agent.application.dto.ModelProviderDTO;
import com.linkroa.deepdataagent.agent.domain.model.AgentModelInfo;
import com.linkroa.deepdataagent.agent.domain.model.TestConnectionResult;
import com.linkroa.deepdataagent.agent.domain.repository.AgentModelInfoRepository;
import com.linkroa.deepdataagent.agent.infrastructure.client.LLMClient;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型配置应用服务
 * <p>基于新的 AgentModelInfo 聚合根管理模型配置。
 * 接收应用层命令对象，返回应用层 DTO，由控制器层完成请求对象与命令对象、DTO 与响应对象的转换。</p>
 * <p>事务统一采用编程式事务（{@link TransactionTemplate}），事务边界在各方法内部自包含，不依赖调用方开启事务。</p>
 */
@Service
public class ModelConfigApplicationService {

    private final AgentModelInfoRepository modelInfoRepository;
    private final LLMClient llmClient;
    private final TransactionTemplate transactionTemplate;
    private final Map<Long, Long> lastTestTime = new ConcurrentHashMap<>();
    private static final long TEST_COOLDOWN_MS = 5000;

    /**
     * 构造方法
     *
     * @param modelInfoRepository 模型配置仓储
     * @param llmClient           LLM 客户端
     * @param transactionTemplate 编程式事务模板
     */
    public ModelConfigApplicationService(AgentModelInfoRepository modelInfoRepository,
                                         LLMClient llmClient,
                                         TransactionTemplate transactionTemplate) {
        this.modelInfoRepository = modelInfoRepository;
        this.llmClient = llmClient;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 获取所有启用的模型配置
     */
    public List<AgentModelInfo> listAllEnabled() {
        return modelInfoRepository.findAllEnabled();
    }

    /**
     * 获取默认模型
     */
    public AgentModelInfo getDefaultModel() {
        return modelInfoRepository.findDefault()
                .orElseThrow(() -> new DeepDataAgentException("未配置默认模型"));
    }

    /**
     * 获取默认模型（不抛出异常，不存在时返回 null）
     */
    public AgentModelInfo getDefaultModelOrNull() {
        return modelInfoRepository.findDefault().orElse(null);
    }

    /**
     * 查询所有启用的服务商（去重）
     *
     * @return 服务商 DTO 列表
     */
    public List<ModelProviderDTO> listProviders() {
        return modelInfoRepository.findDistinctProviders().stream()
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
                .filter(AgentModelInfo::isAvailable)
                .map(m -> new ModelInfoDTO(
                        m.getId(), m.getModelId(), m.getProviderDisplayName() + " - " + m.getModelId()))
                .toList();
    }

    /**
     * 根据 ID 获取模型配置
     */
    public AgentModelInfo getModelById(Long id) {
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
            AgentModelInfo info = new AgentModelInfo();
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
            AgentModelInfo info = getModelById(id);

            if (command.baseUrl() != null) {
                info.setApiUrl(command.baseUrl());
            }
            if (command.apiKey() != null && !command.apiKey().isBlank()) {
                info.setApiKey(command.apiKey());
            }

            modelInfoRepository.update(info);
        });
        llmClient.evictCache(id);
    }

    /**
     * 删除模型配置（软删除）
     * <p>软删除并（若删除的是默认模型）迁移默认配置，二者在同一编程式事务内原子提交；
     * 缓存失效为非 DB 操作，置于事务外执行。</p>
     */
    public void deleteConfig(Long id) {
        transactionTemplate.executeWithoutResult(status -> {
            AgentModelInfo info = getModelById(id);

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
        llmClient.evictCache(id);
    }

    /**
     * 设置默认模型
     * <p>取消所有默认并设置指定模型为默认，二者在同一编程式事务内原子提交。</p>
     */
    public void setDefaultModel(Long id) {
        transactionTemplate.executeWithoutResult(status -> {
            AgentModelInfo info = getModelById(id);

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

    /**
     * 测试模型连接（带频率限制）
     */
    public TestConnectionResult testConnection(Long id) {
        AgentModelInfo info = getModelById(id);

        // 频率限制
        Long lastTime = lastTestTime.get(id);
        if (lastTime != null && System.currentTimeMillis() - lastTime < TEST_COOLDOWN_MS) {
            return new TestConnectionResult(false, "测试过于频繁，请稍后再试", 0L);
        }

        TestConnectionResult result = llmClient.testConnection(id);
        if (result.available()) {
            lastTestTime.put(id, System.currentTimeMillis());
        }
        return result;
    }
}
