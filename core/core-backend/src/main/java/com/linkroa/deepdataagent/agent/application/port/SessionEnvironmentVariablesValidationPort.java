package com.linkroa.deepdataagent.agent.application.port;

/**
 * 触发会话环境变量校验端口（跨 BC 消费单点，进程内出站端口）。
 *
 * <p>调度器的 {@code environment_variables} 是<b>触发会话的挂载材料</b>——触发时经
 * {@code SchedulerSessionApi} 原样交给 Session 创建路径，其形态契约（变量名 MUST 匹配
 * {@code [A-Za-z_][A-Za-z0-9_]*}、所有值 MUST 为字符串、拒绝保留名与 {@code CAW_}
 * 前缀、单值 8 KiB / 64 条 / 总 64 KiB）归 runtime BC 所有。本端口即 agent 侧消费该判定的唯一入口，
 * 由 {@code agent.infrastructure.assembly} 的 {@code Default...} 适配 runtime 侧既有校验器——
 * 规则只有一份，避免在 agent 侧复制判定导致两侧漂移。</p>
 *
 * <p><b>为何必须前置到创建 / 更新</b>：材料落库为 JSON 文本后，不合规形态只在<b>每次触发建 Session
 * 时</b>才被 400，调度器会被创建成「永远无法触发」的形态（与挂载引用缺失同类的静默停摆），
 * 故与挂载引用校验同点前置，形状违规即整批 400 且零落库。</p>
 */
public interface SessionEnvironmentVariablesValidationPort {

    /**
     * 校验触发会话环境变量 JSON 文本形态。
     *
     * @param environmentVariablesJson 环境变量 JSON 对象文本（null / 空白 = 未提供，直接通过）
     * @throws IllegalArgumentException 非 JSON 对象、变量名非法、值非字符串、命中保留名 / 前缀，
     *                                  或单值 / 条数 / 总字节越限（400 {@code invalid_request_error}）
     */
    void validate(String environmentVariablesJson);
}