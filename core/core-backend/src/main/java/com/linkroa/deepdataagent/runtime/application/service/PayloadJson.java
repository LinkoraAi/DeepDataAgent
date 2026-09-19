package com.linkroa.deepdataagent.runtime.application.service;

import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件 payload JSON 装配纯工具（应用层纯工具，非服务组件、非 Bean）。
 * <p>承载「小对象序列化 + 失败即编程错误上抛」与「JSON 对象值宽松收敛」两项跨簇复用的
 * 薄封装（decompose-command-facade 2.2：原为命令服务私有方法 {@code jsonOf} / {@code asMap}，
 * 随 HITL 明细重建簇外迁而收敛于此，供会话资料更新、元数据浅合并与待确认明细重建共用）。</p>
 * <p>依 AGENTS.md「小型工具随主要使用簇安置，跨簇复用时以 package-private 静态工具承载，
 * MUST NOT 为其新建 Bean 或端口」的口径落点；{@code ObjectMapper} 由调用方注入后作入参传入，
 * 保持「全应用共用同一配置化 mapper」的序列化口径不变。</p>
 * <p><b>落点与可见性</b>（5.1 归位裁定）：本类被 {@code service.session}（元数据浅合并）与
 * {@code service.hitl}（待确认明细重建）两个子包共用，无单一主属子包，故留守
 * {@code application.service} 根包作为 application 层内的公共工具位，整类 public；
 * 包内两方法均为纯函数、零业务规则，不构成跨层依赖。</p>
 */
public final class PayloadJson {

    private PayloadJson() {
    }

    /**
     * 序列化事件 payload（小对象直传，失败属编程错误直接上抛）。
     *
     * @param objectMapper 调用方持有的配置化 mapper（非本工具自建实例）
     * @param values       待序列化键值对象
     * @return JSON 文本
     */
    public static String jsonOf(ObjectMapper objectMapper, Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalStateException("事件 payload 序列化失败", ex);
        }
    }

    /**
     * JSON 对象值宽松收敛（非 Map 形态收敛为空对象，明细重建不因形状漂移阻断）。
     *
     * @param value 反序列化后的字段值（可空）
     * @return 可变 {@code Map<String, Object>} 副本；非对象形态为空对象
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }
}
