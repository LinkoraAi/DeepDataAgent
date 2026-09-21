package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

/**
 * 分块来源标识枚举（chunk.source_type 列，写入即定、MUST NOT 在后续操作中被改写）。
 * <p>判别依据是显式数据而非对既有列的推断：{@code created_by} 在人工入口与解析链都会回落
 * 系统缺省值、{@code s3_file} 与 {@code chunk_content_type} 均不表达来源，故来源单列承载。</p>
 * <p>消费面：人工删除入口仅接受 {@link #MANUAL} 分块（解析产生的分块删除会把图谱账本
 * 变成死引用，改由重新解析 / 删除文档承接）；切片物理删除原语按「本批是否含 {@link #PARSED}」
 * 数据推导是否执行图谱账本收敛，不再由调用方声明。</p>
 *
 * @author DeepDataAgent
 */
public enum ChunkSource {

    /** 解析产生：摄入管线解析 + 分块 + 整篇替换落库的切片，可产生图谱贡献。 */
    PARSED,

    /** 人工新增：管理侧手动创建的切片，构造过程不产生任何图谱贡献。 */
    MANUAL
}
