package com.linkroa.deepdataagent.agent.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话管理配置属性
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.session")
public class SessionProperties {

    /**
     * 最大活跃会话数
     * <p>超过此数量时拒绝创建新会话。默认 100。</p>
     */
    private int maxActiveSessions = 100;

    /**
     * 会话恢复时加载的消息数量
     * <p>从 conversation_msg 表加载最近 N 条消息用于上下文重建。默认 5。</p>
     */
    private int contextLoadSize = 5;

    /**
     * 会话列表默认分页大小
     * <p>侧边栏会话列表每次加载的最大条数。默认 20。</p>
     */
    private int sessionListPageSize = 20;
}
