package com.linkroa.deepdataagent.runtime.application.validation;

import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 事件类型过滤集合应用级校验（协议层零领域依赖下沉：控制器不再直接触碰 {@link ChatEventType}）。
 * <p>列表路径（{@code GET /sessions/{id}/events} 与线程作用域嵌套端点）的 {@code types}
 * 取值口径为<b>静默忽略未知类型</b>——非权威事件表成员既不报错也不命中；与入站路径
 * 「未知类型 400 {@code unknown_event_type}」（见 {@code InboundEventValidator.parseKnownType}）
 * 严格区分。</p>
 */
public final class EventTypeValidator {

    private EventTypeValidator() {
    }

    /**
     * 归一事件类型过滤集合：保留权威事件表成员，静默剔除未知与空白取值。
     * <p>类型名与权威事件表取值<b>精确匹配</b>（大小写不一致按未知处理并静默剔除），重复取值去重。</p>
     *
     * @param types 请求携带的事件类型原值列表（可空 / 空 = 不过滤）
     * @return 已知事件类型集合（保序、去重；空表示不过滤）
     */
    public static List<ChatEventType> filterKnown(List<String> types) {
        if (types == null || types.isEmpty()) {
            return List.of();
        }
        return types.stream()
                .filter(StringUtils::isNotBlank)
                .filter(ChatEventType::isKnown)
                .map(ChatEventType::fromValue)
                .distinct()
                .toList();
    }
}