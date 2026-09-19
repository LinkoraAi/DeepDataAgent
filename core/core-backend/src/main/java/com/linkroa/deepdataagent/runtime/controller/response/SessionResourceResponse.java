package com.linkroa.deepdataagent.runtime.controller.response;

import java.time.OffsetDateTime;

/**
 * 会话挂载资源项响应（契约端点 {@code POST /sessions/{session_id}/resources} 成功返回的资源对象）。
 * <p>本期追加挂载仅支持 file 类型，故仅回显 {@code id / type / file_id / mount_path}；
 * 资源 VO 不持有独立时间戳，挂载时刻即追加成功时刻（{@code created_at / updated_at}
 * 取会话追加后的 {@code updated_at}）。</p>
 *
 * @param id         资源业务 ID（前缀 {@code sesr_}）
 * @param type       资源类型（本期追加恒为 {@code file}）
 * @param file_id    已挂载文件业务 ID（前缀 {@code file_}）
 * @param mount_path 挂载路径（沙箱工作区相对路径，缺省 {@code mounts/<file_id>}）
 * @param created_at 挂载创建时刻
 * @param updated_at 挂载更新时刻
 */
public record SessionResourceResponse(
        String id,
        String type,
        String file_id,
        String mount_path,
        OffsetDateTime created_at,
        OffsetDateTime updated_at
) {
}
