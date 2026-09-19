package com.linkroa.deepdataagent.runtime.controller.request;

import java.util.Map;

/**
 * 更新会话请求（契约端点 {@code POST /sessions/{session_id}}）：仅可变属性可提交。
 * <p>{@code title / metadata / environment_variables} 均为「缺省即不改」语义——
 * metadata 与既有元数据浅合并、environment_variables 提供即整体替换；
 * {@code agent / environment_id / status} 为不可更新属性，显式携带非空值即被拒（400，
 * 装配器守卫，见 {@code AgentRuntimeCommandConvert#toUpdateCommand}）。</p>
 *
 * @param title               新标题（null / 空白=不改）
 * @param metadata            增量元数据对象（null=不改；浅合并）
 * @param environmentVariables 会话级环境变量键值对（null=不改；提供即整体替换）。
 *                             值类型声明为 {@code Object} 而非 {@code String}：绑定层不做标量强转，
 *                             由应用级校验器按「所有值 MUST 为字符串」判定并拒绝非字符串（→ 400），
 *                             与创建路径共用同一份判定
 * @param agent               不可更新属性探测位（携带即 400）
 * @param environmentId       不可更新属性探测位（携带即 400）
 * @param status              不可更新属性探测位（携带即 400）
 */
public record UpdateSessionRequest(
        String title,
        Map<String, Object> metadata,
        Map<String, Object> environment_variables,
        Map<String, Object> agent,
        String environment_id,
        String status
) {
}
