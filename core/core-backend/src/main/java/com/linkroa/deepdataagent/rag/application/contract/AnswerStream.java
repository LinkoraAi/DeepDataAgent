package com.linkroa.deepdataagent.rag.application.contract;

import reactor.core.publisher.Flux;

/**
 * 检索答案流契约（应用层 → 控制器）。
 * <p>只承载答案增量流：引用信息由模型在答案正文的 References 段中生成，
 * 终结帧不需要额外的结构化来源或内部降级标记。</p>
 *
 * @param deltas 答案增量流（冷流；订阅时才执行 Stage 0~5）
 * @author DeepDataAgent
 */
public record AnswerStream(Flux<String> deltas) {
}