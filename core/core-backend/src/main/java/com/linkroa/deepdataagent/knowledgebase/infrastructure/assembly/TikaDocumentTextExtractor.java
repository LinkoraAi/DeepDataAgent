package com.linkroa.deepdataagent.knowledgebase.infrastructure.assembly;

import com.linkroa.deepdataagent.knowledgebase.application.port.DocumentTextExtractPort;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.exception.ZeroByteFileException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;

/**
 * {@link DocumentTextExtractPort} 的 Apache Tika 适配实现（knowledgebase 基础设施装配层）。
 * <p>与 RAG 摄入管线的 {@code LocalTikaDocumentParser} 共用 Tika 4.0 自动探测解析能力，
 * 但定位不同：本实现服务于<b>在线预览</b>，只回传截断到上限的纯文本，不做分块 / 哈希 / 元数据落库。
 * 入参流由调用方关闭；本实现另开 {@link TikaInputStream} 包装并在 try-with-resources 中关闭。</p>
 * <p><b>截断策略</b>：为 {@link BodyContentHandler} 配置 {@link #PREVIEW_MAX_CHARS} 写上限，
 * Tika 达到上限时抛 {@link WriteLimitReachedException}（SAX 事件的正常停止信号，非解析失败），
 * 取已累积内容并置 truncated=true；IO 异常与 Tika 协议异常（如加密文档、损坏容器）
 * 转 {@link IllegalStateException} 上抛，由调用方映射为「预览失败」。</p>
 */
@Service
public class TikaDocumentTextExtractor implements DocumentTextExtractPort {

    /** 预览文本最大保留字符数（约 20 万字符，足够覆盖绝大多数办公文档全文）。 */
    private static final int PREVIEW_MAX_CHARS = 200_000;

    /**
     * 复用的 Tika 自动探测解析器（重成本编译产物，构造一次全程复用；无解析态，线程安全）。
     */
    private final AutoDetectParser autoDetectParser = new AutoDetectParser();

    @Override
    public ExtractedText extract(InputStream content) {
        // handler / Metadata / ParseContext 每次新建承载单次解析状态，复用会串数据
        BodyContentHandler handler = new BodyContentHandler(PREVIEW_MAX_CHARS);
        boolean truncated = false;
        try (TikaInputStream tikaIn = TikaInputStream.get(content)) {
            autoDetectParser.parse(tikaIn, handler, new Metadata(), new ParseContext());
        } catch (WriteLimitReachedException e) {
            // 达到预览长度上限：已提取部分有效，标记截断后正常返回
            truncated = true;
        } catch (ZeroByteFileException e) {
            // 空文件（Tika 判定零字节）：按空文本预览处理，不视为解析失败
            return new ExtractedText("", false);
        } catch (IOException e) {
            throw new IllegalStateException("读取文档内容失败，无法生成文本预览", e);
        } catch (TikaException | SAXException e) {
            throw new IllegalStateException("文档解析失败，无法生成文本预览（可能为加密或损坏文件）", e);
        }
        return new ExtractedText(handler.toString(), truncated);
    }
}
