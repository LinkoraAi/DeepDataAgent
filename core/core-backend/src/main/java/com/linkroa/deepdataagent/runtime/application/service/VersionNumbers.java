package com.linkroa.deepdataagent.runtime.application.service;

import org.apache.commons.lang3.StringUtils;

/**
 * Agent 发布版本号解析（应用层纯工具，非服务组件）。
 * <p>会话侧 {@code agent_version} 以字符串承载发布号；快照契约要 {@code Integer}
 * （{@code null} = 取最新 / 激活版本），响应摘要要 {@code int}（非法收敛 0）。
 * 原命令服务与查询服务各持一份，收敛于此。</p>
 * <p>可见性：随 {@code SessionLifecycleService}（{@code application.service.session}）复用
 * 主线程快照装配口径而提升为 public（decompose-command-facade 4.3）——本类逻辑零变化，
 * 仅为放开 application 层内的跨子包复用，不构成跨层依赖。</p>
 */
public final class VersionNumbers {

    private VersionNumbers() {
    }

    /**
     * 版本字符串 → 发布号（空白 / 非十进制收敛为 null = 契约侧取最新）。
     *
     * @param agentVersion 版本原文（可空）
     * @return 发布号；非法为 null
     */
    public static Integer parseOrNull(String agentVersion) {
        if (StringUtils.isBlank(agentVersion)) {
            return null;
        }
        try {
            return Integer.valueOf(agentVersion.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 版本字符串 → 发布号（非十进制收敛为 0，与响应摘要口径一致）。
     *
     * @param agentVersion 版本原文（可空）
     * @return 非负发布号
     */
    public static int parseNumber(String agentVersion) {
        Integer parsed = parseOrNull(agentVersion);
        return parsed == null ? 0 : Math.max(parsed, 0);
    }
}
