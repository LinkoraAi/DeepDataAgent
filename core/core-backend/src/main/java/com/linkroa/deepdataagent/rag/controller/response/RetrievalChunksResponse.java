package com.linkroa.deepdataagent.rag.controller.response;

import java.util.List;

/**
 * 检索切片形态响应 DTO（{@code POST /api/v1/rag/retrievals/chunks} 的唯一数据体）。
 * <p>对外只承诺「命中切片」一项内容：不返回 LLM 答案、引用列表与降级标记。
 * 该形态在精排（Stage 3）后即止，不构建上下文也不作答，故切片为<b>精排后全量</b>
 * （按精排序），条数不受请求中的 token 预算约束。无命中时 {@code chunks} 为空表而非
 * {@code null}，调用方无需二次判空。</p>
 *
 * @param chunks 命中切片明细列表（按精排序；空表非 {@code null}）
 */
public record RetrievalChunksResponse(
        List<ChunkReferenceResponse> chunks
) {
}
