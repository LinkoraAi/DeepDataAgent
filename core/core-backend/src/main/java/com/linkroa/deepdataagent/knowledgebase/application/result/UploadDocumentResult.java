package com.linkroa.deepdataagent.knowledgebase.application.result;

import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 上传文档的应用层结果。
 * <p>区分三种结局：新文档已登记（registered）、命中重复被跳过（skipped，
 * 返回命中的既有文档）、覆盖换版登记（registeredWithOverwritten，携带被受理为 DELETING
 * 的旧文档快照列表）。避免在文档聚合根上掺入「是否跳过 / 是否覆盖」这类应用语义。</p>
 *
 * @param document    新登记的文档、被跳过的既有文档
 * @param skipped     是否因命中重复而被跳过（true 时 document 为库内既有文档）
 * @param overwritten 覆盖换版路径下被受理为 DELETING 的旧文档快照列表（非覆盖路径为空列表，
 */
public record UploadDocumentResult(
        Document document,
        boolean skipped,
        List<Document> overwritten
) {

    /**
     * 构造「新文档已登记（非覆盖路径）」结果。
     *
     * @param document 落库后的新文档
     * @return 登记结果
     */
    public static UploadDocumentResult registered(Document document) {
        return new UploadDocumentResult(document, false, List.of());
    }

    /**
     * 构造「覆盖换版登记」结果：携带被受理为 DELETING 的旧文档快照列表，
     * 供事务提交后按序投递清退任务、并回传给控制器作为前端可见中间态。
     *
     * @param document    落库后的新文档（PENDING）
     * @param overwritten 被受理为 DELETING 的旧文档快照列表（含幂等受理已 DELETING 项）
     * @return 覆盖换版登记结果
     */
    public static UploadDocumentResult registeredWithOverwritten(Document document, List<Document> overwritten) {
        return new UploadDocumentResult(document, false,
                CollectionUtils.isEmpty(overwritten) ? List.of() : List.copyOf(overwritten));
    }

    /**
     * 构造「命中重复被跳过」结果。
     *
     * @param existing 命中的既有文档
     * @return 跳过结果
     */
    public static UploadDocumentResult skipped(Document existing) {
        return new UploadDocumentResult(existing, true, List.of());
    }
}
