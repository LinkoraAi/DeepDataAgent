package com.linkroa.deepdataagent.agent.api;

/**
 * 模型配置归属与类型查询契约（跨限界上下文（BC）只读能力面，未来 Feign 落点）。
 *
 * <p>由 agent BC 定义并发布，供需要校验「所引用的模型配置是否归属当前用户 / 类型是否符合槽位」
 * 的消费方（当前为 knowledgebase BC 的知识库创建 / 更新 / 检索配置写入校验）使用。
 * 依赖方向：消费方依赖本契约、由 agent BC 提供实现，<b>不引入反向编译依赖</b>。</p>
 *
 * <p><b>能力边界</b>：</p>
 * <ul>
 *     <li><b>只读零副作用</b>：MUST NOT 创建 / 修改 / 删除任何模型配置；</li>
 *     <li><b>不泄露存在性</b>：配置不存在与归属不匹配统一返回 {@code null}，
 *     不以不同形态区分两者（消费方据此映射 404，不泄露存在性）；</li>
 *     <li><b>不返回凭证</b>：MUST NOT 返回任何模型端点、密钥或凭证材料；类型码不属凭证，
 *     允许返回以支撑槽位校验。</li>
 * </ul>
 *
 * <p><b>时序定位</b>：本契约服务于<b>写入期</b>归属与类型校验——运行期（摄入 / 检索）线程
 * 无请求者身份上下文，MUST NOT 依赖运行期补校验兜底。</p>
 */
public interface ModelProfileReferenceApi {

    /** 模型类型业务码：对话 LLM。 */
    int MODEL_TYPE_CHAT_LLM = 1;

    /** 模型类型业务码：视觉对话（多模态）。 */
    int MODEL_TYPE_CHAT_VLM = 2;

    /** 模型类型业务码：向量嵌入。 */
    int MODEL_TYPE_EMBEDDING = 3;

    /** 模型类型业务码：重排。 */
    int MODEL_TYPE_RERANK = 4;

    /**
     * 查询归属指定用户的模型配置类型业务码（一次查询同时完成归属与类型判定）。
     *
     * @param profileId 模型配置业务标识（profileId），可为空
     * @param ownerId   归属用户 ID，可为空
     * @return 类型业务码（1=对话LLM，2=视觉对话，3=嵌入，4=重排）；配置不存在、已删除或
     *         归属不匹配一律返回 {@code null}
     */
    Integer findOwnedModelTypeCode(String profileId, Long ownerId);
}
