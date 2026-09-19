package com.linkroa.deepdataagent.file.api;

import com.linkroa.deepdataagent.file.api.dto.FileMountMetaDTO;

import java.util.Optional;

/**
 * 文件服务契约（跨 BC 服务接口，轻量元数据 / 只读查询能力面）。
 * <p>供运行时（runtime BC）按文件业务 ID 校验挂载就绪性并读取挂载元数据，返回
 * 可 JSON 序列化的发布语言 DTO {@link FileMountMetaDTO}；当前由基础设施层
 * {@code DefaultFileApi} 进程内实现（{@code @Service}），未来 Feign 化后
 * 仅需在接口追加 {@code @FeignClient} 并移除进程内实现，消费方无需改动。
 * 越权与不存在的文件统一返回 {@code false} / {@code Optional.empty()}，
 * 语义由调用方映射为 404，避免泄露文件存在性；需要区分「存在但未就绪」（409）时，
 * 调用方可在准入判定失败后经 {@link #existsOwnedBy} 二次探测。</p>
 * <p><b>本面刻意不返回文件内容</b>：单份文件内容最大 50MB，把全文塞进可 Feign 化
 * 的 {@code api} 面等于把大块字节搬进内部 RPC。挂载内容不落内存、改走同 BC 的
 * {@code FileMountMaterializationPort}（进程内出站，永不 Feign 化）：流式复制为
 * 宿主会话挂载副本 + SHA-256 校验，重载荷止步于磁盘到磁盘。</p>
 */
public interface FileApi {

    /**
     * 校验文件可被指定用户挂载到会话：存在、归属该用户且状态为 ready（ready 门禁）。
     *
     * @param fileId  文件业务 ID（前缀 file_）
     * @param ownerId 归属用户 ID
     * @return true=文件存在、归该用户所有且已就绪
     */
    boolean readyForMount(String fileId, Long ownerId);

    /**
     * 判断文件是否存在且归属该用户（<b>不看就绪性</b>）。
     * <p>供调用方在 {@link #readyForMount} 判定失败后区分两份对外语义：
     * 不存在 / 越权 → 404（不泄露存在性）；存在但未就绪 → 409 {@code invalid_request_error}
     * （契约明文路径，见 sessions spec「引用未就绪文件创建会话被 409 拒绝」）。
     * 本方法<b>只</b>在 {@code readyForMount} 返回 false 后调用，MUST NOT 单独作为准入判定。</p>
     *
     * @param fileId  文件业务 ID（前缀 file_）
     * @param ownerId 归属用户 ID
     * @return true=文件存在且归属该用户（就绪性未知）
     */
    boolean existsOwnedBy(String fileId, Long ownerId);

    /**
     * 读取归属用户「已就绪」文件的挂载元数据（文件名与字节数，供 Session 挂载总量配额校验）。
     * <p>与 {@link #readyForMount} 同一门禁语义（复用 {@code File.mountableBy} 领域谓词）：
     * 文件不存在、非本人 owner 或未就绪统一返回 {@link Optional#empty()}，
     * 不泄露文件存在性。<b>不读取磁盘内容</b>。</p>
     *
     * @param fileId  文件业务 ID（前缀 file_）
     * @param ownerId 归属用户 ID
     * @return 挂载元数据 DTO；不可挂载时返回 {@link Optional#empty()}
     */
    Optional<FileMountMetaDTO> findReadyMountMeta(String fileId, Long ownerId);
}
