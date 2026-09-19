package com.linkroa.deepdataagent.runtime.infrastructure.assembly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.linkroa.deepdataagent.runtime.api.SchedulerSessionApi;
import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.api.dto.SchedulerLaunchDTO;
import com.linkroa.deepdataagent.runtime.application.service.execution.TurnExecutionService;
import com.linkroa.deepdataagent.runtime.application.service.session.SessionLifecycleService;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 调度器触发启动服务契约实现（{@link SchedulerSessionApi}）。
 * <p>执行路径：新建打标 Session（{@code triggerType} / {@code triggerId} 承载触发来源，
 * 每次触发 MUST 新建全新会话、绝不复用）→ 驱动首个 turn（异步虚拟线程托管，请求线程不立即阻塞）。
 * 会话装配版本 / 环境 / 挂载材料由调度器侧解析传入，本实现负责透传载荷的解释装配
 * （resources JSON ⇄ {@link SessionResource}、initial_events 合成首个 turn 消息），
 * 装配校验失败整体回滚、无会话残留。</p>
 */
@Component
public class DefaultSchedulerSessionApi implements SchedulerSessionApi {

    /** 触发消息缺省值（调度器未携带输入与首批事件时作为首个 turn 的启动提示）。 */
    private static final String DEFAULT_TRIGGER_INPUT = "调度触发，开始执行。";

    /** 首批事件用户消息类型标识（initial_events 数组项 type 取值）。 */
    private static final String USER_MESSAGE_TYPE = "user_message";

    /** 透传载荷解析（resources 键为会话存储 snake_case 形态） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<SessionResource>> SESSION_RESOURCE_LIST_TYPE =
            new TypeReference<>() {
            };

    /** 会话生命周期入口服务（decompose-command-facade 4.4 改线：调度触发建会话直连目标服务）。 */
    @Resource
    private SessionLifecycleService sessionLifecycleService;
    /** turn 执行链服务（decompose-command-facade 4.4 改线：驱动首个 turn 直连目标服务）。 */
    @Resource
    private TurnExecutionService turnExecutionService;

    @Override
    public String launch(SchedulerLaunchDTO request) {
        AgentSession session = sessionLifecycleService.createSession(new CreateSessionCommand(
                request.ownerId(),
                request.agentId(),
                request.versionNumber(),
                null,
                request.metadata(),
                request.triggerType(),
                request.triggerId(),
                parseResources(request.resourcesJson()),
                request.environmentId(),
                request.vaultIds(),
                request.environmentVariables()
        ));
        // 驱动首个 turn（异步虚拟线程执行，立即返回）；消息回退链：显式输入 → 首批事件合成 → 默认调度提示
        String message = StringUtils.defaultIfBlank(request.input(), composeInitialEvents(request.initialEvents()));
        turnExecutionService.sendMessageAsync(new SendMessageCommand(
                session.sessionId(), StringUtils.defaultIfBlank(message, DEFAULT_TRIGGER_INPUT)));
        return session.sessionId();
    }

    /**
     * 解析挂载资源 JSON 数组文本（空白归一为空列表；非法 JSON 抛参数异常语义由上层收敛）。
     */
    private List<SessionResource> parseResources(String resourcesJson) {
        if (StringUtils.isBlank(resourcesJson) || "[]".equals(resourcesJson.trim())) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(resourcesJson, SESSION_RESOURCE_LIST_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("调度器挂载资源 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 首批用户消息事件合成首个 turn 消息（按序以换行拼接各项 text；
     * 无有效事件返回 null，交由默认调度提示兜底）。
     */
    private String composeInitialEvents(String initialEventsJson) {
        if (StringUtils.isBlank(initialEventsJson) || "[]".equals(initialEventsJson.trim())) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(initialEventsJson);
            if (!root.isArray()) {
                throw new IllegalArgumentException("initial_events 必须为 JSON 数组");
            }
            List<String> texts = new ArrayList<>();
            for (JsonNode event : root) {
                JsonNode typeNode = event.get("type");
                JsonNode textNode = event.get("text");
                // 仅注入用户消息事件，其余类型本期忽略
                if (typeNode != null && USER_MESSAGE_TYPE.equals(typeNode.asText())
                        && textNode != null && StringUtils.isNotBlank(textNode.asText())) {
                    texts.add(textNode.asText());
                }
            }
            return texts.isEmpty() ? null : String.join("\n", texts);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("首批事件 JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
