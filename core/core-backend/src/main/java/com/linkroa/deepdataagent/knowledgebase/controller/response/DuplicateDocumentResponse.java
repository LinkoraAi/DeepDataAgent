package com.linkroa.deepdataagent.knowledgebase.controller.response;

import java.util.List;

/**
 * 判重预检命中的重复文档摘要。
 * <p>仅透出决策所需的最小信息：命中的既有文档ID、文件名与命中的判定轴
 * （对外契约字面量，取值 {@code fileName} / {@code contentHash}），
 * 供调用方在正式上传前确认处置方式。</p>
 *
 * @param id        命中的既有文档ID
 * @param fileName  命中的既有文档文件名
 * @param matchAxes 命中的判定轴标签列表（对外契约字面量，语义为「命中了哪个判定轴」，
 *                  MUST NOT 随持久化列名或存储形态变更而改名）
 */
public record DuplicateDocumentResponse(
        Long id,
        String fileName,
        List<String> matchAxes
) {
}
