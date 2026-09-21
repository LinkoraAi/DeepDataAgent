package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

/**
 * 文档处理状态枚举（六态生命周期）。
 * <p>正常链：PENDING → PROCESSING → PROCESSED / FAILED（FAILED 可重新解析回到 PENDING）</p>
 * <p>删除链：任意非 DELETING 态 → DELETING →（收口＝行物理删除）；清退失败时
 * DELETING → DELETE_FAILED；重新删除时 DELETE_FAILED → DELETING（幂等续跑）</p>
 * <p>DELETING / DELETE_FAILED 两态拒绝重新解析、摄入认领与切片回写；
 * {@code DELETED} 常量已移除——收口为条件 DELETE，行缺失（404）即「已删除」的唯一表达。</p>
 */
public enum DocumentStatus {

    /** 待处理（已入库，等待解析） */
    PENDING,

    /** 处理中（摄入管线在飞：自出队领取起，经解析分块、切片落库、实体关系抽取，直到图合并与向量入库结束） */
    PROCESSING,

    /** 已处理（摄入完整结束：切片、图谱与向量均已就绪，对检索可见） */
    PROCESSED,

    /** 处理失败 */
    FAILED,

    /** 删除中（删除链已启动，正在掐灭在飞任务并分批清退派生数据） */
    DELETING,

    /** 删除失败（清退批次异常，可从剩余数据幂等续跑重新删除） */
    DELETE_FAILED
}
