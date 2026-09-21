package com.linkroa.deepdataagent.knowledgebase.domain.model;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.KbLanguage;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.LifecycleStatus;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 知识库聚合根。
 * <p>管理知识库全生命周期（ACTIVE → DELETING →（收口＝行物理删除），失败分支
 * DELETING → DELETE_FAILED → DELETING）及所有库级配置。
 * 「已删除」不再是持久状态——收口为单条条件 DELETE，行缺失（404）即其唯一表达。
 * 配置以 JSONB 字符串形式持久化，领域模型内以值对象承载；
 * 语言以 {@code knowledge_base.language} 列为唯一真相源，
 * 不再经由 {@code rag_engine_config} JSONB 的 language 键流转。</p>
 *
 * @param id                 主键
 * @param name               知识库名称（全局唯一，≤128 字符）
 * @param description        描述
 * @param language           知识库语言（唯一真相源 = {@code knowledge_base.language} 列；
 *                           值域为 {@link KbLanguage} 十一语言全名、大小写不敏感，创建缺省显式落
 *                           {@code Chinese}；本聚合不重写值域样本，读取侧归一）
 * @param lifecycleStatus    生命周期状态（ACTIVE / DELETING / DELETE_FAILED 三态）
 * @param errorMessage       删除失败留痕（{@code knowledge_base.error_message} 列）：仅
 *                           DELETE_FAILED 态携带失败步骤与原因摘要，重删推回 DELETING 时清除
 * @param ragEngineConfig    RAG 引擎配置 JSON
 * @param dedupPolicy        去重策略 JSON
 * @param retrievalStrategy  检索策略 JSON
 * @param embeddingConfig    嵌入模型配置 JSON
 * @param multiModelConfig   多模态模型配置 JSON
 * @param entityTypeConfig   实体类型自定义配置 JSON
 * @param createdAt          创建时间
 * @param updatedAt          更新时间
 */
