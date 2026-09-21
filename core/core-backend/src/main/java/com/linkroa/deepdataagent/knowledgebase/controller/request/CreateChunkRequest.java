package com.linkroa.deepdataagent.knowledgebase.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建切片请求（人工新增）。
 *
 * @param kbId             所属知识库ID
 * @param documentId       所属文档ID
 * @param sequence         服务端恒忽略：序号由服务端计算为该文档当前最大序号加一
 *                         （人工块恒位于文档末尾），字段仅为兼容存量客户端请求体保留
 * @param tokens           切片 token 数量
 * @param chunkContent     切片内容
 * @param originalItem     多模态原始信息 JSON
 * @param chunkContentType 内容形态（对应 ChunkContentType 枚举名，为空默认 TEXT）
 * @param sourceFileName   来源文件名
 */
public record CreateChunkRequest(

        @NotNull(message = "知识库ID不能为空")
        Long kbId,

        @NotNull(message = "文档ID不能为空")
        Long documentId,

        Integer sequence,

        Integer tokens,

        @NotBlank(message = "切片内容不能为空")
        String chunkContent,

        String originalItem,

        String chunkContentType,

        String sourceFileName
) {
}
