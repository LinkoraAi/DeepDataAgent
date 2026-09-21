package com.linkroa.deepdataagent.knowledgebase.controller.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批量删除切片请求（人工批量清退）。
 * <p>服务端去重后按批执行「Storage 先删、DB 后删」删除原语；任一批失败即终止剩余批次，
 * 由用户重删幂等续跑。</p>
 *
 * @param ids 待删除切片ID集合（非空）
 */
public record DeleteChunksRequest(

        @NotEmpty(message = "切片ID集合不能为空")
        List<Long> ids
) {
}
