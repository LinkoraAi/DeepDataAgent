package com.linkroa.deepdataagent.memory.file;

import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.model.ConversationContext;
import com.linkroa.deepdataagent.memory.model.ExtractedMemory;
import com.linkroa.deepdataagent.memory.util.MemoryText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Markdown 真相源文件管理器。
 *
 * <p>负责创建 memory 目录结构、读写 MEMORY.md/USER.md/episodic/semantic/skills
 * 文件，并通过内存缓存 + 脏文件批量刷盘降低 I/O 开销。索引层只能从这里读取或重建，
 * 不应把 SQLite 内容当作长期记忆真相源。</p>
 */
public class MarkdownFileManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MarkdownFileManager.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    private final MemoryProperties properties;
    private final Path rootPath;
    private final ConcurrentHashMap<String, String> contentCache = new ConcurrentHashMap<>();
    private final Set<String> dirtyFiles = ConcurrentHashMap.newKeySet();

    public MarkdownFileManager(MemoryProperties properties) {
        this.properties = properties;
        this.rootPath = Path.of(properties.getRootPath()).toAbsolutePath().normalize();
    }

    public void initialize() {
        try {
            Files.createDirectories(rootPath);
            Files.createDirectories(rootPath.resolve("episodic"));
            Files.createDirectories(rootPath.resolve("semantic"));
            Files.createDirectories(rootPath.resolve("skills"));
            Files.createDirectories(rootPath.resolve(".index"));
            ensureBaselineFile("MEMORY.md", "# 长期记忆\n\n## 自动沉淀\n");
            ensureBaselineFile("USER.md", "# 用户画像\n\n## 自动沉淀\n");
            preloadCriticalFiles();
        } catch (IOException e) {
            throw new UncheckedIOException("初始化记忆目录失败: " + rootPath, e);
        }
    }

    public Set<String> writeExtractedMemories(ConversationContext context, List<ExtractedMemory> memories) {
        Set<String> changedPaths = new LinkedHashSet<>();
        for (ExtractedMemory memory : memories) {
            switch (memory.layer()) {
                case "episodic" -> changedPaths.add(writeEpisodicMemory(context, memory));
                case "semantic" -> changedPaths.addAll(writeSemanticMemory(memory));
                case "skills" -> changedPaths.add(writeSkillMemory(memory));
                default -> log.debug("跳过未知记忆层级: {}", memory.layer());
            }
        }
        return changedPaths;
    }

    public void append(String relativePath, String content) {
        String path = normalizeRelativePath(relativePath);
        String current = readIfExists(path);
        String separator = current.isBlank() || current.endsWith("\n") ? "" : "\n";
        // append 只修改内存缓存并标脏，真正落盘集中在 flushDirtyFiles，避免一轮 record 多次 I/O。
        contentCache.put(path, current + separator + content);
        dirtyFiles.add(path);
        enforceCacheLimit();
    }

    public void write(String relativePath, String content) {
        String path = normalizeRelativePath(relativePath);
        contentCache.put(path, content);
        dirtyFiles.add(path);
        enforceCacheLimit();
    }

    public String read(String relativePath) {
        String path = normalizeRelativePath(relativePath);
        return contentCache.computeIfAbsent(path, this::readFromDisk);
    }

    public String readIfExists(String relativePath) {
        String path = normalizeRelativePath(relativePath);
        Path fullPath = resolve(path);
        if (!Files.exists(fullPath)) {
            return contentCache.getOrDefault(path, "");
        }
        return read(path);
    }

    public String readLines(String relativePath, int startLine, int endLine) {
        String content = read(relativePath);
        String[] lines = content.split("\\R", -1);
        int from = Math.max(0, startLine - 1);
        int to = Math.clamp(from, endLine, lines.length);
        List<String> selected = new ArrayList<>(Arrays.asList(lines).subList(from, to));
        return String.join("\n", selected).strip();
    }

    public List<String> scanMarkdownFiles() {
        if (!Files.exists(rootPath)) {
            return List.of();
        }
        try (var stream = Files.walk(rootPath)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    // .index 是可重建索引目录，不能反向参与 Markdown 真相源扫描。
                    .filter(path -> !path.startsWith(rootPath.resolve(".index")))
                    .map(path -> rootPath.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("扫描记忆 Markdown 文件失败", e);
        }
    }

    public Path resolve(String relativePath) {
        String path = normalizeRelativePath(relativePath);
        Path fullPath = rootPath.resolve(path).normalize();
        // 防止通过 ../ 或绝对路径越过 memory 根目录写文件。
        if (!fullPath.startsWith(rootPath)) {
            throw new IllegalArgumentException("非法记忆文件路径: " + relativePath);
        }
        return fullPath;
    }

    public void flushDirtyFiles() {
        Set<String> snapshot = new LinkedHashSet<>(dirtyFiles);
        for (String path : snapshot) {
            String content = contentCache.get(path);
            if (content == null) {
                dirtyFiles.remove(path);
                continue;
            }
            try {
                Path fullPath = resolve(path);
                Files.createDirectories(fullPath.getParent());
                Files.writeString(fullPath, content, StandardCharsets.UTF_8);
                dirtyFiles.remove(path);
            } catch (IOException e) {
                log.error("记忆文件刷盘失败: {}", path, e);
            }
        }
    }

    public void shutdown() {
        flushDirtyFiles();
        contentCache.clear();
    }

    @Override
    public void close() {
        shutdown();
    }

    private String writeEpisodicMemory(ConversationContext context, ExtractedMemory memory) {
        String date = DATE.format(context.createdAt());
        // 同一会话使用稳定 hash 文件名，重复 record 同一批消息时会覆盖同一 session 文件。
        String timeHash = MemoryText.sha256(context.sessionId() + context.createdAt()).substring(0, 10);
        String path = "episodic/" + date + "/session-" + timeHash + ".md";
        String content = """
                ---
                session_id: %s
                date: %s
                layer: episodic
                sub_category: %s
                created_at: %s
                memory_count: 1
                ---
                
                # 会话记录 - %s
                
                ### %s [id: %s] [importance: %.2f]
                
                %s
                """.formatted(
                context.sessionId(),
                date,
                memory.subCategory(),
                DATE_TIME.format(memory.createdAt()),
                date,
                memory.title(),
                memory.id(),
                memory.importance(),
                memory.content().strip()
        );
        write(path, content);
        return path;
    }

    private Set<String> writeSemanticMemory(ExtractedMemory memory) {
        Set<String> changed = new LinkedHashSet<>();
        // semantic 详情文件保存完整条目，MEMORY.md/USER.md 只保存适合启动快照的摘要。
        String detailPath = switch (memory.subCategory()) {
            case "preference" -> "semantic/preferences.md";
            case "rule" -> "semantic/rules.md";
            default -> "semantic/facts.md";
        };
        append(detailPath, memoryEntryMarkdown(memory));
        changed.add(detailPath);

        String digestPath = "preference".equals(memory.subCategory()) ? "USER.md" : "MEMORY.md";
        append(digestPath, "- [%s] %s".formatted(DATE_TIME.format(memory.createdAt()), memory.title()));
        changed.add(digestPath);
        return changed;
    }

    private String writeSkillMemory(ExtractedMemory memory) {
        String path = "skills/" + MemoryText.slug(memory.title(), "auto-skill") + ".md";
        String current = readIfExists(path);
        if (current.isBlank()) {
            // 技能文件按标题聚合，首次创建写入 front matter，后续追加新的经验条目。
            current = """
                    ---
                    layer: skills
                    sub_category: skill
                    created_at: %s
                    ---
                    
                    # %s
                    """.formatted(DATE_TIME.format(memory.createdAt()), memory.title());
        }
        write(path, current.stripTrailing() + "\n\n" + memoryEntryMarkdown(memory));
        return path;
    }

    private String memoryEntryMarkdown(ExtractedMemory memory) {
        return """
                
                ### %s [id: %s] [importance: %.2f]
                > 创建: %s | 来源会话: %s | layer: %s | sub_category: %s
                
                %s
                """.formatted(
                memory.title(),
                memory.id(),
                memory.importance(),
                DATE_TIME.format(memory.createdAt()),
                memory.sourceSessionId(),
                memory.layer(),
                memory.subCategory(),
                memory.content().strip()
        );
    }

    private void ensureBaselineFile(String relativePath, String body) throws IOException {
        Path fullPath = resolve(relativePath);
        if (Files.exists(fullPath)) {
            return;
        }
        Files.createDirectories(fullPath.getParent());
        String content = """
                ---
                last_updated: %s
                version: 1
                ---
                
                %s
                """.formatted(DATE_TIME.format(java.time.Instant.now()), body);
        Files.writeString(fullPath, content, StandardCharsets.UTF_8);
    }

    private void preloadCriticalFiles() {
        for (String file : properties.getIo().getCache().getPreloadFiles()) {
            try {
                read(file);
            } catch (RuntimeException e) {
                log.debug("预加载记忆文件失败或不存在: {}", file, e);
            }
        }
    }

    private String readFromDisk(String relativePath) {
        try {
            Path fullPath = resolve(relativePath);
            if (!Files.exists(fullPath)) {
                return "";
            }
            return Files.readString(fullPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取记忆文件失败: " + relativePath, e);
        }
    }

    private void enforceCacheLimit() {
        int maxSize = Math.max(properties.getIo().getCache().getMaxCacheSize(), 1);
        if (contentCache.size() <= maxSize) {
            return;
        }
        // 只淘汰干净缓存，避免尚未刷盘的脏文件内容丢失。
        for (String key : contentCache.keySet()) {
            if (!dirtyFiles.contains(key)) {
                contentCache.remove(key);
                if (contentCache.size() <= maxSize) {
                    return;
                }
            }
        }
    }

    private String normalizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("记忆文件路径不能为空");
        }
        String normalized = relativePath.replace('\\', '/');
        Path path = Path.of(normalized).normalize();
        if (path.isAbsolute() || normalized.contains("..")) {
            throw new IllegalArgumentException("非法记忆文件路径: " + relativePath);
        }
        return path.toString().replace('\\', '/');
    }
}
