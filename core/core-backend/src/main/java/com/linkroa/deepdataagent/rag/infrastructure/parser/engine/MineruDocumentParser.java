package com.linkroa.deepdataagent.rag.infrastructure.parser.engine;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.knowledgebase.domain.model.MultiModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import com.linkroa.deepdataagent.rag.domain.port.DocumentParser;
import com.linkroa.deepdataagent.rag.infrastructure.client.MineruClient;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 远程 MinerU 文档解析实现（provider=MINERU 时使用）。
 * <p>基于 {@link MineruClient} 调用 MinerU 异步解析服务：提交文件 → 轮询 → 拉取 content_list 结果，
 * content_list 是内容块映射的唯一来源（return_md=false / return_content_list=true 固定开关）。
 * 结果按提交文件名的 stem（去扩展名）定位对应文件，逐条映射为 ContentBlockVO 五类统一结构：
 * 坐标、表格体等原始字段原样置于 meta（便于下游坐标标签剥离与多模态描述），页码统一映射为 page。
 * 恒发 return_images=true 时响应同 stem 下的 images 载荷（图片名 → base64）解码为字节随解析产物一并返回，
 * 由摄入 Worker 落对象存储（图片字节不进块 meta，故不会随 original_item 落库）。
 * <b>无自动回退</b>：解析失败抛异常交由调用方置 FAILED，不切换本地引擎。</p>
 * <p><b>空内容处置</b>：解析成功但未提取到有效内容（content_list 为空数组，如空白图片）时
 * 产出含文件名的占位 TEXT 块（与本地 Tika / 直读口径一致），保证文档终态可 PROCESSED 且切片数 ≥ 1。</p>
 */
