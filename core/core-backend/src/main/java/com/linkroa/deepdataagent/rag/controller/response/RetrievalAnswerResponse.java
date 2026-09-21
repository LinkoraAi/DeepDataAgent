package com.linkroa.deepdataagent.rag.controller.response;

/**
 * 检索答案形态响应 DTO（{@code POST /api/v1/rag/retrievals/answer} 的唯一数据体）。
 * <p>对外只承诺「LLM 最终回答」一项内容：不返回引用列表、命中切片明细与降级标记。
 * 检索链路任一阶段降级仍按编排层降级矩阵兜底并写入服务端摘要日志，
 * 但不再随 HTTP 响应发布；答案生成（Stage 5）降级时本字段为 {@code null}，
 * 属调用方须容忍的唯一空值形态。</p>
 *
 * @param answer 最终答案文本（答案生成降级时为 {@code null}）
 */
public record RetrievalAnswerResponse(
        String answer
) {
}
