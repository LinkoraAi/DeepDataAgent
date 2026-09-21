package com.linkroa.deepdataagent.knowledgebase.application.result;

import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;

import java.util.List;

/**
 * 上传判重命中结果。
 * <p>承载一条命中的既有文档及其命中的判定轴，供 REJECT 拒绝回传、
 * SKIP 跳过返回与预检端点摘要展示使用。</p>
 *
 * @param document  命中的既有文档聚合根
 * @param matchAxes 命中的判定轴标签列表（对外契约字面量，取值 {@code fileName} / {@code contentHash}，
 *                  语义为「命中了哪个判定轴」，MUST NOT 随持久化列名或存储形态变更而改名）
 */
public record DedupHit(
        Document document,
        List<String> matchAxes
) {
}
