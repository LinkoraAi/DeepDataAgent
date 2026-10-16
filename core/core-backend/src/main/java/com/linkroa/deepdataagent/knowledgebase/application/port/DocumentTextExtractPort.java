package com.linkroa.deepdataagent.knowledgebase.application.port;

import java.io.InputStream;

/**
 * 文档纯文本提取出站端口（knowledgebase BC 出站端口，进程内依赖倒置）。
 * <p>用于办公类文档（DOC / DOCX / XLS / XLSX / PPT / PPTX）的在线文本预览：
 * 浏览器无法原生渲染这些格式，由基础设施侧适配实现（Apache Tika 自动探测解析）
 * 把源文件内容流提取为纯文本回传，业务侧不感知具体解析技术。</p>
 * <p>实现方约束：</p>
 * <ul>
 *   <li>调用方 MUST 以 try-with-resources 管理入参流；实现方内部包装流关闭时可能级联关闭入参流，
 *       调用方重复关闭须保持幂等无副作用；</li>
 *   <li>解析失败（加密文档 / 损坏文件 / 不支持的容器）以 {@link IllegalStateException} 上抛，
 *       由调用方转「预览失败」提示；</li>
 *   <li>超长文本 MUST 在实现侧截断并在结果中置 {@link ExtractedText#truncated()} 为 true，
 *       防止超大办公文档全文驻留内存。</li>
 * </ul>
 */
public interface DocumentTextExtractPort {

    /**
     * 从文档内容流中提取纯文本。
     *
     * @param content 文档源文件内容流（非空，由调用方关闭）
     * @return 提取结果（纯文本内容 + 是否因超长被截断）
     */
    ExtractedText extract(InputStream content);

    /**
     * 文本提取结果。
     *
     * @param content   提取出的纯文本（无有效文本时为空字符串，永不为 {@code null}）
     * @param truncated 是否因达到预览长度上限被截断（true 时前端提示下载查看完整内容）
     */
    record ExtractedText(String content, boolean truncated) {
    }
}
