package com.linkroa.deepdataagent.rag.domain.port;

/**
 * LLM 缓存键只读计算端口（归属登记等旁路场景专用）。
 * <p><b>契约要点</b>：本端口只做一件事——按与缓存客户端<b>完全一致</b>的算法算出
 * {@code md5(model + system + user + temperature [+ sha256(image)...])} 键值。
 * 计算过程<b>只读</b>：不查询 {@code llm_cache}、不写入任何缓存行、不触发任何缓存读写，
 * 也不改变缓存命中判定口径；调用本端口 MUST NOT 产生任何持久化副作用。</p>
 * <p><b>为何需要它</b>：缓存键的计算口径必须单一来源（两处重复实现必然漂移），
 * 但存在「调用点拿不到缓存客户端内部键值、又需要该键去登记归属」的场景——
 * 如多模态媒体描述发生在切片落库之前（拿不到分块主键），其归属只能在落库之后补登记，
 * 补登记所需的键值即在描述调用点经本端口计算并带出。</p>
 *
 * @author DeepDataAgent
 */
public interface LlmCacheKeyProvider {

    /**
     * 只读计算指定请求的缓存键（算法与缓存客户端逐字节一致）。
     * <p>不触发任何缓存读写、不改变命中判定，仅供归因等旁路使用；
     * 键要素含模型名（经模型配置解析）与提示词、温度、图片内容摘要，
     * 与 {@link LlmChatRequest#attributionChunkId()} 无关（归因字段不参与键计算）。</p>
     *
     * @param request LLM 对话请求（非空）
     * @return 32 位 MD5 十六进制缓存键
     * @throws IllegalArgumentException 请求为空
     */
    String cacheKeyOf(LlmChatRequest request);
}