package com.linkroa.deepdataagent.rag.controller.stream;

import com.linkroa.deepdataagent.rag.application.contract.AnswerStream;
import com.linkroa.deepdataagent.rag.infrastructure.config.RetrievalStreamProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link AnswerStreamSession} 单元测试。
 * <p>覆盖两条收尾路径：① 正常完成——下发 started/delta/completed 三帧后关闭连接；
 * ② 客户端提前断开——取消模型订阅（{@code doOnCancel} 被触发），不再写终结帧。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class AnswerStreamSessionTest {

    /** SSE 连接 Mock */
    @Mock
    private SseEmitter emitter;

    /**
     * 构造关闭保活、默认合帧窗口的流参数（避免测试期间后台任务干扰）。
     *
     * @return 流参数
     */
    private RetrievalStreamProperties properties() {
        RetrievalStreamProperties properties = new RetrievalStreamProperties();
        properties.setKeepaliveInterval(Duration.ZERO);
        return properties;
    }

    /**
     * 场景：增量流正常完成。
     * 预期：依次写出开始、增量、完成三帧，并关闭连接。
     */
    @Test
    void should_sendStartedDeltaCompletedAndClose_when_start_givenNormalCompletion() throws Exception {
        // given
        AnswerStream stream = new AnswerStream(Flux.just("AB"));
        AnswerStreamSession session = new AnswerStreamSession(emitter, stream, properties(), 1L);

        // when
        session.start();

        // then：三帧（started / delta / completed）+ 正常关闭
        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    /**
     * 场景：客户端在答案完成前断开（连接完成回调触发）。
     * 预期：取消上游订阅（{@code doOnCancel} 标志置位），且不写完成帧。
     */
    @Test
    void should_cancelSubscription_when_clientDisconnectsBeforeCompletion() throws Exception {
        // given：永不结束的流 + 取消探针
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Flux<String> deltas = Flux.<String>never().doOnCancel(() -> cancelled.set(true));
        AnswerStream stream = new AnswerStream(deltas);
        AnswerStreamSession session = new AnswerStreamSession(emitter, stream, properties(), 1L);
        ArgumentCaptor<Runnable> completionCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onCompletion(completionCaptor.capture());
        session.start();

        // when：模拟容器在客户端断开后回调连接完成
        completionCaptor.getValue().run();

        // then：上游被取消，且未写完成帧（只有开始事件）
        assertTrue(cancelled.get(), "客户端断开必须取消模型订阅");
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }
}