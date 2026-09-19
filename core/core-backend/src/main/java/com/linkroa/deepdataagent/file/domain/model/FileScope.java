package com.linkroa.deepdataagent.file.domain.model;

import org.apache.commons.lang3.StringUtils;

/**
 * 文件作用域值对象（对外形状 {@code scope: {id, type}}）。
 * <p>未关联任何资源的文件（如刚上传的 user_upload）scope 为 null；
 * 关联会话产出 / 会话资源时携带 {@code {id: "sess_...", type: "session"}}，
 * 文件生命周期随所属资源（Session 删除时 scope=session 文件被清理）。
 * 当前仅支持 session 作用域（契约现行口径），持久化为 JSONB 列。</p>
 *
 * @param id   作用域资源 ID（如会话 ID，前缀 sess_）
 * @param type 作用域资源类型（当前仅 session）
 */
public record FileScope(String id, String type) {

    /** 会话作用域类型 */
    public static final String TYPE_SESSION = "session";

    /**
     * 紧凑构造器：不变量校验（非空 + 类型白名单）。
     *
     * @throws IllegalArgumentException ID / 类型为空或类型不受支持
     */
    public FileScope {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("文件作用域ID不能为空");
        }
        if (!TYPE_SESSION.equals(type)) {
            throw new IllegalArgumentException("文件作用域类型仅支持 session: " + type);
        }
    }

    /**
     * 构造会话作用域。
     *
     * @param sessionId 会话业务 ID（前缀 sess_）
     * @return scope 值对象
     */
    public static FileScope ofSession(String sessionId) {
        return new FileScope(sessionId, TYPE_SESSION);
    }
}
