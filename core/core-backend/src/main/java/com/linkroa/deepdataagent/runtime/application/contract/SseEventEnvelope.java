package com.linkroa.deepdataagent.runtime.application.contract;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 事件订阅（SSE / REST 回放 / 发送回显）对外输出的扁平 Event（发布语言 DTO）。
 * <p>公开事件契约形态：固定字段仅 {@code id}（evt_ 前缀，回放 + 实时订阅重合窗口
 * 幂等去重键，与 SSE {@code id:} 字段一致）、{@code type}（{@code {域}.{动作}}）
 * 与可选的 {@code processed_at}（RFC 3339 UTC，部分生成事件可缺省）；其余字段为该事件类型
 * 自有的顶层字段（如 {@code content} / {@code tool_use_id} / {@code stop_reason} /
 * {@code error} / span 相关字段），经 {@code attributes} 顶层展开，对外一级与嵌套字段统一
 * snake_case。</p>
 * <p>MUST NOT 输出 {@code object} / {@code role} / 固定槽 {@code content} 包装 /
 * {@code created_at} / {@code seq} / {@code payload} 等信封或内部字段；MUST NOT 出现
 * sessionId / eventId / roundId / eventType 等领域内部字段、审计字段与数据库自增主键。</p>
 * <p><b>包位置说明</b>：本类型是面向<b>外部客户端</b>的协议发布语言，而非跨 BC 契约，
 * 故不归 {@code api.dto}（无 {@code XxxApi} 入口）也不归 {@code application.dto}
 * （不含敏感材料）。同时它被 {@code application.convert}（装配）与
 * {@code infrastructure.sse}（编解码）共同消费，移入 {@code controller.response}
 * 会制造 {@code application → controller} 与 {@code infrastructure → controller}
 * 两条反向依赖，因此保留在 {@code application.contract}。</p>
 *
 * @param id          事件 ID（evt_ 前缀，业务幂等键）
 * @param type        事件类型（{@code {域}.{动作}}，如 agent.message）
 * @param processedAt 处理完成时间（可空：未处理事件缺省该字段）
 * @param attributes  类型自有顶层字段（原样展开的键值对）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SseEventEnvelope(
        String id,
        String type,
        @JsonProperty("processed_at") OffsetDateTime processedAt,
        @JsonAnyGetter Map<String, Object> attributes
) {

    public SseEventEnvelope {
        if (id == null || !id.startsWith("evt_")) {
            throw new IllegalArgumentException("事件 id 必须为 evt_ 前缀事件 ID");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("事件类型不能为空");
        }
        if (attributes == null) {
            attributes = Map.of();
        }
    }
}