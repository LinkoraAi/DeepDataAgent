package com.linkroa.deepdataagent.agent.api;

/**
 * 模型配置归属查询契约（跨限界上下文（BC）只读能力面，未来 Feign 落点）。
 *
 * <p>由 agent BC 定义并发布，供需要校验「所引用的模型配置是否归属当前用户」的消费方
 * （当前为 knowledgebase BC 的知识库创建 / 更新写入校验）使用。依赖方向：
 * 消费方依赖本契约、由 agent BC 提供实现，<b>不引入反向编译依赖</b>。</p>
 *
 * <p><b>能力边界</b>：</p>
 * <ul>
 *     <li><b>只读零副作用</b>：MUST NOT 创建 / 修改 / 删除任何模型配置；</li>
 *     <li><b>不泄露存在性</b>：配置不存在与归属不匹配统一返回 {@code false}，
 *     不以不同形态区分两者（消费方据此映射 404，不泄露存在性）；</li>
 *     <li><b>不返回凭证</b>：MUST NOT 返回任何模型端点、密钥或凭证材料。</li>
 * </ul>
 *
 * <p><b>时序定位</b>：本契约服务于<b>写入期</b>归属校验——运行期（摄入 / 检索）线程
 * 无请求者身份上下文，MUST NOT 依赖运行期补校验兜底。</p>
 */
public interface ModelProfileReferenceApi {

    /**
     * 判定模型配置是否存在且归属指定用户。
     *
     * @param profileId 模型配置业务标识（profileId），可为空
     * @param ownerId   归属用户 ID，可为空
     * @return true 表示存在且归属一致；配置不存在、已删除或归属不匹配一律返回 false
     */
    boolean existsOwned(String profileId, Long ownerId);
}