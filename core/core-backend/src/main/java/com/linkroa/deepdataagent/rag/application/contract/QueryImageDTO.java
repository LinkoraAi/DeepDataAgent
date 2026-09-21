package com.linkroa.deepdataagent.rag.application.contract;

/**
 * 检索请求附图 DTO（通路 A：RAG 检索入参携带的「知识库从未收录」内联图片，
 * <p>图片以 <b>base64 内联</b> 形态随请求体传入，仅作一次性转译输入：
 * MUST NOT 写入知识库切片、对象存储媒体资产或图谱（附件零持久化），
 * 其生命周期由调用方（agent 会话附件）管理。数量、单图字节与内容类型合法性
 * 由 {@code QueryImageValidator} 在入参校验层完成，解码产物为
 * {@code LlmImage}（见 {@link RetrievalQuery#images()}）。</p>
 *
 * @param contentType 图片 MIME 类型（如 {@code image/jpeg}；白名单与魔数一致性由校验层核验）
 * @param data        图片内容的 base64 编码串（不含 {@code data:} 前缀，解码后受单图字节上限约束）
 * @author DeepDataAgent
 */
public record QueryImageDTO(
        String contentType,
        String data
) {
}
