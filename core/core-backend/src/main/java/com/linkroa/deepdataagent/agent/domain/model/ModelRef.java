package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.ModelEffort;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型引用值对象（{@code agent_version.model_json} 的解析结果，对外 {@code model} 字段形状）。
 * <p>{@code model} 支持两种等价提交形态：字符串简写（目录模型 id，如 {@code "ultimate"}）与
 * 对象形态 {@code {id, effort?, context_window?}}。本值对象以 {@code shorthand} 标记提交形态，
 * 响应按提交形态原样回显（字符串提交返回字符串，对象提交保留调优字段）。</p>
 * <p>不变量：模型 id 非空；简写形态不携带调优参数；{@code effort} 档位词汇合法（目录级校验在应用层）；
 * {@code context_window} 为正整数。对象形态提交已废止字段 {@code speed} 一律拒绝（提示改用 {@code effort}）。</p>
 *
 * @param id            目录模型标识
 * @param effort        推理 effort 档位（可空 = 目录默认）
 * @param contextWindow 上下文窗口档位（可空 = 目录默认）
 * @param shorthand     是否以字符串简写形态提交（决定响应回显形态）
 */
public record ModelRef(String id, ModelEffort effort, Integer contextWindow, boolean shorthand) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 紧凑构造器：不变量校验。
     *
     * @throws IllegalArgumentException 模型 id 为空 / 简写形态携带调优参数 / 上下文窗口非法
     */
    public ModelRef {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("模型引用 id 不能为空");
        }
        if (shorthand && (effort != null || contextWindow != null)) {
            throw new IllegalArgumentException("模型引用为字符串简写时不得携带调优参数");
        }
        if (contextWindow != null && contextWindow < 1) {
            throw new IllegalArgumentException("模型上下文窗口必须大于0");
        }
    }

    /**
     * 解析模型引用 JSON（字符串简写 {@code "ultimate"} 或对象 {@code {id, effort, context_window}}）。
     * <p>对象形态携带已废止字段 {@code speed} 时拒绝并提示改用 {@code effort}。</p>
     *
     * @param modelJson 模型引用 JSON（可空 = 未配置，返回 {@code null}）
     * @return 解析结果；未配置时为 {@code null}
     * @throws IllegalStateException    JSON 非法、形态不受支持或携带已废止的 {@code speed} 字段
     * @throws IllegalArgumentException {@code effort} 档位词汇非法
     */
    public static ModelRef parse(String modelJson) {
        if (StringUtils.isBlank(modelJson)) {
            return null;
        }
        JsonNode node;
        try {
            node = OBJECT_MAPPER.readTree(modelJson);
        } catch (RuntimeException e) {
            throw new IllegalStateException("模型引用JSON解析失败", e);
        }
        if (node.isString()) {
            return new ModelRef(node.asText(), null, null, true);
        }
        if (!node.isObject()) {
            throw new IllegalStateException("模型引用格式非法：须为字符串简写或对象形态");
        }
        rejectDeprecatedSpeed(node);
        String id = textOf(node, "id");
        ModelEffort effort = ModelEffort.from(textOf(node, "effort"));
        Integer contextWindow = intOf(node, "context_window");
        return new ModelRef(id, effort, contextWindow, false);
    }

    /**
     * 序列化模型引用为 JSON（按提交形态回显：简写落字符串，对象落 {@code {id, effort?, context_window?}}）。
     *
     * @param ref 模型引用（可空 = 未配置，返回 {@code null}）
     * @return 模型引用 JSON 字符串
     */
    public static String toJson(ModelRef ref) {
        if (ref == null) {
            return null;
        }
        try {
            if (ref.shorthand()) {
                return OBJECT_MAPPER.writeValueAsString(ref.id());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ref.id());
            if (ref.effort() != null) {
                item.put("effort", ref.effort().value());
            }
            if (ref.contextWindow() != null) {
                item.put("context_window", ref.contextWindow());
            }
            return OBJECT_MAPPER.writeValueAsString(item);
        } catch (RuntimeException e) {
            throw new IllegalStateException("模型引用序列化失败", e);
        }
    }

    /** 目录级调优档位声明的展示形态（effort 词汇列表，供错误消息与目录比对复用）。 */
    public static List<String> effortVocabulary() {
        List<String> values = new ArrayList<>();
        for (ModelEffort effort : ModelEffort.values()) {
            values.add(effort.value());
        }
        return values;
    }

    /**
     * 拒绝已废止的 {@code speed} 字段：该字段为公开契约早期形态，已由 {@code effort} 取代，
     * 提交（非 null）即报错并提示改用 {@code effort}，MUST NOT 静默忽略。
     *
     * @param node 模型引用对象节点
     * @throws IllegalStateException 对象携带非 null 的 {@code speed}
     */
    private static void rejectDeprecatedSpeed(JsonNode node) {
        if (node.hasNonNull("speed")) {
            throw new IllegalStateException("模型引用 model.speed 字段已废止，请改用 model.effort");
        }
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer intOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw new IllegalStateException("模型引用 " + field + " 须为整数");
        }
        return value.asInt();
    }
}
