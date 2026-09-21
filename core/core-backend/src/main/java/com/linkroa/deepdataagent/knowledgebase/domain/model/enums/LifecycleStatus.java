package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

/**
 * 知识库生命周期状态枚举（三态）。
 * <p>状态流转：{@code ACTIVE → DELETING →（收口＝行物理删除）}；清退失败时
 * {@code DELETING → DELETE_FAILED}，重删时 {@code DELETE_FAILED → DELETING}。</p>
 * <p>{@code DELETED} 常量已移除：收口为单条条件 DELETE，行缺失（404）即「已删除」的唯一表达，
 * 本枚举不再承载任何「已删除」持久态。</p>
 */
public enum LifecycleStatus {

    /** 激活（可用） */
    ACTIVE,

    /** 删除中（级联清退进行中，或清退任务投递失败停留待恢复） */
    DELETING,

    /** 删除失败（清退某步异常留痕，error_message 携带失败步骤，由用户重删推回 DELETING 续跑） */
    DELETE_FAILED
}
