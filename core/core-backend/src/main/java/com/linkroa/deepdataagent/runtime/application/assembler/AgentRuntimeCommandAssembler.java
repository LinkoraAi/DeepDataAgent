package com.linkroa.deepdataagent.runtime.application.assembler;

import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.application.command.TerminateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.query.ListSessionsQuery;
import com.linkroa.deepdataagent.runtime.application.query.ReplayQuery;
import com.linkroa.deepdataagent.runtime.controller.request.CreateSessionRequest;
import org.mapstruct.Mapper;

/**
 * 运行时 Request → Command/Query 转换装配器。
 * <p>创建会话需先由接口层解析 {@code agent} 的最新发布号并序列化 {@code metadata} 对象，
 * 再经本装配器组装命令（对齐 Managed Agents 创建时不传版本号、metadata 为对象）。</p>
 */
@Mapper(componentModel = "spring")
public interface AgentRuntimeCommandAssembler {

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
     * 会话列表查询（limit/page 对齐 Managed Agents 命名，内部转为 offset 分页）。
     */
    default ListSessionsQuery toListQuery(String userId, Integer limit, Integer page) {
        return new ListSessionsQuery(userId, page == null ? 1 : page, limit == null ? 20 : limit);
    }

    /**
     * 回放查询（事件流 / 事件历史端点）。
     */
    default ReplayQuery toReplayQuery(String sessionId, long afterSequenceNum) {
        return new ReplayQuery(sessionId, Math.max(afterSequenceNum, 0));
    }
}