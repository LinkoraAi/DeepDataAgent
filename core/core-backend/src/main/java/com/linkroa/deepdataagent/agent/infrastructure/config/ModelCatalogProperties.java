package com.linkroa.deepdataagent.agent.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型目录种子配置（前缀 {@code model-catalog}，design D7）。
 * <p>目录清单与内部供应商映射（目录模型 → 内部 {@code model_profile} 凭证配置）由配置维护，
 * 本期不提供管理界面；供应商映射按租户键（ownerId 字符串，{@code "*"} 为默认租户）分层，
 * 不进入对外契约。</p>
 */
@Component
@ConfigurationProperties(prefix = "model-catalog")
public class ModelCatalogProperties {

    /** 目录条目种子清单（对外可见字段）。 */
    private List<CatalogItem> items = new ArrayList<>();

    /** 内部供应商映射：租户键（ownerId 或 "*"）→ 目录模型 id → model_profile_id。 */
    private Map<String, Map<String, String>> providerMappings = new LinkedHashMap<>();

    public List<CatalogItem> getItems() {
        return items;
    }

    public void setItems(List<CatalogItem> items) {
        this.items = items;
    }

    public Map<String, Map<String, String>> getProviderMappings() {
        return providerMappings;
    }

    public void setProviderMappings(Map<String, Map<String, String>> providerMappings) {
        this.providerMappings = providerMappings;
    }

    /**
     * 目录条目配置载体（宽松绑定，efforts 为线格式字符串，由领域层解析校验）。
     */
    public static class CatalogItem {

        private String id;
        private String displayName;
        private String source = "system";
        private Boolean isEnabled = Boolean.TRUE;
        private Boolean isNew;
        private List<String> efforts = new ArrayList<>();
        private String defaultEffort;
        private Boolean isVl;
        private Boolean isDefault;
        private Integer maxInputTokens;
        private Integer maxOutputTokens;
        private Integer defaultContextWindow;
        private List<Integer> availableContextWindows = new ArrayList<>();

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public Boolean getIsEnabled() {
            return isEnabled;
        }

        public void setIsEnabled(Boolean isEnabled) {
            this.isEnabled = isEnabled;
        }

        public Boolean getIsNew() {
            return isNew;
        }

        public void setIsNew(Boolean isNew) {
            this.isNew = isNew;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public List<String> getEfforts() {
            return efforts;
        }

        public void setEfforts(List<String> efforts) {
            this.efforts = efforts;
        }

        public String getDefaultEffort() {
            return defaultEffort;
        }

        public void setDefaultEffort(String defaultEffort) {
            this.defaultEffort = defaultEffort;
        }

        public Boolean getIsVl() {
            return isVl;
        }

        public void setIsVl(Boolean isVl) {
            this.isVl = isVl;
        }

        public Boolean getIsDefault() {
            return isDefault;
        }

        public void setIsDefault(Boolean isDefault) {
            this.isDefault = isDefault;
        }

        public Integer getMaxInputTokens() {
            return maxInputTokens;
        }

        public void setMaxInputTokens(Integer maxInputTokens) {
            this.maxInputTokens = maxInputTokens;
        }

        public Integer getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(Integer maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public Integer getDefaultContextWindow() {
            return defaultContextWindow;
        }

        public void setDefaultContextWindow(Integer defaultContextWindow) {
            this.defaultContextWindow = defaultContextWindow;
        }

        public List<Integer> getAvailableContextWindows() {
            return availableContextWindows;
        }

        public void setAvailableContextWindows(List<Integer> availableContextWindows) {
            this.availableContextWindows = availableContextWindows;
        }
    }
}
