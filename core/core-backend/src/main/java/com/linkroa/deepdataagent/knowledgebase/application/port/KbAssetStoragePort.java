package com.linkroa.deepdataagent.knowledgebase.application.port;

import java.io.InputStream;
import java.util.Optional;

/**
 * 知识库对象资产存储访问业务端口（knowledgebase BC 出站端口，进程内依赖倒置）。
 * <p>统一承载文档源文件与切片媒体图片两类对象资产的读写删与清退：业务侧只以
 * <b>对象键</b>（{@code rag/{kbId}/...} 命名空间下的相对路径）引用资产，
 * MUST NOT 感知桶、后端类型等技术形态；shared 对象存储技术类型（{@code ObjectStorage}
 * 及其异常）只允许出现在 infrastructure 装配适配器内，由适配器完成「对象缺失 →
 * {@link Optional#empty()}」等业务语义翻译。</p>
 * <p>消费方：knowledgebase 应用服务（上传登记 / 原文代理 / 删除清退）与 rag BC
 * 管线（解析器读源、媒体图片持久化、描述与答案真看图）——两 BC 读写同一命名空间，
 * 经本端口单点收敛，避免双适配器造成 key 规则漂移。</p>
 */
public interface KbAssetStoragePort {

    /**
     * 写入文档源文件对象（覆盖语义；对象键由业务侧含 UUID 派生，天然无碰撞）。
     *
     * @param objectKey   对象键（非空白，{@code rag/{kbId}/source/{uuid}.{扩展名}} 形态）
     * @param content     文件字节（非空）
     * @param contentType 内容类型（可为 null，实现侧按二进制流兜底）
     * @throws IllegalArgumentException 对象键空白或字节为空
     */
    void putSource(String objectKey, byte[] content, String contentType);

    /**
     * 写入切片媒体图片对象（覆盖语义：同名重摄入即覆盖，维持单一引用）。
     *
     * @param objectKey   对象键（非空白，{@code rag/{kbId}/{documentId}/images/{图片名}} 形态）
     * @param content     图片字节（非空）
     * @param contentType 内容类型（可为 null，实现侧按二进制流兜底）
     * @throws IllegalArgumentException 对象键空白或字节为空
     */
    void putMedia(String objectKey, byte[] content, String contentType);

    /**
     * 打开对象内容（流式读取 + 精确字节数）。
     * <p>字节数以对象存储列举能力按对象键精确取得；打开成功返回的流由调用方负责关闭。</p>
     *
     * @param objectKey 对象键（非空白）
     * @return 已打开对象（内容流 + 字节数）；对象不存在返回 {@link Optional#empty()}，
     *         由调用方映射 404 / 降级等业务语义
     * @throws IllegalArgumentException    对象键空白
     * @throws RuntimeException 其他存储技术失败原样上抛（fail-closed，由调用方处置）
     */
    Optional<OpenedObject> open(String objectKey);

    /**
     * 删除单个对象（幂等：对象不存在静默成功）。
     *
     * @param objectKey 对象键（非空白）
     * @throws IllegalArgumentException 对象键空白
     */
    void delete(String objectKey);

    /**
     * 清退指定前缀下的全部对象（整库 / 文档级资产回收的有界命名空间整体删除）。
     * <p>前缀 MUST 为业务侧可精确解析归属的有界命名空间（如 {@code rag/{kbId}/}），
     * 空白前缀直接拒绝，杜绝全桶误删。</p>
     *
     * @param prefix 对象键前缀（非空白，通常以 {@code /} 结尾）
     * @throws IllegalArgumentException 前缀空白
     */
    void cleanupPrefix(String prefix);

    /**
     * 已打开的对象（内容流 + 字节数）。
     *
     * @param content 对象内容流（调用方负责关闭）
     * @param size    对象字节数（列举精确命中所得）
     */
    record OpenedObject(InputStream content, long size) {
    }
}
