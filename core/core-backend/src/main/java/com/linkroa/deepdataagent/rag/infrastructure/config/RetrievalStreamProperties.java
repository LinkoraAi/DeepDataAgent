package com.linkroa.deepdataagent.rag.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 检索答案流配置（{@code app.rag.retrieval.stream}）。
 * <p>集中管理 SSE 连接超时、增量合帧窗口与准备阶段保活间隔，支持同名环境变量覆盖。
 * 这些参数只影响答案端点的传输节奏，不改变检索与作答语义。</p>
 *
 * @author DeepDataAgent
 */
@Configuration
@ConfigurationProperties(prefix = "app.rag.retrieval.stream")
public class RetrievalStreamProperties {

    /**
     * SSE 连接超时：必须大于一次作答的最长耗时，否则连接会被容器提前回收。
     */
    private Duration sseTimeout = Duration.ofMinutes(10);

    /**
     * 增量合帧窗口（毫秒）：首个增量立即下发，其余在窗口内合并。
     * 非正数收敛为 1ms（等价逐增量下发）。
     */
    private long deltaFlushIntervalMs = 50L;

    /**
     * 准备阶段保活间隔：从建流到首个答案事件之间周期性发送 SSE 注释帧，
     * 避免长时间无数据被链路判定为空闲。非正数表示关闭保活。
     */
    private Duration keepaliveInterval = Duration.ofSeconds(15);

    /**
     * 获取 SSE 连接超时。
     *
     * @return 连接超时
     */
    public Duration getSseTimeout() {
        return sseTimeout;
    }

    /**
     * 设置 SSE 连接超时。
     *
     * @param sseTimeout 连接超时
     */
    public void setSseTimeout(Duration sseTimeout) {
        this.sseTimeout = sseTimeout;
    }

    /**
     * 获取增量合帧窗口（毫秒）。
     *
     * @return 合帧窗口毫秒
     */
    public long getDeltaFlushIntervalMs() {
        return deltaFlushIntervalMs;
    }

    /**
     * 设置增量合帧窗口（毫秒）。
     *
     * @param deltaFlushIntervalMs 合帧窗口毫秒
     */
    public void setDeltaFlushIntervalMs(long deltaFlushIntervalMs) {
        this.deltaFlushIntervalMs = deltaFlushIntervalMs;
    }

    /**
     * 获取准备阶段保活间隔。
     *
     * @return 保活间隔
     */
    public Duration getKeepaliveInterval() {
        return keepaliveInterval;
    }

    /**
     * 设置准备阶段保活间隔。
     *
     * @param keepaliveInterval 保活间隔
     */
    public void setKeepaliveInterval(Duration keepaliveInterval) {
        this.keepaliveInterval = keepaliveInterval;
    }
}