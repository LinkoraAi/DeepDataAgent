package com.linkroa.deepdataagent.runtime.application.assembler;

import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.application.command.TerminateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.query.ListSessionsQuery;
import com.linkroa.deepdataagent.runtime.application.query.ReplayQuery;
import com.linkroa.deepdataagent.runtime.controller.request.CreateSessionRequest;
import com.linkroa.deepdataagent.runtime.controller.request.SendEventRequest;
import org.mapstruct.Mapper;
import org.springframework.util.StringUtils;

/**
 * 运行时 Request → Command/Query 转换装配器。
 * <p>字段同名直转由 MapStruct 自动生成；涉及默认值与类型约束的映射在
 * default 方法中显式实现（对齐 datasource CommandAssembler 先例）。</p>
 */
@Mapper(componentModel = "spring")
public interface AgentRuntimeCommandAssembler {

    /**
     * 创建会话请求 → 命令。
     */
    CreateSessionCommand toCreateCommand(CreateSessionRequest request);

    /**
     * 发送事件请求 → 发送消息命令（含接口层预生成的 runId）。
     */
    default SendMessageCommand toSendCommand(String sessionId, SendEventRequest request, String runId) {
        return new SendMessageCommand(sessionId, request.content(), runId);
    }

    /**
     * 终止会话命令。
     */
    default TerminateSessionCommand toTerminateCommand(String sessionId) {
        return new TerminateSessionCommand(sessionId);
    }

    /**
     * 会话列表查询（GET query 参数，默认分页）。
     */
    default ListSessionsQuery toListQuery(String userId, Integer page, Integer size) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        return new ListSessionsQuery(userId, page == null ? 1 : page, size == null ? 20 : size);
    }

    /**
     * 回放查询（事件流端点）。
     */
    default ReplayQuery toReplayQuery(String sessionId, long afterSequenceNum) {
        return new ReplayQuery(sessionId, Math.max(afterSequenceNum, 0));
    }
}