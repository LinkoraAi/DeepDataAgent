package com.linkroa.deepdataagent.knowledgebase.application.command;

/**
 * 创建切片命令（人工新增入口，来源恒打「人工新增」标）。
 *
 * @param kbId             所属知识库ID
 * @param documentId       所属文档ID
 * @param sequence         已废弃入参：服务端一律忽略该值，序号由服务端计算为该文档当前最大序号
 *                         加一（人工块恒位于文档末尾）；字段仅为兼容存量客户端请求体保留
 * @param tokens           块的 token 数量
 * @param chunkContent     块内容（套模板后的最终文本）
 * @param originalItem     多模态原始信息 JSON
 * @param chunkContentType 内容形态（ChunkContentType 枚举名，为空按 TEXT 处理）
 * @param sourceFileName   来源文件名（冗余，便于引用展示）
 */
public record CreateChunkCommand(
        Long kbId,
        Long documentId,
        Integer sequence,
        Integer tokens,
        String chunkContent,
        String originalItem,
        String chunkContentType,
        String sourceFileName
) {
}
