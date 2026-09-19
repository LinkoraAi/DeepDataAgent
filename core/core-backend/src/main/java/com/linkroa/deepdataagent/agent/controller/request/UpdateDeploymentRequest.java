package com.linkroa.deepdataagent.agent.controller.request;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 更新调度器请求（merge-patch，6.5 管理面）。
 *
 * <p>以 <b>DELEGATING</b> 方式承载原始 JSON 对象体：普通 record 反序列化无法区分
 * 「键缺省」与「键显式为 null」，而 merge-patch 语义要求二者可分辨
 * （缺省=不改、显式 null=清空），故整体接收 {@code Map} 并由
 * {@code DeploymentCommandConvert.toUpdateCommand} 以 {@code containsKey} 裁决三态。</p>
 *
 * <p>可调键：{@code name / description / environment_id / environment_variables /
 * resources / vault_ids / initial_events / metadata / schedule}；未知键拒绝（400）。
 * 绑定关系（{@code agent_id / agent_version}，创建时固定、触发不漂移）与 webhook
 * 开通状态不可调。</p>
 *
 * @param fields 原始补丁体键值对（保留显式 null；空白补丁 = 空 Map）
 */
public record UpdateDeploymentRequest(Map<String, Object> fields) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public UpdateDeploymentRequest {
        // LinkedHashMap 快照保留键序与显式 null 值（Map.copyOf 会拒绝 null 值，禁用）
        fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
