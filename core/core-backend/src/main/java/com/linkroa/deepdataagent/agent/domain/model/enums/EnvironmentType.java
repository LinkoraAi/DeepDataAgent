package com.linkroa.deepdataagent.agent.domain.model.enums;

/**
 * 运行环境类型枚举。
 * <p>本期仅支持 {@link #LOCAL}（本进程内本地 Docker 服务）；
 * {@code remote} / {@code self_hosted} 等异构环境类型不在本期落地，仅作未来扩展点预留。</p>
 */
public enum EnvironmentType {

    /** 本地 Docker 服务（本地沙箱执行环境） */
    LOCAL
}