package com.linkroa.deepdataagent.file.domain.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文件内容类型探测领域服务（mime_type 服务端探测，按平面分化的准入策略）。
 * <p><b>上传平面</b>（{@link #resolveTextMimeType}）：文件的 {@code mime_type} 一律由服务端探测，
 * 不接受客户端申报值——先按文件名扩展名拒绝已知二进制类型（图片 / 音视频 / 压缩包 / 办公文档等），
 * 再对内容做严格 UTF-8 解码与 NUL 字节检测，非文本内容一律拒绝（400 语义）；
 * 通过检测后按扩展名映射具体文本 MIME，未知扩展名兜底 {@code text/plain}。</p>
 * <p><b>产出登记平面</b>（{@link #resolveArtifactMimeType}）：沙箱产出天然可为任意字节
 * （图表 PNG、报表 XLSX 等），准入放宽为「仅拒 null 内容」，不做文本性检测；
 * MIME 按扩展名映射已知文本 / 二进制类型，未知一律兜底 {@code application/octet-stream}。</p>
 */
@Service
public class FileContentTypeDomainService {

    /** 缺省文本 MIME（未知扩展名兜底） */
    public static final String DEFAULT_TEXT_MIME = "text/plain";

    /** 已知文本扩展名 → MIME 映射（大小写不敏感） */
    private static final Map<String, String> TEXT_MIME_BY_EXTENSION = Map.ofEntries(
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"),
            Map.entry("markdown", "text/markdown"),
            Map.entry("csv", "text/csv"),
            Map.entry("tsv", "text/tab-separated-values"),
            Map.entry("json", "application/json"),
            Map.entry("jsonl", "application/jsonl"),
            Map.entry("ndjson", "application/jsonl"),
            Map.entry("xml", "application/xml"),
            Map.entry("yaml", "application/yaml"),
            Map.entry("yml", "application/yaml"),
            Map.entry("toml", "application/toml"),
            Map.entry("sql", "application/sql"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("css", "text/css"),
            Map.entry("js", "text/javascript"),
            Map.entry("mjs", "text/javascript"),
            Map.entry("ts", "text/plain"),
            Map.entry("tsx", "text/plain"),
            Map.entry("jsx", "text/plain"),
            Map.entry("java", "text/x-java-source"),
            Map.entry("py", "text/x-python"),
            Map.entry("go", "text/plain"),
            Map.entry("rs", "text/plain"),
            Map.entry("sh", "application/x-sh"),
            Map.entry("bat", "text/plain"),
            Map.entry("ps1", "text/plain"),
            Map.entry("ini", "text/plain"),
            Map.entry("conf", "text/plain"),
            Map.entry("env", "text/plain"),
            Map.entry("log", "text/plain"),
            Map.entry("properties", "text/plain"),
            Map.entry("svg", "image/svg+xml")
    );

    /** 显式拒绝的二进制 / 富媒体扩展名（图片 / 音视频 / 压缩包 / 办公文档 / 可执行等） */
    private static final Set<String> REJECTED_BINARY_EXTENSIONS = Set.of(
            // 图片
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "tif", "tiff", "heic", "avif",
            // 音视频
            "mp3", "wav", "flac", "aac", "ogg", "m4a", "mp4", "mov", "avi", "mkv", "webm", "flv",
            // 压缩包
            "zip", "rar", "7z", "gz", "tgz", "bz2", "xz", "tar",
            // 办公 / 文档二进制
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            // 可执行 / 对象码
            "exe", "dll", "so", "dylib", "bin", "class", "jar", "war", "msi", "dmg",
            // 数据 / 字体 / 模型
            "db", "sqlite", "parquet", "orc", "avro", "ttf", "otf", "woff", "woff2",
            "pt", "onnx", "safetensors", "pkl"
    );

    /** 产出面缺省 MIME（未知扩展名兜底） */
    public static final String DEFAULT_ARTIFACT_MIME = "application/octet-stream";

    /** 已知二进制扩展名 → MIME 映射（产出登记面使用，大小写不敏感） */
    private static final Map<String, String> BINARY_MIME_BY_EXTENSION = Map.ofEntries(
            // 图片
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("ico", "image/vnd.microsoft.icon"),
            Map.entry("tif", "image/tiff"),
            Map.entry("tiff", "image/tiff"),
            Map.entry("heic", "image/heic"),
            Map.entry("avif", "image/avif"),
            // 音频
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/x-wav"),
            Map.entry("flac", "audio/flac"),
            Map.entry("aac", "audio/aac"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("m4a", "audio/mp4"),
            // 视频
            Map.entry("mp4", "video/mp4"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("avi", "video/x-msvideo"),
            Map.entry("mkv", "video/x-matroska"),
            Map.entry("webm", "video/webm"),
            Map.entry("flv", "video/x-flv"),
            // 压缩包
            Map.entry("zip", "application/zip"),
            Map.entry("rar", "application/vnd.rar"),
            Map.entry("7z", "application/x-7z-compressed"),
            Map.entry("gz", "application/gzip"),
            Map.entry("tgz", "application/gzip"),
            Map.entry("bz2", "application/x-bzip2"),
            Map.entry("xz", "application/x-xz"),
            Map.entry("tar", "application/x-tar"),
            // 办公 / 文档二进制
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    );

    /**
     * 探测沙箱产出文件的 MIME 类型（产出登记平面，不做文本性准入）。
     * <p>与 {@link #resolveTextMimeType} 的区别：产出天然可为任意字节，
     * 仅 null 内容被拒绝；扩展名命中已知文本 / 二进制映射时返回具体 MIME，
     * 未命中一律兜底 {@code application/octet-stream}。</p>
     *
     * @param filename 文件名（按扩展名判定已知类型）
     * @param content  文件原始字节内容
     * @return 服务端探测出的 mime_type（如 image/png、application/pdf、application/octet-stream）
     * @throws IllegalArgumentException 内容为 null（400 语义）
     */
    public String resolveArtifactMimeType(String filename, byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
        String extension = extractExtension(filename);
        String mime = TEXT_MIME_BY_EXTENSION.get(extension);
        if (mime == null) {
            mime = BINARY_MIME_BY_EXTENSION.get(extension);
        }
        return mime == null ? DEFAULT_ARTIFACT_MIME : mime;
    }

    /**
     * 探测上传文件的 MIME 类型并执行文本类准入校验。
     *
     * @param filename 文件名（按扩展名判定已知类型）
     * @param content  文件原始字节内容
     * @return 服务端探测出的 mime_type（如 text/markdown、application/json、text/plain）
     * @throws IllegalArgumentException 二进制扩展名 / 非 UTF-8 / 含 NUL 字节内容（400 语义）
     */
    public String resolveTextMimeType(String filename, byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
        String extension = extractExtension(filename);
        if (REJECTED_BINARY_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("仅接受文本类文件，不支持图片/音视频/压缩包等二进制文件");
        }
        assertTextContent(content);
        String mime = TEXT_MIME_BY_EXTENSION.get(extension);
        return mime == null ? DEFAULT_TEXT_MIME : mime;
    }

    /**
     * 严格 UTF-8 解码 + NUL 字节检测（任一失败即视为二进制内容拒绝）。
     */
    private void assertTextContent(byte[] content) {
        if (content.length == 0) {
            return;
        }
        for (byte b : content) {
            if (b == 0) {
                throw new IllegalArgumentException("文件内容包含 NUL 字节，疑似二进制文件，已拒绝");
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(content));
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("文件内容不是合法的 UTF-8 文本，仅接受文本类文件");
        }
    }

    /**
     * 提取小写扩展名（无扩展名返回空串）。
     */
    private String extractExtension(String filename) {
        if (StringUtils.isBlank(filename)) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