public record KnowledgeBase(
        Long id,
        String name,
        String description,
        String language,
        LifecycleStatus lifecycleStatus,
        String errorMessage,
        String ragEngineConfig,
        String dedupPolicy,
        String retrievalStrategy,
        String embeddingConfig,
        String multiModelConfig,
        String entityTypeConfig,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    private static final int NAME_MAX_LENGTH = 128;

    /** 语言缺省值：创建未指定时显式落库，对齐「未配置 / 空白视同 Chinese」的现行运行语义（不依赖列库默认值 ENGLISH） */
    private static final String DEFAULT_LANGUAGE = KbLanguage.Chinese.name();

    /**
     * 紧凑构造器：不变量校验。
     */
    public KnowledgeBase {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("知识库名称长度不能超过" + NAME_MAX_LENGTH + "个字符");
        }
        if (lifecycleStatus == null) {
            lifecycleStatus = LifecycleStatus.ACTIVE;
        }
    }

    /**
     * 创建新知识库。
     * <p>语言入参空白 / 缺省时显式落 {@code Chinese}；非空白按原文保留（值域校验在应用层完成，
     * 读取侧统一按 {@link KbLanguage} 归一）。</p>
     */
    public static KnowledgeBase create(String name, String description, String language,
                                       String ragEngineConfig, String dedupPolicy,
                                       String retrievalStrategy, String embeddingConfig,
                                       String multiModelConfig, String entityTypeConfig) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        String effectiveLanguage = StringUtils.isBlank(language) ? DEFAULT_LANGUAGE : language;
        return new KnowledgeBase(null, name, description, effectiveLanguage, LifecycleStatus.ACTIVE, null,
                ragEngineConfig, dedupPolicy, retrievalStrategy,
                embeddingConfig, multiModelConfig, entityTypeConfig, now, now);
    }

    /**
     * 从数据库恢复（language 与 errorMessage 均为列值原样还原，不做归一改写）。
     *
     * @param errorMessage 删除失败留痕（{@code error_message} 列原值），非 DELETE_FAILED 态通常为空白
     */
    public static KnowledgeBase restore(Long id, String name, String description, String language,
                                        LifecycleStatus lifecycleStatus, String errorMessage,
                                        String ragEngineConfig, String dedupPolicy,
                                        String retrievalStrategy, String embeddingConfig,
                                        String multiModelConfig, String entityTypeConfig,
                                        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return new KnowledgeBase(id, name, description, language, lifecycleStatus, errorMessage,
                ragEngineConfig, dedupPolicy, retrievalStrategy,
                embeddingConfig, multiModelConfig, entityTypeConfig, createdAt, updatedAt);
    }

    /**
     * 更新配置（返回新实例，不可变语义；语言与名称等非配置分量原样保留）。
     */
    public KnowledgeBase withUpdatedConfig(String ragEngineConfig, String dedupPolicy,
                                           String retrievalStrategy, String embeddingConfig,
                                           String multiModelConfig, String entityTypeConfig) {
        return new KnowledgeBase(id, name, description, language, lifecycleStatus, errorMessage,
                ragEngineConfig, dedupPolicy, retrievalStrategy,
                embeddingConfig, multiModelConfig, entityTypeConfig,
                createdAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    /**
     * 更新语言（返回新实例，不可变语义）。
     * <p>语言属库级真相源而非 JSONB 配置段，独立于 {@link #withUpdatedConfig} 单独复制。</p>
     *
     * @param language 新语言值（值域校验在应用层完成，本方法按原文承载）
     * @return 携带新语言且更新时间刷新的新实例
     */
    public KnowledgeBase withLanguage(String language) {
        return new KnowledgeBase(id, name, description, language, lifecycleStatus, errorMessage,
                ragEngineConfig, dedupPolicy, retrievalStrategy,
                embeddingConfig, multiModelConfig, entityTypeConfig,
                createdAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    /**
     * 标记为删除中（首删受理：ACTIVE → DELETING）。
     * <p>DELETE_FAILED 的重删推进不走本方法，见 {@link #clearFailureOnRedelete()}。</p>
     *
     * @return 处于 DELETING 的新实例
     * @throws IllegalStateException 当前状态不是 ACTIVE
     */
    public KnowledgeBase markDeleting() {
        if (lifecycleStatus != LifecycleStatus.ACTIVE) {
            throw new IllegalStateException("仅 ACTIVE 状态的知识库可发起删除");
        }
        return new KnowledgeBase(id, name, description, language, LifecycleStatus.DELETING, null,
                ragEngineConfig, dedupPolicy, retrievalStrategy,
                embeddingConfig, multiModelConfig, entityTypeConfig,
                createdAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    /**
     * 标记为删除失败（清退某步失败留痕：DELETING → DELETE_FAILED）。
     * <p>仅允许 DELETING → DELETE_FAILED：清退编排（rag BC）任一步失败即经本迁移落
     * {@code error_message} 留痕（形如 {@code [KB-CLEANUP] step=…}），对用户呈现
     * 「知识库不可用，请重新执行删除」；非源态迁移属编排错误，拒绝。</p>
     *
     * @param reason 失败步骤与原因摘要（截断后的留痕文案），空白表示不留具体原因
     * @return 携带失败留痕的 DELETE_FAILED 新实例
     * @throws IllegalStateException 当前状态不是 DELETING
     */
    public KnowledgeBase markFailed(String reason) {
        if (lifecycleStatus != LifecycleStatus.DELETING) {
            throw new IllegalStateException("仅删除中的知识库可标记为删除失败");
        }
        return new KnowledgeBase(id, name, description, language, LifecycleStatus.DELETE_FAILED, reason,
                ragEngineConfig, dedupPolicy, retrievalStrategy,
                embeddingConfig, multiModelConfig, entityTypeConfig,
                createdAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    /**
     * 重删受理（DELETE_FAILED → DELETING，同时清除失败留痕）。
     * <p>失败库的处置权归用户：重删即从断点全链幂等续跑，error_message 留痕随之清除；
     * 非 DELETE_FAILED 态调用属编排错误，拒绝。</p>
     *
     * @return 清除留痕后的 DELETING 新实例
     * @throws IllegalStateException 当前状态不是 DELETE_FAILED
     */
    public KnowledgeBase clearFailureOnRedelete() {
        if (lifecycleStatus != LifecycleStatus.DELETE_FAILED) {
            throw new IllegalStateException("仅删除失败的知识库可重删清痕推回删除中");
        }
        return new KnowledgeBase(id, name, description, language, LifecycleStatus.DELETING, null,
                ragEngineConfig, dedupPolicy, retrievalStrategy,
                embeddingConfig, multiModelConfig, entityTypeConfig,
                createdAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    /**
     * 判断是否处于可操作（ACTIVE）状态。
     */
    public boolean isActive() {
        return lifecycleStatus == LifecycleStatus.ACTIVE;
    }
}
