package com.linkroa.deepdataagent.rag.domain.port;

/**
 * LLM 对话端口（RAG 摄入统一出口：实体抽取、gleaning、描述摘要均经此端口）。
 * <p>实现方必须保证调用发生在数据库事务之外（事务内严禁远程调用）；
 * 生产装配的实现内嵌 {@code llm_cache} 缓存：以
 * {@code md5(model + prompt + 归一化参数)} 为键、按 {@code (kb_id, cache_key)} 复合唯一键
 * 查询，命中直接回放跳过远程调用，未命中执行后回写。</p>
 *
 * @author DeepDataAgent
 */
public interface LlmClient {

    /**
     * 发起一次对话补全（可能命中缓存回放）。
     *
     * @param request 对话请求（kbId/模型引用/提示词）
     * @return 模型回复与 token 消耗
     * @throws IllegalArgumentException 请求参数非法
     * @throws RuntimeException         模型解析失败或远程调用失败
     */
    LlmChatResult chat(LlmChatRequest request);
}
