package com.linkroa.deepdataagent.file.controller.response;

import java.util.List;

/**
 * 文件列表响应（游标分页形态）。
 *
 * @param data       当前页文件列表
 * @param nextCursor 下一页游标（null 表示已到末页）
 */
public record FileListResponse(
        List<FileResponse> data,
        String nextCursor
) {
}
