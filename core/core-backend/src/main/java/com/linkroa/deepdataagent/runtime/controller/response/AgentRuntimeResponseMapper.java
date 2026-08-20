package com.linkroa.deepdataagent.runtime.controller.response;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 领域对象 → 响应 DTO 转换器（会话部分手工装配，承载 snake_case 契约字段映射）。
 * <p>会话 {@code metadata} 以 JSON 文本持久化，此处解析为对象后返回；{@code agent}
 * 快照目前仅返回摘要（id / type / version），完整智能体快照留待后续接入 Agent 台账。</p>
 */
@Component
public class AgentRuntimeResponseMapper {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 会话领域模型 → Managed Agents 对齐的 Session 响应。
     */
    public SessionResponse toSessionResponse(AgentSession session) {
        return new SessionResponse(
                session.sessionId(),
                "session",
                session.status().name().toLowerCase(Locale.ROOT),
                agentSnapshot(session),
                session.sandboxId(),
                session.title(),
                parseMetadata(session.metadata()),
                List.of(),
                null,
                session.createdAt(),
                session.updatedAt()
        );
    }

    /** Agent 摘要快照（完整快照留待后续）。 */
    private Map<String, Object> agentSnapshot(AgentSession session) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("id", session.agentId());
        agent.put("type", "agent");
        agent.put("version", parseVersionNumber(session.agentVersion()));
        return agent;
    }

    /** 发布号字符串 → int（非法收敛为 0）。 */
    private int parseVersionNumber(String agentVersion) {
        if (agentVersion == null || agentVersion.isBlank()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(agentVersion), 0);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** metadata JSON 文本 → 对象（非法/空白收敛为空 Map）。 */
    private Map<String, Object> parseMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(metadata, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            return Map.of();
        }
    }
}