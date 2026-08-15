package com.linkroa.deepdataagent.runtime.infrastructure.persistence;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.ExecutionRound;
import com.linkroa.deepdataagent.runtime.domain.model.RunTrace;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.RoundStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.SpanKind;
import com.linkroa.deepdataagent.runtime.domain.model.enums.SpanStatus;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.AgentSessionEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.ChatEventEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.ExecutionRoundEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.RunTraceEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * 领域对象 ⇄ 持久化实体的 MapStruct 转换器（runtime BC）。
 * <p>四个模型字段与建表约定一一对应，由 MapStruct 自动生成映射（含 record 目标）；
 * 枚举以字符串（{@code name()}）落库与建表注释一致，反查经 default 方法兜底处理空值/脏数据。</p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface RuntimePersistenceMapper {

    // ===== AgentSession =====

    AgentSessionEntity toEntity(AgentSession session);

    AgentSession toDomain(AgentSessionEntity entity);

    // ===== ExecutionRound =====

    ExecutionRoundEntity toEntity(ExecutionRound round);

    ExecutionRound toDomain(ExecutionRoundEntity entity);

    // ===== ChatEvent =====

    ChatEventEntity toEntity(ChatEvent event);

    ChatEvent toDomain(ChatEventEntity entity);

    // ===== RunTrace =====

    RunTraceEntity toEntity(RunTrace trace);

    RunTrace toDomain(RunTraceEntity entity);

    // ===== 枚举兜底反解（String → enum，null/空白安全） =====

    default AgentSessionStatus mapSessionStatus(String value) {
        return enumValueOf(AgentSessionStatus.class, value);
    }

    default RoundStatus mapRoundStatus(String value) {
        return enumValueOf(RoundStatus.class, value);
    }

    default ChatEventType mapChatEventType(String value) {
        return enumValueOf(ChatEventType.class, value);
    }

    default SpanKind mapSpanKind(String value) {
        return enumValueOf(SpanKind.class, value);
    }

    default SpanStatus mapSpanStatus(String value) {
        return enumValueOf(SpanStatus.class, value);
    }

    private <T extends Enum<T>> T enumValueOf(Class<T> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Enum.valueOf(type, value);
    }
}