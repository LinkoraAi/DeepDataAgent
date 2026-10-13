package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * 分块中间态值对象（仅内存，不落库）。
 * <p>sequence 由解析顺序决定，重解析时可能变化，故不作持久化键（幂等靠 documentId 级清空重建）。</p>
 * <p><b>多模态占位例外</b>：IMAGE / TABLE / EQUATION 等媒体块在 Stage-1 透传时正文可能为空
 * （MinerU 常不出 caption，{@code image_caption=[]}），此时允许空正文先立占位块，
 * 待 Stage-2 {@code MediaChunkTemplateService} 用模板正文（含大模型描述）原位重建——
 * 若在此处直接拒绝空正文，会使空 caption 媒体块整体丢失、切片表永无 IMAGE/TABLE 形态。</p>
 *
 * @param sequence 块在文档内的序号（从 1 开始）
 * @param text     分块后的正文（唯一参与向量化与 LLM 抽取的字段，落库前已剥坐标标签）
 * @param tokens   块的 token 数量（真实 encode 口径）
 * @param block    来源内容块（携带 heading / sidecar / positions 等检索期元数据）
 */
public record ChunkVO(Integer sequence, String text, Integer tokens, ContentBlockVO block) {

    /**
     * 紧凑构造器：分块不变量校验。
     */
    public ChunkVO {
        if (ObjectUtils.isEmpty(sequence) || sequence < 1) {
            throw new IllegalArgumentException("切片序号必须从 1 开始");
        }
        if (StringUtils.isBlank(text) && !isMultimodalPlaceholder(block)) {
            throw new IllegalArgumentException("切片内容不能为空");
        }
        if (ObjectUtils.isEmpty(tokens) || tokens < 0) {
            throw new IllegalArgumentException("切片 token 数不能为负");
        }
    }

    /**
     * 是否为「多模态占位块」：来源块为 IMAGE / TABLE / EQUATION 等媒体类型。
     * <p>此类块 Stage-1 正文允许为空（等待 Stage-2 模板化重建），不受非空正文不变量约束。</p>
     *
     * @param block 来源内容块，可为 null
     * @return true 表示多模态占位块
     */
    private static boolean isMultimodalPlaceholder(ContentBlockVO block) {
        return ObjectUtils.isNotEmpty(block) && block.isMultimodal();
    }
}