package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.domain.model.ModelCatalogItem;
import com.linkroa.deepdataagent.agent.domain.model.ModelRef;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelEffort;
import com.linkroa.deepdataagent.agent.infrastructure.config.ModelCatalogProperties;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模型目录应用服务（design D7：目录条目种子配置驱动、只读，本期无管理面）。
 * <p>职责：①目录清单查询（{@code GET /api/cloud/models} 数据源，不含供应商信息）；
 * ②发布期模型校验（目录存在性 + effort / context_window 档位支持）；
 * ③内部供应商映射解析（目录模型 id → 内部 {@code model_profile_id}，租户键覆盖默认键
 * {@code "*"}，映射结果不进入对外契约，仅运行时装配消费）。</p>
 */
@Service
public class ModelCatalogService {

    /** 默认租户映射键。 */
    public static final String DEFAULT_TENANT_KEY = "*";

    @Resource
    private ModelCatalogProperties properties;

    /**
     * 查询全部目录条目。
     *
     * @return 目录条目列表（保持种子顺序）
     * @throws IllegalStateException 目录为空（配置缺失时返回明确错误，不静默回退）
     */
    public List<ModelCatalogItem> listModels() {
        List<ModelCatalogItem> items = properties.getItems().stream()
                .map(ModelCatalogService::toDomain)
                .filter(ModelCatalogItem::isEnabled)
                .toList();
        if (items.isEmpty()) {
            throw new IllegalStateException("模型目录未配置（model-catalog.items 为空）");
        }
        return items;
    }

    /**
     * 按目录模型 id 查询条目。
     *
     * @param modelId 目录模型 id（可空）
     * @return 目录条目；不存在返回空
     */
    public Optional<ModelCatalogItem> find(String modelId) {
        if (modelId == null) {
            return Optional.empty();
        }
        return properties.getItems().stream()
                .filter(item -> modelId.equals(item.getId()))
                .findFirst()
                .map(ModelCatalogService::toDomain);
    }

    /**
     * 发布期模型校验：目录存在性 + 调优参数档位支持。
     *
     * @param modelRef 模型引用（可空 = 沿用内部配置引用，跳过校验）
     * @throws IllegalArgumentException 模型不在目录 / effort 或 context_window 档位不受支持
     */
    public void validateModel(ModelRef modelRef) {
        if (modelRef == null) {
            return;
        }
        ModelCatalogItem item = find(modelRef.id())
                .orElseThrow(() -> new IllegalArgumentException("模型目录中不存在该模型: " + modelRef.id()));
        if (!item.isEnabled()) {
            throw new IllegalArgumentException("模型已禁用，不可被新引用: " + modelRef.id());
        }
        if (modelRef.effort() != null && !item.efforts().contains(modelRef.effort())) {
            throw new IllegalArgumentException("模型 " + modelRef.id() + " 不支持 effort 档位: "
                    + modelRef.effort().value());
        }
        if (modelRef.contextWindow() != null && !item.supportsContextWindow(modelRef.contextWindow())) {
            throw new IllegalArgumentException("模型 " + modelRef.id() + " 不支持上下文窗口档位: "
                    + modelRef.contextWindow());
        }
    }

    /**
     * 解析目录模型对应的内部供应商配置引用（{@code model_profile_id}）。
     * <p>先查租户键映射，再回退默认键 {@code "*"}；两键皆缺返回 {@code null}，
     * 由调用方决定回退策略（运行时装配回退默认目录模型 + 可用供应商）。</p>
     *
     * @param modelId 目录模型 id
     * @param ownerId 租户用户 id（可空 = 仅查默认键）
     * @return 内部 model_profile_id；未配置映射返回 {@code null}
     */
    public String resolveProfileId(String modelId, Long ownerId) {
        if (modelId == null) {
            return null;
        }
        if (ownerId != null) {
            String tenantHit = mappedProfileId(String.valueOf(ownerId), modelId);
            if (tenantHit != null) {
                return tenantHit;
            }
        }
        return mappedProfileId(DEFAULT_TENANT_KEY, modelId);
    }

    private String mappedProfileId(String tenantKey, String modelId) {
        Map<String, String> mapping = properties.getProviderMappings().get(tenantKey);
        return mapping == null ? null : mapping.get(modelId);
    }

    private static ModelCatalogItem toDomain(ModelCatalogProperties.CatalogItem config) {
        List<ModelEffort> efforts = config.getEfforts().stream().map(ModelEffort::from).toList();
        return new ModelCatalogItem(
                config.getId(),
                config.getDisplayName(),
                config.getSource(),
                !Boolean.FALSE.equals(config.getIsEnabled()),
                Boolean.TRUE.equals(config.getIsNew()),
                Boolean.TRUE.equals(config.getIsVl()),
                Boolean.TRUE.equals(config.getIsDefault()),
                efforts,
                ModelEffort.from(config.getDefaultEffort()),
                config.getMaxInputTokens(),
                config.getMaxOutputTokens(),
                config.getDefaultContextWindow(),
                config.getAvailableContextWindows());
    }
}
