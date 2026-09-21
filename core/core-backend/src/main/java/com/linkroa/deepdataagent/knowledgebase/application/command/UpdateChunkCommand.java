package com.linkroa.deepdataagent.knowledgebase.application.command;

/**
 * 更新切片内容命令（人工修正解析结果）。
 *
 * @param id           切片ID
 * @param chunkContent 新的块内容
 * @param tokens       新的 token 数量，为空时由服务端按内容长度重新估算
 */
public record UpdateChunkCommand(
        Long id,
        String chunkContent,
        Integer tokens
) {
}
