package com.linkroa.deepdataagent.knowledgebase.api;

/**
 * 在飞任务类型（在飞任务注册表的键分段与成员语义载体）。
 * <p>每一类可跨重启恢复的异步任务对应一个本枚举常量，各自占用本实例的一个注册表键。
 * {@link #keySegment()} 为注册表键的第三段固定字面量，MUST NOT 随限界上下文、部署环境或
 * 配置项变化（键形如 {@code <实例标识>:knowledgebase-rag:<任务类型>}）。</p>
 *
 * @author DeepDataAgent
 */
public enum InFlightTaskType {

    /** 文档解析任务（成员语义：该文档已入队或正在解析，尚未收敛到终态）。 */
    DOC_INGESTION("docIngestionTask"),

    /** 文档清退任务（成员语义：该文档处于删除链清退中，尚未收敛到终态）。 */
    DOC_CLEANUP("docCleanupTask"),

    /** 知识库清退任务（成员语义：该知识库处于整库清退中，尚未收敛到终态）。 */
    KB_CLEANUP("kbCleanupTask");

    /** 注册表键的任务类型段固定字面量。 */
    private final String keySegment;

    /**
     * 构造在飞任务类型。
     *
     * @param keySegment 注册表键的任务类型段固定字面量
     */
    InFlightTaskType(String keySegment) {
        this.keySegment = keySegment;
    }

    /**
     * 获取注册表键的任务类型段。
     *
     * @return 任务类型段字面量（如 {@code docIngestionTask}）
     */
    public String keySegment() {
        return keySegment;
    }
}