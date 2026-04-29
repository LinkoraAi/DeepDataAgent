package com.linkroa.deepdataagent.memory.index;

import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.model.MemoryChunk;
import com.linkroa.deepdataagent.memory.util.MemoryText;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 分块器。
 *
 * <p>把单个 Markdown 记忆文件拆成可检索的连续行片段，同时解析 layer、
 * sub_category、memory_id、importance 和 created_at 等索引元数据。</p>
 */
public class MarkdownChunker {

    private static final Pattern MEMORY_ID = Pattern.compile("mem-[a-zA-Z0-9\\-]+");
    private static final Pattern IMPORTANCE = Pattern.compile("importance:\\s*([0-9.]+)|\\[importance:\\s*([0-9.]+)]");
    private static final Pattern FRONT_MATTER_FIELD = Pattern.compile("^([a-zA-Z_\\-]+):\\s*(.+)$");

    private final MemoryProperties properties;

    public MarkdownChunker(MemoryProperties properties) {
        this.properties = properties;
    }

    public List<MemoryChunk> chunk(String relativePath, String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int maxChars = Math.max(properties.getChunking().getMaxChars(), 200);
        List<MemoryChunk> chunks = new ArrayList<>();

        int startLine = 1;
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // Markdown 标题通常代表一个新的语义段；块已经足够大时优先在标题处切开，便于来源阅读。
            boolean headingBoundary = line.startsWith("## ") || line.startsWith("### ");
            if (headingBoundary && !buffer.isEmpty() && buffer.length() >= maxChars / 3) {
                addChunk(chunks, relativePath, startLine, i, buffer.toString());
                startLine = i + 1;
                buffer.setLength(0);
            }

            buffer.append(line).append('\n');
            // 标题边界之外仍用最大字符数兜底，防止单个超长段落让索引块过大。
            if (buffer.length() >= maxChars) {
                addChunk(chunks, relativePath, startLine, i + 1, buffer.toString());
                startLine = i + 2;
                buffer.setLength(0);
            }
        }

        if (!buffer.isEmpty()) {
            addChunk(chunks, relativePath, startLine, lines.length, buffer.toString());
        }
        return chunks;
    }

    public String layerOf(String relativePath, String content) {
        String frontMatterLayer = frontMatterValue(content, "layer");
        if (frontMatterLayer != null && !frontMatterLayer.isBlank()) {
            return normalizeLayer(frontMatterLayer);
        }
        String path = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (path.startsWith("episodic/")) {
            return "episodic";
        }
        if (path.startsWith("skills/")) {
            return "skills";
        }
        return "semantic";
    }

    public String subCategoryOf(String relativePath, String content) {
        String value = frontMatterValue(content, "sub_category");
        if (value != null && !value.isBlank()) {
            return value.strip().toLowerCase(Locale.ROOT);
        }
        String path = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (path.contains("preferences") || path.endsWith("user.md")) {
            return "preference";
        }
        if (path.contains("rules")) {
            return "rule";
        }
        if (path.startsWith("skills/")) {
            return "skill";
        }
        if (path.startsWith("episodic/")) {
            return "event";
        }
        return "fact";
    }

    private void addChunk(List<MemoryChunk> chunks, String relativePath, int startLine, int endLine, String rawContent) {
        String chunkContent = rawContent.strip();
        if (chunkContent.isBlank()) {
            return;
        }
        String layer = layerOf(relativePath, chunkContent);
        String subCategory = subCategoryOf(relativePath, chunkContent);
        // chunkId 必须同时包含位置和内容，文件局部修改后能自然生成新的索引分块。
        String idSeed = relativePath + ":" + startLine + ":" + endLine + ":" + chunkContent;
        String chunkId = MemoryText.sha256(idSeed);
        String memoryId = extractMemoryId(chunkContent, chunkId);
        double importance = extractImportance(chunkContent);
        Instant createdAt = extractCreatedAt(chunkContent);

        chunks.add(new MemoryChunk(
                chunkId,
                memoryId,
                relativePath,
                layer,
                subCategory,
                startLine,
                Math.max(startLine, endLine),
                chunkContent,
                importance,
                createdAt,
                0,
                System.currentTimeMillis()
        ));
    }

    private static String extractMemoryId(String content, String chunkId) {
        Matcher matcher = MEMORY_ID.matcher(content);
        if (matcher.find()) {
            return matcher.group();
        }
        return "chunk-" + chunkId.substring(0, 12);
    }

    private static double extractImportance(String content) {
        Matcher matcher = IMPORTANCE.matcher(content);
        if (matcher.find()) {
            String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            try {
                return Math.clamp(Double.parseDouble(value), 0.0, 1.0);
            } catch (NumberFormatException ignored) {
                return 0.5;
            }
        }
        return 0.5;
    }

    private static Instant extractCreatedAt(String content) {
        String value = frontMatterValue(content, "created_at");
        if (value == null) {
            value = frontMatterValue(content, "date");
        }
        if (value == null) {
            value = frontMatterValue(content, "last_updated");
        }
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        String stripped = value.strip();
        try {
            return Instant.parse(stripped);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(stripped).atStartOfDay(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                return Instant.now();
            }
        }
    }

    private static String frontMatterValue(String content, String name) {
        if (content == null || !content.startsWith("---")) {
            return null;
        }
        String[] lines = content.split("\\R");
        // 只解析 YAML front matter 的第一段，正文中的同名字段不参与文件级元数据判断。
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i].strip())) {
                return null;
            }
            Matcher matcher = FRONT_MATTER_FIELD.matcher(lines[i].strip());
            if (matcher.matches() && matcher.group(1).equalsIgnoreCase(name)) {
                return matcher.group(2).strip();
            }
        }
        return null;
    }

    private static String normalizeLayer(String layer) {
        String normalized = layer.strip().toLowerCase(Locale.ROOT);
        if ("procedural".equals(normalized)) {
            return "skills";
        }
        return normalized;
    }
}
