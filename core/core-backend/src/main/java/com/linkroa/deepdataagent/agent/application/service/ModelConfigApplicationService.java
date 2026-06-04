package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.controller.request.AddModelConfigRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateModelConfigRequest;
import com.linkroa.deepdataagent.agent.controller.response.TestConnectionResult;
import com.linkroa.deepdataagent.agent.infrastructure.client.LLMClient;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.LlmModelConfigEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.LlmModelTemplateEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.LlmModelConfigMapper;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.LlmModelTemplateMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.util.PasswordEncryptionUtil;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型配置应用服务
 */
@Service
public class ModelConfigApplicationService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LlmModelTemplateMapper templateMapper;
    private final LlmModelConfigMapper configMapper;
    private final PasswordEncryptionUtil encryptionUtil;
    private final LLMClient llmClient;
    private final Map<Long, Long> lastTestTime = new ConcurrentHashMap<>();
    private static final long TEST_COOLDOWN_MS = 5000;

    public ModelConfigApplicationService(LlmModelTemplateMapper templateMapper,
                                         LlmModelConfigMapper configMapper,
                                         PasswordEncryptionUtil encryptionUtil,
                                         LLMClient llmClient) {
        this.templateMapper = templateMapper;
        this.configMapper = configMapper;
        this.encryptionUtil = encryptionUtil;
        this.llmClient = llmClient;
    }

    /**
     * 获取所有启用的预置模板
     */
    public List<LlmModelTemplateEntity> listTemplates() {
        return templateMapper.selectEnabledTemplates();
    }

    /**
     * 获取所有用户模型配置
     */
    public List<LlmModelConfigEntity> listConfigs() {
        return configMapper.selectAllNotDeleted();
    }

    /**
     * 添加模型配置
     */
    @Transactional
    public void addConfig(AddModelConfigRequest request) {
        // 验证模板存在
        LlmModelTemplateEntity template = templateMapper.selectById(request.templateId());
        if (template == null) {
            throw new DeepDataAgentException("模型模板不存在");
        }

        String now = LocalDateTime.now().format(FORMATTER);

        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setName(request.name());
        entity.setTemplateId(request.templateId());
        entity.setProvider(template.getProvider());
        entity.setBaseUrl(template.getBaseUrl());
        entity.setApiKey(encryptionUtil.encrypt(request.apiKey()));
        entity.setModelName(template.getModelName());
        entity.setTemperature(request.temperature() != null ? request.temperature() : 0.1);
        entity.setIsDefault(Boolean.TRUE.equals(request.setDefault()) ? 1 : 0);
        entity.setDescription(request.description());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);

        configMapper.insert(entity);

        // 如果设为默认，取消其他默认
        if (Boolean.TRUE.equals(request.setDefault())) {
            configMapper.cancelDefaultForAll(now);
            entity.setIsDefault(1);
            configMapper.updateById(entity);
        }
    }

    /**
     * 更新模型配置
     */
    @Transactional
    public void updateConfig(Long id, UpdateModelConfigRequest request) {
        LlmModelConfigEntity entity = configMapper.selectByIdAndNotDeleted(id);
        if (entity == null) {
            throw new DeepDataAgentException("模型配置不存在");
        }

        String now = LocalDateTime.now().format(FORMATTER);

        if (request.name() != null && !request.name().isBlank()) {
            entity.setName(request.name());
        }
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            entity.setApiKey(encryptionUtil.encrypt(request.apiKey()));
        }
        if (request.temperature() != null) {
            entity.setTemperature(request.temperature());
        }
        if (request.description() != null) {
            entity.setDescription(request.description());
        }
        entity.setUpdatedAt(now);

        configMapper.updateById(entity);

        // 清除 LLM 客户端缓存，使新配置立即生效
        llmClient.evictCache(id);
    }

    /**
     * 删除模型配置
     */
    @Transactional
    public void deleteConfig(Long id) {
        LlmModelConfigEntity entity = configMapper.selectByIdAndNotDeleted(id);
        if (entity == null) {
            throw new DeepDataAgentException("模型配置不存在");
        }

        String now = LocalDateTime.now().format(FORMATTER);
        entity.setIsDeleted(1);
        entity.setUpdatedAt(now);
        configMapper.updateById(entity);

        // 如果删除的是默认模型，按模板优先级选择下一个作为默认
        if (entity.getIsDefault() == 1) {
            LlmModelConfigEntity newDefault = configMapper.selectNextDefaultModel();
            if (newDefault != null) {
                newDefault.setIsDefault(1);
                newDefault.setUpdatedAt(now);
                configMapper.updateById(newDefault);
            }
        }

        // 清除 LLM 客户端缓存
        llmClient.evictCache(id);
    }

    /**
     * 设置默认模型
     */
    @Transactional
    public void setDefaultModel(Long id) {
        LlmModelConfigEntity entity = configMapper.selectByIdAndNotDeleted(id);
        if (entity == null) {
            throw new DeepDataAgentException("模型配置不存在");
        }

        String now = LocalDateTime.now().format(FORMATTER);
        // 取消所有默认
        configMapper.cancelDefaultForAll(now);
        // 设置新默认
        entity.setIsDefault(1);
        entity.setUpdatedAt(now);
        configMapper.updateById(entity);
    }

    /**
     * 获取默认模型
     */
    public LlmModelConfigEntity getDefaultModel() {
        return configMapper.selectDefaultModel();
    }

    /**
     * 根据 ID 获取模型配置（含解密后的 API Key）
     */
    public LlmModelConfigEntity getConfigById(Long id) {
        return configMapper.selectByIdAndNotDeleted(id);
    }

    /**
     * 解密 API Key
     */
    public String decryptApiKey(String encryptedKey) {
        return encryptionUtil.decrypt(encryptedKey);
    }

    /**
     * 测试模型连接（带频率限制）
     * <p>5 秒内只能测试一次成功连接，防止频繁请求 LLM 接口</p>
     *
     * @param id 模型配置 ID
     * @return 连接测试结果
     */
    public TestConnectionResult testConnection(Long id) {
        LlmModelConfigEntity entity = configMapper.selectByIdAndNotDeleted(id);
        if (entity == null) {
            return new TestConnectionResult(false, "模型配置不存在", 0L);
        }

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
