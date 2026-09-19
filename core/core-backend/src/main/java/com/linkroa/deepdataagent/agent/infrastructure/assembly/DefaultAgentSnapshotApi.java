package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.api.AgentSnapshotApi;
import com.linkroa.deepdataagent.agent.api.dto.AgentSnapshotDTO;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.domain.repository.AgentDefinitionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 嵌入快照服务契约进程内实现（裁剪规则的权威落点，6.3 / 3.9）。
 * <p>按 Session 嵌入 Agent 快照裁剪规则装配：{@code created_at / updated_at / archived /
 * archived_at / metadata} 不输出、系统提示词以 {@code system} 键输出、按提交形态回显
 * {@code model / tools / mcp_servers / skills}（台账缺列即省略键）；{@code multiagent} 恒输出
 * {@code null}（写入侧非空 multiagent 一律 400，快照 MUST NOT 展开 coordinator {@code agents[]}
 * 阵列——旧展开语义已废止）。</p>
 * <p>线程快照（Session Thread）复用本快照后由消费方移除 {@code multiagent} 键
 * （「额外去掉 multiagent 字段」），契约本身保持 Session 形态。</p>
 */
@Service
public class DefaultAgentSnapshotApi implements AgentSnapshotApi {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private AgentDefinitionRepository definitionRepository;
    @Resource
    private AgentVersionRepository versionRepository;
    @Resource
    private com.linkroa.deepdataagent.agent.application.service.ModelCatalogService modelCatalogService;

    @Override
    public AgentSnapshotDTO resolveSnapshot(String agentId, Integer versionNumber, Long ownerId) {
        // 归属隔离 fail-closed（审查修复 F05）：ownerId 缺失或定义不归属时视为不存在
        if (ownerId == null) {
            return null;
        }
        AgentDefinition definition = StringUtils.isBlank(agentId)
                ? null : definitionRepository.findByAgentId(agentId)
                .filter(d -> ownerId.equals(d.ownerId()))
                .orElse(null);
        if (definition == null) {
            return null;
        }
        // 版本缺省口径与 Agent 读取路径一致：激活版本优先，无激活版本回落最新已发布版本
        int version = versionNumber == null || versionNumber < 1
                ? (definition.activeVersion() >= 1 ? definition.activeVersion() : definition.latestVersion())
                : versionNumber;
        if (version < 1) {
            return null;
        }
        AgentVersion snapshot = versionRepository.findByAgentIdAndVersionNumber(agentId, version).orElse(null);
        if (snapshot == null) {
            return null;
        }
        return new AgentSnapshotDTO(agentId, version, buildAgentMap(snapshot));
    }

    /** 版本快照 → 裁剪格式化 Agent 对象（snake_case 键，LinkedHashMap 保装配序）。 */
    private Map<String, Object> buildAgentMap(AgentVersion version) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("id", version.agentId());
        agent.put("type", "agent");
        agent.put("version", version.versionNumber());
        putIfPresent(agent, "name", version.name());
        putIfPresent(agent, "description", version.description());
        // instructions 概念以 system 输出（恒有键，未配置为空串）
        agent.put("system", version.systemPrompt() == null ? "" : version.systemPrompt());
        putModel(agent, version.modelJson());
        putParsedJson(agent, "tools", version.toolsJson());
        putParsedJson(agent, "mcp_servers", version.mcpServersJson());
        putParsedJson(agent, "skills", version.skillsJson());
        // metadata / created_at / updated_at / archived_at / agents_md 一律裁剪；
        // multiagent 恒 null（写入侧已拒绝非空提交，快照不展开 coordinator 阵列）
        agent.put("multiagent", null);
        return agent;
    }

    // ==================== JSON 装配工具 ====================

    /** JSON 文本 → 解析树（空白 / 非法返回 null）。 */
    private static JsonNode readTree(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (JacksonException ex) {
            return null;
        }
    }

    /**
     * 模型引用装配：按提交形态回显（字符串简写 / 对象），并在 model 对象上追加只读
     * {@code effective_context_window}（取值为「请求 context_window → 模型目录默认」的首个有效值；
     * 无有效值时省略键）。字符串简写且有目录默认窗口时展开为对象承载只读字段。
     * <p>契约声明「请求 → 环境覆盖 → 目录默认」三档，其中环境覆盖档位本期无生产者
     * （环境契约 {@code config} 仅承载 type/packages/setup_script），登记于 design D12 偏离 #12；
     * 环境侧引入覆盖字段时在此补一档取值即可。</p>
     */
    private void putModel(Map<String, Object> target, String modelJson) {
        JsonNode node = readTree(modelJson);
        if (node == null) {
            return;
        }
        Integer effective = effectiveContextWindow(node);
        Map<String, Object> model = asPlainMap(node);
        if (model == null) {
            // 字符串简写形态：仅当目录声明默认窗口时展开为对象，否则原样回显
            if (effective == null) {
                target.put("model", toPlain(node));
                return;
            }
            model = new LinkedHashMap<>();
            model.put("id", text(node));
        }
        if (effective != null) {
            model.put("effective_context_window", effective);
        }
        target.put("model", model);
    }

    /** 生效上下文窗口：请求显式 context_window 优先，否则取模型目录默认；皆无返回 null。 */
    private Integer effectiveContextWindow(JsonNode modelNode) {
        Integer explicit = intOrNull(modelNode.get("context_window"));
        if (explicit != null) {
            return explicit;
        }
        String modelId = text(modelNode.get("id"));
        if (StringUtils.isBlank(modelId) && modelNode.isString()) {
            modelId = text(modelNode);
        }
        if (StringUtils.isBlank(modelId)) {
            return null;
        }
        return modelCatalogService.find(modelId)
                .map(item -> item.defaultContextWindow())
                .orElse(null);
    }

    /** JSON 文本 → 按提交形态回显的 plain 值（空白 / 非法省略键）。 */
    private static void putParsedJson(Map<String, Object> target, String key, String json) {
        JsonNode node = readTree(json);
        if (node == null) {
            return;
        }
        target.put(key, toPlain(node));
    }

    /** 解析树 → 可 JSON 序列化 plain 结构（Map / List / 标量）。 */
    private static Object toPlain(JsonNode node) {
        try {
            return OBJECT_MAPPER.readValue(node.toString(), Object.class);
        } catch (JacksonException ex) {
            return node.toString();
        }
    }

    /** 对象节点 → 可变 Map（供 model 追加只读字段覆写）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asPlainMap(JsonNode node) {
        Object plain = toPlain(node);
        return plain instanceof Map ? (Map<String, Object>) plain : null;
    }

    /** 节点文本取值（null 节点返回 null）。 */
    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    /** 版本节点取整数（数字或数字字符串；无法解析返回 null）。 */
    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        try {
            return Integer.parseInt(node.asString().trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 值非空白时写入 map（snake_case 装配辅助）。 */
    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            target.put(key, value);
        }
    }
}
