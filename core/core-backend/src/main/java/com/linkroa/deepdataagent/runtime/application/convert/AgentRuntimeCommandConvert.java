package com.linkroa.deepdataagent.runtime.application.convert;

import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.command.ResolveHumanConfirmationCommand;
import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.application.command.TerminateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.query.ListSessionsQuery;
import com.linkroa.deepdataagent.runtime.application.query.ReplayQuery;
import com.linkroa.deepdataagent.runtime.controller.request.CreateSessionRequest;
import com.linkroa.deepdataagent.runtime.controller.request.ResolveHumanConfirmationRequest;
import com.linkroa.deepdataagent.runtime.domain.model.SessionCursor;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * 运行时 Request → Command/Query 转换装配器。
 * <p>创建会话需先由接口层解析 {@code agent} 的最新发布号并序列化 {@code metadata} 对象，
 * 再经本装配器组装命令（对齐 Managed Agents 创建时不传版本号、metadata 为对象）。</p>
 */
@Mapper
public interface AgentRuntimeCommandConvert {

    AgentRuntimeCommandConvert INSTANCE = Mappers.getMapper(AgentRuntimeCommandConvert.class);

    /**
     * 创建会话请求 → 命令（userId 由接口层内部默认身份注入，agentVersion 为解析到的最新发布号）。
     */
    default CreateSessionCommand toCreateCommand(String userId, CreateSessionRequest request,
                                                 String agentVersion, String metadataJson) {
        return new CreateSessionCommand(userId, request.agent(), agentVersion, request.title(), metadataJson);
    }

    /**
     * 发送事件 → 发送消息命令（message 已由接口层从 input 中提取为纯文本）。
     */
    default SendMessageCommand toSendCommand(String sessionId, String message, String runId) {
        return new SendMessageCommand(sessionId, message, runId);
    }

    /**
     * 终止会话命令。
     */
    default TerminateSessionCommand toTerminateCommand(String sessionId) {
        return new TerminateSessionCommand(sessionId);
    }

    /**
     * 人工确认指令 → 命令（确认 / 拒绝）。
     */
    default ResolveHumanConfirmationCommand toResolveHumanConfirmationCommand(String sessionId,
                                                                              ResolveHumanConfirmationRequest request) {
        return new ResolveHumanConfirmationCommand(sessionId, Boolean.TRUE.equals(request.confirmed()));
    }

    /**
     * 会话列表查询（limit/agent_id/statuses[]/cursor 对齐 Managed Agents 命名，转为游标分页）。
     */
    default ListSessionsQuery toListQuery(String userId, String agentId, List<String> statuses,
                                          String cursor, Integer limit) {
        return new ListSessionsQuery(
                userId,
                agentId == null || agentId.isBlank() ? null : agentId,
                parseStatuses(statuses),
                SessionCursor.parse(cursor),
                limit == null ? 20 : limit);
    }

    /**
     * 解析状态过滤集合；非法状态名直接抛参错（对齐领域状态枚举边界）。
     */
    static List<AgentSessionStatus> parseStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return statuses.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> {
                    try {
                        return AgentSessionStatus.valueOf(s);
                    } catch (IllegalArgumentException ex) {
                        throw new IllegalArgumentException("非法会话状态: " + s);
                    }
                })
                .toList();
    }

    /**
     * 回放查询（事件流 / 事件历史端点）。
     */
    default ReplayQuery toReplayQuery(String sessionId, long afterSequenceNum) {
        return new ReplayQuery(sessionId, Math.max(afterSequenceNum, 0));
    }
}