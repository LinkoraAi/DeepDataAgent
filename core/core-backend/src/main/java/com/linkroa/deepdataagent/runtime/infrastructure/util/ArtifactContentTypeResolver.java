package com.linkroa.deepdataagent.runtime.infrastructure.util;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * 产出内容类型解析器（沙箱交付事件的 {@code content_type} 来源，5.4）。
 * <p>沙箱产出可为任意字节，按文件扩展名映射已知文本 / 二进制 MIME，未知一律兜底
 * {@code application/octet-stream}。</p>
 * <p><b>值表同源声明</b>：本表是 file BC 产出登记平面（{@code FileContentTypeDomainService#resolveArtifactMimeType}）
 * 的<b>镜像</b>——交付目标在登记成功当刻需要在事件里携带 {@code content_type}，而 file BC 登记端口
 * 当前只回传 {@code file_id}（未回传探测结果）。镜像表 MUST 与 file BC 产出面保持同步；
 * 待 file BC 登记面回传 MIME 后，本类应整体删除、改由登记结果承载。
 * 注意产出面与上传面口径不同：产出面不拒绝二进制扩展名，仅做映射与兜底。</p>
 */
public final class ArtifactContentTypeResolver {

    /** 产出面缺省 MIME（未知扩展名兜底）。 */
    public static final String DEFAULT_ARTIFACT_MIME = "application/octet-stream";

    /** 已知扩展名 → MIME 映射（大小写不敏感，产出面口径）。 */
    private static final Map<String, String> MIME_BY_EXTENSION = Map.ofEntries(
            // 文本 / 结构化数据
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
            Map.entry("svg", "image/svg+xml"),
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

    private ArtifactContentTypeResolver() {
    }

    /**
     * 按文件扩展名解析产出内容类型（未知扩展名兜底 {@code application/octet-stream}）。
     *
     * @param filename 文件名（可空 / 无扩展名 → 兜底）
     * @return 内容类型 MIME
     */
    public static String resolve(String filename) {
        String mime = MIME_BY_EXTENSION.get(extractExtension(filename));
        return mime == null ? DEFAULT_ARTIFACT_MIME : mime;
    }

    /** 提取小写扩展名（无扩展名返回空串）。 */
    private static String extractExtension(String filename) {
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