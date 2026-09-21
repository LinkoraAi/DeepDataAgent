package com.linkroa.deepdataagent.knowledgebase.controller.response;

import java.util.List;

/**
 * 上传文档响应。
 * <p>在文档详情之外透出判重处置结果：{@code skipped=true} 表示命中库内重复且冲突动作为 SKIP，
 * 此时 {@code document} 为库内既有文档（未新建）；{@code skipped=false} 表示新文档已正常登记。
 * 覆盖换版（OVERWRITE）路径下 {@code overwritten} 携带被受理为 DELETING 的旧文档快照列表，
 * 供前端展示"旧版本删除中"徽标或按状态过滤；
 * 非覆盖路径 {@code overwritten} 为空列表。</p>
 *
 * @param skipped     是否因命中重复被跳过
 * @param document    新登记的文档或被跳过的既有文档详情
 * @param overwritten 覆盖换版路径下被受理为 DELETING 的旧文档详情列表（非覆盖路径为空列表）
 */
public record UploadDocumentResponse(
        boolean skipped,
        DocumentResponse document,
        List<DocumentResponse> overwritten
) {
}
