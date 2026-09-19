package com.linkroa.deepdataagent.agent.controller.request;

import java.util.Map;

/**
 * 环境配置请求（{@code config}：type / packages / setup_script）。
 * <p>整体可缺省（缺省 {@code {"type":"cloud"}}）；{@code type} 空白时同样收敛为
 * {@code cloud}；{@code self_hosted} 仅支持 type 与可选 setup_script。</p>
 * <p>{@code packages} 为<b>对象映射</b>（非数组、非平铺字段），键集合的严格校验由
 * {@code EnvironmentCommandConvert} 承担（仅接受 {@code apt}/{@code pip}/{@code npm}
 * 三键，其余键 400），故此处保留原始 Map 形状、不做类型化绑定。</p>
 *
 * @param type         环境配置类型（cloud / self_hosted，可缺省）
 * @param packages     预装依赖对象映射（仅 apt/pip/npm 三键，可缺省）
 * @param setup_script 准备阶段 shell 脚本（≤64KB，可缺省；下划线键按契约约定）
 */
public record EnvironmentConfigRequest(
        String type,
        Map<String, Object> packages,
        String setup_script
) {
}