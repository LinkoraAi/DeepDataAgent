package com.linkroa.deepdataagent.runtime.controller.request;

import com.linkroa.deepdataagent.runtime.application.command.SessionResourceItem;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * 创建会话请求（契约端点 {@code POST /sessions}）。
 * <p>挂载已全量生效：{@code resources} 支持 file / github_repository / memory_store
 * 三类（见 {@link SessionResourceItem}）；创建时校验运行环境、挂载文件、保管库与
 * 挂载记忆库的存在性与归属（不存在 / 越权 → 404，全有或全无）；
 * {@code environment_id} 必须指向存在且归属当前用户的运行环境，
 * {@code self_hosted} 执行平面显式拒绝（400 不支持的执行平面）；
 * {@code vault_ids} 与 {@code environment_variables} 随会话落库，
 * 运行时装配注入数据面。</p>
 * <p>{@code agent} 声明为 {@code Object}：公开契约允许两种等价提交形态——字符串 Agent ID，
 * 或对象 {@code {id, type:"agent", version?}}（version 省略 / {@code 0} = 绑定激活版本）。
 * 绑定层不做强类型反序列化，形态判别与归一由
 * {@link com.linkroa.deepdataagent.runtime.application.convert.AgentRuntimeCommandConvert#toAgentReference(Object)}
 * 完成，非法形态 → 400（强类型声明会把对象形态直接打成反序列化 400，使显式版本无法表达）。</p>
 * <p>{@code environment} / {@code vaults} / {@code memory_store_ids} /
 * {@code delta_flush_interval_ms} 为<b>已废止</b>遗留字段，仅用于形态探测：
 * 任一非空提交即 400（不得静默忽略，否则客户端会「创建成功但挂载丢失」）。
 * 这四个字段 MUST NOT 参与命令装配。</p>
 *
 * @param agent                   绑定的智能体（字符串 ID 或 {@code {id, type:"agent", version?}} 对象）
 * @param environment_id          绑定的运行环境 ID（校验存在且归属当前用户）
 * @param title                   会话标题（可空）
 * @param resources               挂载资源列表（可空，file / github_repository / memory_store）
 * @param vault_ids               保管库 ID 列表（可空，校验存在且归属当前用户）
 * @param metadata                业务自定义元数据（可空）
 * @param environment_variables   会话级环境变量键值对（可空，装配时注入执行环境）。
 *                                值类型声明为 {@code Object} 而非 {@code String}：绑定层不做标量强转，
 *                                由应用级校验器按「所有值 MUST 为字符串」判定并拒绝非字符串
 *                                （数字 / 布尔 / null / 对象 / 数组 → 400），
 *                                避免「协议绑定静默转成字符串」使该规则被架空
 * @param environment             已废止的内联环境字段（非空即 400）
 * @param vaults                  已废止的旧保管库列表字段（非空即 400）
 * @param memory_store_ids        已废止的顶层记忆库列表字段（非空即 400）
 * @param delta_flush_interval_ms 已废止的增量帧冲刷间隔字段（非空即 400）
 */
public record CreateSessionRequest(
        Object agent,

        @NotBlank(message = "运行环境ID不能为空")
        String environment_id,

        String title,
        List<SessionResourceItem> resources,
        List<String> vault_ids,
        Map<String, Object> metadata,
        Map<String, Object> environment_variables,
        Object environment,
        List<Object> vaults,
        List<Object> memory_store_ids,
        Object delta_flush_interval_ms
) {
}
