package com.linkroa.deepdataagent.rag.infrastructure.parser.engine;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.knowledgebase.domain.model.MultiModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import com.linkroa.deepdataagent.rag.domain.port.DocumentParser;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 本地 Tika 文档解析实现（provider=LOCAL 时使用）。
 * <p>基于 Apache Tika 4.0 的 {@link AutoDetectParser} 在本地进程内解析 S3 源文件，
 * 提取的纯文本按空行分段切分为多个 TEXT 内容块。本实现只产出纯文本块、不产出坐标标签
 * （坐标抽取能力由远程 MinerU 承担），meta 统一携带 page=0 占位。输出与 MinerU 均为
 * ContentBlockVO 五类统一结构，是后续分块层对解析引擎无感知的统一输入。</p>
 */
public class LocalTikaDocumentParser implements DocumentParser {

    /** 文本按"空行"分段的分隔正则（兼容 CRLF 与空白行）。 */
    private static final String BLOCK_SPLIT_REGEX = "\r?\n\\s*\r?\n";

    /** meta 键：页码。 */
    private static final String META_KEY_PAGE = "page";

    /** 本地解析不产坐标，统一占位页码。 */
    private static final int DEFAULT_PAGE = 0;

    /** BodyContentHandler 无大小上限标记。 */
    private static final int NO_WRITE_LIMIT = -1;

    /** MD5 摘要算法名。 */
    private static final String ALGORITHM_MD5 = "MD5";

    /** 解析失败提示。 */
    private static final String ERROR_PARSE_FAILED = "文档解析失败：未提取到任何文本内容";

    /** 读取对象失败提示前缀。 */
    private static final String ERROR_READ_OBJECT_FAILED = "读取源文件对象失败: ";

    /** 知识库对象资产存储访问端口（跨 BC 消费，读取源文件内容流）。 */
    private final KbAssetStoragePort kbAssetStoragePort;

    /**
     * 复用的 Tika 自动探测解析器：无参构造经 ServiceLoader 装配标准解析器集（重成本编译产物），
     * 本实例构造一次全程复用。
     * <p><b>线程安全依据</b>：Tika 的 {@code Parser} 实例本身无解析态（状态承载于每次调用的
     * {@code ContentHandler} / {@code Metadata} / {@code ParseContext}），且并发复用已经
     * openspec change {@code converge-rag-hot-path-object-creation} design D3 实证
     * （{@code LocalTikaDocumentParserTest} 两组并发用例：共享实例产出与串行逐条一致、零交叉污染）。</p>
     */
    private final AutoDetectParser autoDetectParser = new AutoDetectParser();

    /**
     * 构造器：注入对象资产存储访问端口。
     *
     * @param kbAssetStoragePort 对象资产存储访问端口（读取源文件内容流）
     */
    public LocalTikaDocumentParser(KbAssetStoragePort kbAssetStoragePort) {
        this.kbAssetStoragePort = kbAssetStoragePort;
    }

    /**
     * 解析源文件：经存储访问端口读取内容流 → Tika 提取纯文本 → 空行分段为 TEXT 块 → MD5 全文哈希。
     * <p>注意：chunkStrategy / multiModel 当前解析层不消费（为端口签名一致性保留）。</p>
     *
     * @param s3File        源文件对象引用
     * @param chunkStrategy 分块策略标识（本实现不消费）
     * @param multiModel    多模态模型配置（本实现不消费）
     * @return 解析结果（全文哈希 + TEXT 块列表）
     * @throws IllegalArgumentException 未提取到任何文本内容（解析失败，交由调用方置 FAILED）
     */
    @Override
    public ParsedDocument parse(S3File s3File, String chunkStrategy, MultiModelConfig multiModel) {
        try (InputStream in = kbAssetStoragePort.open(s3File.objectKey())
                .orElseThrow(() -> new IllegalArgumentException("源文件对象不存在: " + s3File.objectKey()))
                .content();
             TikaInputStream tikaIn = TikaInputStream.get(in)) {
            // handler / Metadata / ParseContext 承载单次解析的状态与输出，必须每次新建（复用会串数据）；
            // 仅重量级编译产物 autoDetectParser 作为实例字段复用（线程安全依据见其字段注释与 design D3）。
            BodyContentHandler handler = new BodyContentHandler(NO_WRITE_LIMIT);
            autoDetectParser.parse(tikaIn, handler, new Metadata(), new ParseContext());
            String fullText = handler.toString();
            List<ContentBlockVO> blocks = splitToTextBlocks(fullText);
            if (CollectionUtils.isEmpty(blocks)) {
                throw new IllegalArgumentException(ERROR_PARSE_FAILED);
            }
            return new ParsedDocument(computeMd5(fullText), blocks);
        } catch (IOException e) {
            throw new RuntimeException(ERROR_READ_OBJECT_FAILED + s3File.objectKey(), e);
        } catch (TikaException | SAXException e) {
            throw new IllegalArgumentException(ERROR_PARSE_FAILED, e);
        }
    }

    /**
     * 全文按空行分段切为多个 TEXT 块：每段 trim 后非空才保留，meta 统一携带 page=0 占位。
     *
     * @param fullText Tika 提取的全文
     * @return TEXT 内容块列表（可能为空）
     */
    private List<ContentBlockVO> splitToTextBlocks(String fullText) {
        List<ContentBlockVO> blocks = new ArrayList<>();
        if (StringUtils.isBlank(fullText)) {
            return blocks;
        }
        Arrays.stream(fullText.split(BLOCK_SPLIT_REGEX))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .forEach(segment -> blocks.add(new ContentBlockVO(ContentBlockVO.TYPE_TEXT, segment,
                        Map.of(META_KEY_PAGE, DEFAULT_PAGE))));
        return blocks;
    }

    /**
     * 对全文按 UTF-8 计算 MD5（hex 小写），作为 ParsedDocument 幂等参考。
     *
     * @param text 待哈希文本
     * @return 32 位小写 hex 摘要
     */
    private String computeMd5(String text) {
        try {
            byte[] digest = MessageDigest.getInstance(ALGORITHM_MD5).digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }
}