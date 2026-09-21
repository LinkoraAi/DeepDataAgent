package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.ObjectUtils;

/**
 * 分块落库态值对象（仅内存，不落库）——Stage 3 落库后的分块态：
 * 真实分块主键与内存中间态（{@link ChunkVO}）的绑定体，抽取链路唯一身份输入。
 * <p>以构造即真值的方式从根源消灭 sequence 占位重写层：抽取产物（实体 / 关系）的溯源来源键
 * 自出生起即为真实 chunkId，占位值在类型层面不可能进入图谱账本。</p>
 *
 * @param sequence 块在文档内的序号（从 1 开始，与 {@link ChunkVO#sequence()} 同值，仅作定位/命名展示用）
 * @param chunkId  Stage 3 落库后回传的真实分块主键（抽取溯源来源键的唯一来源）
 * @param chunk    落库前的分块中间态（正文 / token / 来源内容块）
 */
public record PersistedChunkVO(Integer sequence, Long chunkId, ChunkVO chunk) {

    /**
     * 紧凑构造器：落库态绑定不变量校验。
     */
    public PersistedChunkVO {
        if (ObjectUtils.isEmpty(sequence) || sequence < 1) {
            throw new IllegalArgumentException("切片序号必须从 1 开始");
        }
        if (ObjectUtils.isEmpty(chunkId)) {
            throw new IllegalArgumentException("切片主键不能为空");
        }
        if (ObjectUtils.isEmpty(chunk)) {
            throw new IllegalArgumentException("切片中间态不能为空");
        }
    }

    /**
     * 分块正文（代理 {@link ChunkVO#text()}）。
     *
     * @return 分块后的正文
     */
    public String text() {
        return chunk.text();
    }

    /**
     * 来源内容块（代理 {@link ChunkVO#block()}）。
     *
     * @return 来源内容块，可为 null（纯文本块无来源块元数据时）
     */
    public ContentBlockVO block() {
        return chunk.block();
    }
}
