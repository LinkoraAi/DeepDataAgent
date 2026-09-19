package com.linkroa.deepdataagent.runtime.application.service;

import org.apache.commons.lang3.StringUtils;

/**
 * 用户 ID 解析（应用层纯工具，非服务组件）。
 * <p>会话 / 装配链路上的 owner 均为字符串形态（数字 user_id 的文本），跨 BC 契约与
 * 认证上下文按数字 ID 口径交互，故统一在此收敛「非法收敛为 null」的解析口径
 * （原命令服务、查询服务、挂载物化器、装配解析器四份重复实现）。</p>
 * <p>可见性：随 {@code SessionMountValidator}（{@code application.validation}）复用 owner
 * 解析口径而提升为 public（decompose-command-facade 2.1）——本类逻辑零变化，
 * 仅为放开 application 层内的跨子包复用，不构成跨层依赖。</p>
 */
public final class UserIds {

    private UserIds() {
    }

    /**
     * 用户 ID 字符串 → 数字（空白 / 非数字收敛为 null，端口门禁按不存在处理）。
     *
     * @param userId 用户 ID 原文（可空）
     * @return 数字用户 ID；非法为 null
     */
    public static Long parse(String userId) {
        if (StringUtils.isBlank(userId)) {
            return null;
        }
        try {
            return Long.valueOf(userId.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
