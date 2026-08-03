package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.infrastructure.sse.SSEConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * SSE 连接控制器
 * <p>提供客户端建立 SSE 连接的接口。客户端通过此接口建立长连接，
 * 后续所有会话的分析事件都通过此连接推送，实现多路复用。</p>
 *
 * <p>接口说明：</p>
 * <ul>
 *   <li>GET /agent/sse/connect - 建立 SSE 连接，返回 clientId</li>
 *   <li>GET /agent/sse/disconnect - 断开 SSE 连接</li>
 * </ul>
 */
@RestController
@RequestMapping("/agent/sse")
public class SSEConnectionController {

    private static final Logger log = LoggerFactory.getLogger(SSEConnectionController.class);

    private final SSEConnectionPool connectionPool;

    public SSEConnectionController(SSEConnectionPool connectionPool) {
        this.connectionPool = connectionPool;
    }

    /**
     * 建立 SSE 连接
     * <p>客户端调用此接口建立 SSE 长连接，服务端生成 clientId 并通过 SSEConnectionPool 管理连接生命周期。
     * 连接池自动注册 onCompletion/onTimeout/onError 回调，30 秒无事件自动断开。</p>
     *
     * @return SseEmitter 事件流，包含 clientId 信息
     */
    @GetMapping(value = "/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect() {
        String clientId = UUID.randomUUID().toString();
        SseEmitter emitter = connectionPool.acquire(clientId);

        if (emitter == null) {
            log.warn("SSE connection pool exhausted, rejecting connection request");
            // 返回一个已完成的 emitter，前端收到后会触发 onError 重连
            SseEmitter rejected = new SseEmitter(0L);
            try {
                rejected.send(SseEmitter.event()
                        .name("ERROR")
                        .data("{\"message\":\"系统繁忙，请稍后重试\"}", MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                // ignore
            }
            rejected.complete();
            return rejected;
        }

        try {
            // 发送连接成功事件，包含 clientId
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .data("{\"clientId\":\"" + clientId + "\"}", MediaType.APPLICATION_JSON));
            log.info("SSE connection established for clientId={}", clientId);
        } catch (Exception e) {
            log.error("Failed to send CONNECTED event for clientId={}", clientId, e);
            connectionPool.release(clientId);
        }

        return emitter;
    }

    /**
     * 断开 SSE 连接
     * <p>客户端主动断开连接时调用此接口，通过 SSEConnectionPool 释放连接资源。</p>
     *
     * @param clientId 客户端 ID
     */
    @GetMapping("/disconnect")
    public void disconnect(@RequestParam("clientId") String clientId) {
        connectionPool.release(clientId);
        log.info("SSE connection disconnected for clientId={}", clientId);
    }
}