package com.linkroa.deepdataagent.agent.api;

/**
 * Agent 对外引用计数服务契约（跨 BC 服务边界，未来 Feign 落点）。
 * <p>反向引用数据（agent_version.skills_json 技能绑定）归属 agent BC，
 * 删除被引用对象前需反向感知「是否仍被未删除的 Agent 版本引用」。
 * 本接口由 agent BC 提供正向查询能力，skill 等作为消费方依赖本接口调用，方向统一为
 * 「消费方依赖服务契约、提供方实现」的 Feign 友好模型。当前由 {@code DefaultAgentReferenceApi}
 * 进程内实现，未来接入 Feign 时仅需在本接口追加 {@code @FeignClient} 注解。</p>
 */
public interface AgentReferenceApi {

    /**
     * 统计 {@code skills_json} 中绑定指定技能的未删除 Agent 版本数（技能删除 / 版本删除引用校验）。
     *
     * @param skillId 技能业务 ID
     * @return 引用数（0 表示无绑定，可安全删除）
     */
    long countSkillBindings(String skillId);

    /**
     * 统计 {@code skills_json} 中绑定指定技能**特定版本**的未删除 Agent 版本数
     * （6.4 delete-version：custom 绑定在保存时已固定版本号，缺省版本绑定不参与本计数）。
     *
     * @param skillId 技能业务 ID
     * @param version 技能版本键（创建时刻 epoch 微秒字符串）
     * @return 引用数（0 表示该版本无绑定，可安全删除）
     */
    long countSkillVersionBindings(String skillId, String version);
}