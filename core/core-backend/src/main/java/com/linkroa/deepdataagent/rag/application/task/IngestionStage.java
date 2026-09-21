package com.linkroa.deepdataagent.rag.application.task;

/**
 * 摄入管线失败阶段标记（封闭枚举）。
 * <p>作为 {@code errorMessage} 分类前缀的唯一来源：摄入 worker 的各失败出口按所处阶段
 * 归位到本枚举，集中定义、禁止散落字面量；前缀格式固定为 {@code [NAME] }，
 * 供日志检索与状态接口消费方稳定归因。未知阶段回落
 * {@link #UNKNOWN_STAGE}，保证前缀永不缺位。</p>
 *
 * @author DeepDataAgent
 */
public enum IngestionStage {

    /** 解析阶段：Stage 0 前置校验（上下文 / 源文件 / 模型引用）与文档解析。 */
    PARSE,

    /** 分块阶段：内容块切分与「未产出内容块」判定。 */
    CHUNK,

    /** 多模态描述阶段：多模态块 VLM 描述生成（内容驱动，有块且库配多模态模型时执行）。 */
    DESCRIBE,

    /** 向量化落库阶段：Stage 3 切片整篇替换（含嵌入向量化）。 */
    EMBED,

    /** 抽取阶段：Stage 4 实体与关系抽取及 sourceIds 溯源重写。 */
    EXTRACT,

    /** 图合并阶段：Stage 5 实体 / 关系合并写入图谱与向量。 */
    MERGE,

    /** 取消：用户操作触发的管线中断（与真实故障区分，不覆盖用户新状态）。 */
    CANCELLED,

    /** 未知阶段：未能归位到任一管线阶段的异常兜底标记。 */
    UNKNOWN_STAGE;

    /** 前缀模板：{@code [阶段名] }（后随原始异常摘要）。 */
    private static final String PREFIX_TEMPLATE = "[%s] ";

    /**
     * 该阶段的 errorMessage 分类前缀（稳定可枚举标记）。
     *
     * @return 形如 {@code [EXTRACT] } 的前缀文本
     */
    public String prefix() {
        return String.format(PREFIX_TEMPLATE, name());
    }
}