public class MineruDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(MineruDocumentParser.class);

    /** meta 键：页码。 */
    private static final String META_KEY_PAGE = "page";

    /** content_list 原始类型：文本。 */
    private static final String RAW_TYPE_TEXT = "text";

    /** content_list 原始类型：图片。 */
    private static final String RAW_TYPE_IMAGE = "image";

    /** content_list 原始类型：表格。 */
    private static final String RAW_TYPE_TABLE = "table";

    /** content_list 原始类型：公式。 */
    private static final String RAW_TYPE_EQUATION = "equation";

    /** 原始字段键：text。 */
    private static final String FIELD_TEXT = "text";

    /** 原始字段键：image_caption。 */
    private static final String FIELD_IMAGE_CAPTION = "image_caption";

    /** 原始字段键：table_caption。 */
    private static final String FIELD_TABLE_CAPTION = "table_caption";

    /** 空串（image/table 无 caption 时的兜底文本）。 */
    private static final String STRING_EMPTY = "";

    /**
     * data URL 前缀（MinerU 3.4.2 images 载荷形态 {@code data:{mime};base64,{base64}}，
     * 严格 Base64 解码前必须剥离）。
     */
    private static final Pattern DATA_URL_PREFIX = Pattern.compile("^data:[^;]*;base64,");

    /** 块哈希拼接分隔符。 */
    private static final String BLOCK_HASH_SEPARATOR = "\t";

    /** MD5 摘要算法名。 */
    private static final String ALGORITHM_MD5 = "MD5";

    /** 结果缺少对应文件内容的提示。 */
    private static final String ERROR_MISSING_FILE_CONTENT = "解析结果缺少对应文件的内容";

    /** 读取对象失败提示前缀。 */
    private static final String ERROR_READ_OBJECT_FAILED = "读取源文件对象失败: ";

    /**
     * 本引擎支持解析的文档类型集合（MINERU：办公文档（含表格）+ 图片）。
     * <p>XLS / XLSX 为实测补充：MinerU 3.4.2（protocol v2）可正常解析表格文件，
     * 此前未声明会导致表格被本地直读兜底处理（二进制不可解码时退化为占位块）。</p>
     */
    private static final Set<FileType> SUPPORTED_TYPES = EnumSet.of(
            FileType.PDF, FileType.DOC, FileType.DOCX, FileType.XLS, FileType.XLSX,
            FileType.PPT, FileType.PPTX, FileType.PNG, FileType.JPG, FileType.JPEG);

    /** MinerU HTTP 客户端。 */
    private final MineruClient mineruClient;

    /** 知识库对象资产存储访问端口（跨 BC 消费，读取源文件字节）。 */
    private final KbAssetStoragePort kbAssetStoragePort;

    /**
     * 构造器：注入 MinerU 客户端与对象资产存储访问端口。
     *
     * @param mineruClient      远程 MinerU 解析客户端
     * @param kbAssetStoragePort 对象资产存储访问端口（读取源文件字节）
     */
    public MineruDocumentParser(MineruClient mineruClient, KbAssetStoragePort kbAssetStoragePort) {
        this.mineruClient = mineruClient;
        this.kbAssetStoragePort = kbAssetStoragePort;
    }

    @Override
    public Set<FileType> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    /**
     * 获取本引擎声明的支持类型集合（不可变副本）。
     * <p>供解析引擎能力查询与上传校验复用，避免调用方为读取能力而构造客户端实例。</p>
     *
     * @return 支持的文件类型集合（不可变）
     */
    public static Set<FileType> declaredSupportedTypes() {
        return Set.copyOf(SUPPORTED_TYPES);
    }

    /**
     * 解析 S3 源文件：读取字节 → 提交 MinerU 异步解析 → 按 stem 定位 content_list → 映射为统一内容块，
     * 并将响应携带的媒体图片 base64 载荷解码为字节附入解析产物（图片名 → 字节，解码失败单图跳过）。
     * <p>注意：chunkStrategy / multiModel 当前解析层不消费（为端口签名一致性保留）。</p>
     *
     * @param s3File        源文件对象引用
     * @param chunkStrategy 分块策略标识（本实现不消费）
     * @param multiModel    多模态模型配置（本实现不消费）
     * @return 解析结果（内容哈希 + 内容块列表 + 媒体图片字节载荷；解析成功但无有效内容时为占位 TEXT 块）
     * @throws IllegalArgumentException 结果缺少对应文件内容（stem 不在 results 中，协议破坏，交由调用方置 FAILED）
     */
    @Override
    public ParsedDocument parse(S3File s3File, String chunkStrategy, MultiModelConfig multiModel) {
        byte[] content = readObjectBytes(s3File);
        String fileName = extractFileName(s3File.objectKey());
        String stem = extractStem(fileName);
        MineruClient.ParsedResult parsedResult = mineruClient.parse(content, fileName);
        if (!parsedResult.results().containsKey(stem)) {
            throw new IllegalArgumentException(ERROR_MISSING_FILE_CONTENT);
        }
        List<MineruClient.Block> sourceBlocks = parsedResult.results().get(stem);
        if (CollectionUtils.isEmpty(sourceBlocks)) {
            // 解析成功但无有效内容（如空白图片 content_list 为空数组）：产占位 TEXT 块，
            // 保证文档终态可 PROCESSED 且切片数 ≥ 1（与本地 Tika / 直读的空文本口径一致）
            log.info("MinerU 解析成功但无有效内容，产出占位块, objectKey={}, stem={}", s3File.objectKey(), stem);
            return LocalReadParseSupport.placeholderDocument(fileName);
        }
        List<ContentBlockVO> blocks = sourceBlocks.stream().map(this::toContentBlock).toList();
        Map<String, byte[]> images = decodeImages(parsedResult.images().get(stem));
        return ParsedDocument.withImages(computeMd5(buildHashRawText(blocks)), blocks, images);
    }

    /**
     * base64 图片载荷解码为字节映射（保持 MinerU 返回顺序）。
     * <p>载荷值兼容两形态：纯 base64 串与 <b>data URL</b>（MinerU 3.4.2 返回
     * {@code data:{mime};base64,{base64}}，解码前 MUST 剥离前缀——严格 Base64 解码器
     * 遇 {@code ':'} 即抛，前缀不剥离会导致全部图片解码失败、切片媒体引用整体缺失）。</p>
     * <p>容错口径：单图解码失败 / 载荷空白仅告警跳过，不阻断解析——该图后续不会形成对象存储引用。</p>
     *
     * @param payload 图片名 → base64 原文（可含 data URL 前缀），可为 null（响应未携带图片）
     * @return 图片名 → 图片字节（不可变，无可用图片时为空映射）
     */
    private Map<String, byte[]> decodeImages(Map<String, String> payload) {
        if (MapUtils.isEmpty(payload)) {
            return Map.of();
        }
        Map<String, byte[]> images = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : payload.entrySet()) {
            String imgName = entry.getKey();
            if (StringUtils.isBlank(entry.getValue())) {
                log.warn("MinerU 图片载荷为空，跳过该图, imgName={}", imgName);
                continue;
            }
            try {
                String base64 = DATA_URL_PREFIX.matcher(entry.getValue().trim()).replaceFirst(StringUtils.EMPTY);
                images.put(imgName, Base64.getDecoder().decode(base64));
            } catch (IllegalArgumentException e) {
                log.warn("MinerU 图片 base64 解码失败，跳过该图, imgName={}", imgName, e);
            }
        }
        return Map.copyOf(images);
    }

    /**
     * 经存储访问端口读取源文件对象全部字节（try-with-resources 保证流关闭）。
     *
     * @param s3File 源文件对象引用
     * @return 文件内容字节
     */
    private byte[] readObjectBytes(S3File s3File) {
        try (InputStream in = kbAssetStoragePort.open(s3File.objectKey())
                .orElseThrow(() -> new IllegalArgumentException("源文件对象不存在: " + s3File.objectKey()))
                .content()) {
            if (ObjectUtils.isEmpty(in)) {
                throw new IllegalArgumentException("源文件对象不存在: " + s3File.objectKey());
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException(ERROR_READ_OBJECT_FAILED + s3File.objectKey(), e);
        }
    }

    /**
     * 从对象键推导提交给 MinerU 的文件名：取路径最后一段（含扩展名则保留）。
     *
     * @param objectKey 对象键
     * @return 文件名
     */
    private String extractFileName(String objectKey) {
        int slashIndex = Math.max(objectKey.lastIndexOf('/'), objectKey.lastIndexOf('\\'));
        return slashIndex >= 0 ? objectKey.substring(slashIndex + 1) : objectKey;
    }

    /**
     * 取文件名 stem：去掉最后一个扩展名（无扩展名则取全名），用于在结果 map 中定位 content_list。
     *
     * @param fileName 提交给 MinerU 的文件名
     * @return stem
     */
    private String extractStem(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    /**
     * content_list 条目映射为统一内容块：类型五类映射（未知 → GENERIC）、
     * 文本取值（image/table 用 caption，其余用 text）、meta 必含 page 并保留全部原始字段。
     *
     * @param block MinerU content_list 原始块
     * @return 统一内容块
     */
    private ContentBlockVO toContentBlock(MineruClient.Block block) {
        Map<String, Object> meta = new HashMap<>();
        for (Map.Entry<String, Object> entry : block.fields().entrySet()) {
            meta.put(entry.getKey(), entry.getValue());
        }
        meta.put(META_KEY_PAGE, block.pageIdx());
        return new ContentBlockVO(mapType(block.type()), extractText(block), meta);
    }

    /**
     * MinerU 原始类型映射为 ContentBlockVO 五类统一类型；未知类型兜底 GENERIC。
     */
    private String mapType(String rawType) {
        if (StringUtils.equals(rawType, RAW_TYPE_TEXT)) {
            return ContentBlockVO.TYPE_TEXT;
        }
        if (StringUtils.equals(rawType, RAW_TYPE_IMAGE)) {
            return ContentBlockVO.TYPE_IMAGE;
        }
        if (StringUtils.equals(rawType, RAW_TYPE_TABLE)) {
            return ContentBlockVO.TYPE_TABLE;
        }
        if (StringUtils.equals(rawType, RAW_TYPE_EQUATION)) {
            return ContentBlockVO.TYPE_EQUATION;
        }
        return ContentBlockVO.TYPE_GENERIC;
    }

    /**
     * 取块文本：text/equation/generic 用项内 text（可为空串），image 用 image_caption（无则空串），
     * table 用 table_caption（无则空串）。
     *
     * @param block MinerU 原始块
     * @return 块文本（可能为空串）
     */
    private String extractText(MineruClient.Block block) {
        String key = FIELD_TEXT;
        if (StringUtils.equals(block.type(), RAW_TYPE_IMAGE)) {
            key = FIELD_IMAGE_CAPTION;
        } else if (StringUtils.equals(block.type(), RAW_TYPE_TABLE)) {
            key = FIELD_TABLE_CAPTION;
        }
        Object value = block.fields().get(key);
        return ObjectUtils.isEmpty(value) ? STRING_EMPTY : String.valueOf(value);
    }

    /**
     * 拼接块哈希原文：按顺序以 type + TAB + text 连接。
     */
    private String buildHashRawText(List<ContentBlockVO> blocks) {
        StringBuilder raw = new StringBuilder();
        for (ContentBlockVO block : blocks) {
            raw.append(block.type()).append(BLOCK_HASH_SEPARATOR).append(block.text());
        }
        return raw.toString();
    }

    /**
     * 对块哈希原文按 UTF-8 计算 MD5（hex 小写），作为 ParsedDocument 幂等参考。
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