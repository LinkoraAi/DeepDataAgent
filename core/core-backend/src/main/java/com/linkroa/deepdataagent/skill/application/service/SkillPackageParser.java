package com.linkroa.deepdataagent.skill.application.service;

import com.linkroa.deepdataagent.shared.exception.RequestTooLargeException;
import com.linkroa.deepdataagent.skill.domain.model.SkillAsset;
import com.linkroa.deepdataagent.skill.domain.model.SkillContent;
import com.linkroa.deepdataagent.skill.domain.model.SkillPackage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 技能包解析器（multipart {@code files} → 统一逻辑文件树 → {@link SkillPackage}）。
 * <p>两种上传形态统一收敛：</p>
 * <ul>
 *   <li><b>单个 {@code .zip}</b>：压缩包体积 ≤50MB（超限 413 {@code request_too_large_error}），
 *       解压后总量 ≤50MB（超限 400）；</li>
 *   <li><b>裸文件树</b>：多个 files part，{@code filename} 携带相对路径，总量 ≤50MB。</li>
 * </ul>
 * <p>结构裁决：路径规整 + zip-slip 防护（拒绝绝对路径 / 反斜杠歧义 / {@code .} 与 {@code ..}
 * 穿越段 / 空段）→ 包顶级目录唯一 → 顶级目录内存在 {@code SKILL.md} → frontmatter 严格 YAML
 * 优先、失败逐行宽松回退（仅提取顶级 name/description）→ 顶级目录名必须等于 frontmatter name。
 * 任一违规抛 {@link IllegalArgumentException}（400 {@code invalid_request_error}）。</p>
 */
@Service
public class SkillPackageParser {

    /** zip 压缩包体积上限（字节）。 */
    public static final long MAX_PACKAGE_BYTES = 50L * 1024 * 1024;

    /** 解压后（或裸树）总字节上限。 */
    public static final long MAX_UNCOMPRESSED_BYTES = 50L * 1024 * 1024;

    /** 包内文件条目数上限（zip bomb 防护）。 */
    public static final int MAX_ENTRIES = 2000;

    /** 技能正文文件名（比较大小写不敏感）。 */
    private static final String SKILL_FILE = SkillContent.RESERVED_SKILL_FILE;

    /** YAML frontmatter 定界行。 */
    private static final String FRONTMATTER_DELIMITER = "---";

    /**
     * 解析 multipart 上传的技能包。
     *
     * @param files multipart files part（单个 zip 或裸文件树）
     * @return 解析后的技能包（frontmatter name/description + SKILL.md 原文 + 资源字节映射）
     * @throws IllegalArgumentException 非 multipart / 缺 files / 结构违规 / frontmatter 非法
     * @throws RequestTooLargeException zip 压缩包体积超过 50MB
     */
    public SkillPackage parse(List<MultipartFile> files) {
        Map<String, byte[]> tree = readTree(files);
        if (tree.isEmpty()) {
            throw new IllegalArgumentException("技能包不能为空，须以 multipart files 上传");
        }
        String topDirectory = requireUniqueTopDirectory(tree.keySet());
        String skillEntry = topDirectory + "/" + SKILL_FILE;
        String skillEntryKey = findCaseInsensitive(tree.keySet(), skillEntry);
        if (skillEntryKey == null) {
            throw new IllegalArgumentException("技能包顶级目录内必须存在 " + SKILL_FILE);
        }
        String markdown = new String(tree.get(skillEntryKey), StandardCharsets.UTF_8);
        Frontmatter frontmatter = parseFrontmatter(markdown);
        if (!topDirectory.equals(frontmatter.name())) {
            throw new IllegalArgumentException("技能包顶级目录名必须等于 SKILL.md frontmatter name: "
                    + topDirectory + " != " + frontmatter.name());
        }
        Map<String, byte[]> resources = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : tree.entrySet()) {
            if (entry.getKey().equals(skillEntryKey)) {
                continue;
            }
            String relativePath = entry.getKey().substring(topDirectory.length() + 1);
            // 资源原样保留字节：不做字符解码，二进制资源经存储 / zip 下载可逐字节还原
            resources.put(relativePath, entry.getValue());
        }
        return new SkillPackage(frontmatter.name(), frontmatter.description(),
                new SkillContent(markdown, resources));
    }

    /**
     * 由上传的 zip 原始文件名推导展示名缺省值（去目录段与 {@code .zip} 后缀）。
     *
     * @param files multipart files part
     * @return 展示名缺省候选；非单个 zip / 文件名不可用时返回 {@code null}
     */
    public String suggestedDisplayTitle(List<MultipartFile> files) {
        if (files == null || files.size() != 1 || files.get(0) == null) {
            return null;
        }
        String filename = normalizeFilename(files.get(0).getOriginalFilename());
        if (StringUtils.isBlank(filename) || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return null;
        }
        String title = StringUtils.trimToNull(filename.substring(0, filename.length() - ".zip".length()));
        if (title == null || title.length() > SkillAsset.MAX_DISPLAY_TITLE_LENGTH) {
            return null;
        }
        return title;
    }

    /**
     * 读取上传内容为「规范化相对路径 → 字节」逻辑文件树。
     */
    private Map<String, byte[]> readTree(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("技能包不能为空，须以 multipart files 上传");
        }
        List<MultipartFile> parts = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file != null && (!file.isEmpty() || StringUtils.isNotBlank(file.getOriginalFilename()))) {
                parts.add(file);
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("技能包不能为空，须以 multipart files 上传");
        }
        if (parts.size() == 1 && isZip(parts.get(0))) {
            return readZipTree(parts.get(0));
        }
        return readBareTree(parts);
    }

    /** 判定单个上传是否为 zip（扩展名或 PK 魔数）。 */
    private static boolean isZip(MultipartFile file) {
        String filename = normalizeFilename(file.getOriginalFilename());
        if (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return true;
        }
        try (InputStream in = file.getInputStream()) {
            byte[] magic = in.readNBytes(2);
            return magic.length == 2 && magic[0] == 'P' && magic[1] == 'K';
        } catch (IOException e) {
            throw new UncheckedIOException("技能包读取失败", e);
        }
    }

    /** 解压 zip 为逻辑文件树（zip-slip 防护 + 双 50MB 限额 + 条目数上限 + 有界条目读取）。 */
    private static Map<String, byte[]> readZipTree(MultipartFile file) {
        if (file.getSize() > MAX_PACKAGE_BYTES) {
            throw new RequestTooLargeException("技能包 zip 不能超过 50MB");
        }
        Map<String, byte[]> tree = new LinkedHashMap<>();
        long total = 0L;
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String path = normalizePath(entry.getName());
                if (path != null) {
                    long budget = MAX_UNCOMPRESSED_BYTES - total;
                    // 声明体积已知时先行卡口（未声明为 -1，交由有界读取兜底）
                    if (entry.getSize() > budget) {
                        throw new IllegalArgumentException("技能包解压后总量不能超过 50MB");
                    }
                    byte[] content = readEntryBounded(zip, budget);
                    total += content.length;
                    requireEntryBudget(tree, path);
                    tree.put(path, content);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("技能包 zip 解析失败: " + e.getMessage());
        }
        return tree;
    }

    /**
     * 有界读取当前 zip 条目（zip bomb 防护：不使用无上限的 {@code readAllBytes}，
     * 按剩余预算分块累积，超出预算即拒绝，避免单条目解压内容撑爆堆）。
     *
     * @param zip    当前条目输入流
     * @param budget 该条目可用的剩余字节预算
     * @return 条目字节
     * @throws IOException              读取失败
     * @throws IllegalArgumentException 条目实际字节数超出预算
     */
    private static byte[] readEntryBounded(ZipInputStream zip, long budget) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long read = 0L;
        int length;
        while ((length = zip.read(chunk)) > 0) {
            read += length;
            if (read > budget) {
                throw new IllegalArgumentException("技能包解压后总量不能超过 50MB");
            }
            buffer.write(chunk, 0, length);
        }
        return buffer.toByteArray();
    }

    /** 裸文件树读取（filename 携带相对路径，原样保留顶级目录段）。 */
    private static Map<String, byte[]> readBareTree(List<MultipartFile> parts) {
        Map<String, byte[]> tree = new LinkedHashMap<>();
        long total = 0L;
        for (MultipartFile part : parts) {
            String path = normalizePath(part.getOriginalFilename());
            if (path == null) {
                throw new IllegalArgumentException("技能包文件缺少有效的相对路径: " + part.getOriginalFilename());
            }
            byte[] content;
            try {
                content = part.getBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("技能包文件读取失败: " + path, e);
            }
            total += content.length;
            if (total > MAX_UNCOMPRESSED_BYTES) {
                throw new IllegalArgumentException("技能包总量不能超过 50MB");
            }
            requireEntryBudget(tree, path);
            tree.put(path, content);
        }
        return tree;
    }

    /** 条目数上限与重复路径校验。 */
    private static void requireEntryBudget(Map<String, byte[]> tree, String path) {
        if (tree.size() >= MAX_ENTRIES) {
            throw new IllegalArgumentException("技能包文件条目数不能超过" + MAX_ENTRIES + "个");
        }
        if (tree.containsKey(path)) {
            throw new IllegalArgumentException("技能包存在重复文件路径: " + path);
        }
    }

    /** 顶级目录唯一性裁决（全部路径的首段必须一致且必须存在）。 */
    private static String requireUniqueTopDirectory(Set<String> paths) {
        Set<String> tops = new LinkedHashSet<>();
        for (String path : paths) {
            int slash = path.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException("技能包文件必须包含顶级目录: " + path);
            }
            tops.add(path.substring(0, slash));
        }
        if (tops.size() != 1) {
            throw new IllegalArgumentException("技能包必须且只能有一个顶级目录");
        }
        return tops.iterator().next();
    }

    /** 大小写不敏感地在已知键集中定位目标路径。 */
    private static String findCaseInsensitive(Set<String> paths, String target) {
        for (String path : paths) {
            if (path.equalsIgnoreCase(target)) {
                return path;
            }
        }
        return null;
    }

    /**
     * 文件名规范化：剥离浏览器可能携带的目录段、统一分隔符、去首尾空白。
     *
     * @param raw 原始文件名
     * @return 纯文件名；不可用时返回 {@code null}
     */
    private static String normalizeFilename(String raw) {
        String filename = StringUtils.trimToNull(raw);
        if (filename == null) {
            return null;
        }
        filename = filename.replace('\\', '/');
        int slash = filename.lastIndexOf('/');
        if (slash >= 0) {
            filename = filename.substring(slash + 1);
        }
        return StringUtils.trimToNull(filename);
    }

    /**
     * 包内路径规整 + zip-slip 防护。
     *
     * @param raw 原始条目路径
     * @return 规范化相对路径；目录条目 / 空路径返回 {@code null}
     * @throws IllegalArgumentException 绝对路径 / 反斜杠歧义 / 空段 / 路径穿越
     */
    private static String normalizePath(String raw) {
        String value = StringUtils.trimToNull(raw);
        if (value == null) {
            return null;
        }
        if (value.startsWith("/") || value.startsWith("\\")) {
            throw new IllegalArgumentException("技能包文件路径不得为绝对路径: " + raw);
        }
        if (value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("技能包文件路径不得使用反斜杠分隔符: " + raw);
        }
        StringBuilder normalized = new StringBuilder();
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("技能包文件路径不得包含路径穿越: " + raw);
            }
            normalized.append(segment).append('/');
        }
        if (normalized.length() == 0) {
            return null;
        }
        normalized.setLength(normalized.length() - 1);
        return normalized.toString();
    }

    /**
     * 解析 SKILL.md 的 YAML frontmatter（严格 YAML 优先、失败逐行宽松回退）。
     *
     * @param markdown SKILL.md 原文
     * @return frontmatter 的 name / description
     * @throws IllegalArgumentException 缺少 frontmatter 块 / name 或 description 缺失或非法
     */
    private static Frontmatter parseFrontmatter(String markdown) {
        List<String> lines = List.of(markdown.split("\r?\n", -1));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (StringUtils.isNotBlank(lines.get(i))) {
                if (!FRONTMATTER_DELIMITER.equals(lines.get(i).trim())) {
                    throw new IllegalArgumentException("SKILL.md 必须以 YAML frontmatter 开头");
                }
                start = i;
                break;
            }
        }
        if (start < 0) {
            throw new IllegalArgumentException("SKILL.md 必须以 YAML frontmatter 开头");
        }
        int end = -1;
        for (int i = start + 1; i < lines.size(); i++) {
            if (FRONTMATTER_DELIMITER.equals(lines.get(i).trim())) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new IllegalArgumentException("SKILL.md 的 YAML frontmatter 缺少结束定界符");
        }
        List<String> block = lines.subList(start + 1, end);

        Map<String, String> strict = tryStrictYaml(block);
        String name = strict == null ? lenientValue(block, "name") : strict.get("name");
        String description = strict == null ? lenientValue(block, "description") : strict.get("description");
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("SKILL.md frontmatter 缺少 name");
        }
        if (StringUtils.isBlank(description)) {
            throw new IllegalArgumentException("SKILL.md frontmatter 缺少 description");
        }
        return new Frontmatter(name.trim(), description.trim());
    }

    /**
     * 严格 YAML 解析尝试：仅接受顶层标量键值行（缩进行 / 无冒号行 / 块标量一律判定为非严格形态）。
     *
     * @param block frontmatter 内容行
     * @return 顶层键值映射；判定为非严格形态返回 {@code null}（触发宽松回退）
     */
    private static Map<String, String> tryStrictYaml(List<String> block) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : block) {
            if (StringUtils.isBlank(line) || line.trim().startsWith("#")) {
                continue;
            }
            if (Character.isWhitespace(line.charAt(0)) || line.indexOf(':') < 0) {
                return null;
            }
            int colon = line.indexOf(':');
            String key = line.substring(0, colon).trim();
            String value = unquote(line.substring(colon + 1).trim());
            if (key.isEmpty() || value.isEmpty()) {
                return null;
            }
            values.put(key, value);
        }
        return values;
    }

    /** 宽松回退：逐行扫描首个匹配的 {@code key: value}（用于非严格 YAML 形态）。 */
    private static String lenientValue(List<String> block, String key) {
        String prefix = key + ":";
        for (String line : block) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith(prefix)) {
                String value = unquote(trimmed.substring(prefix.length()).trim());
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 剥离 YAML 标量的成对引号。 */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /** frontmatter 解析结果（内部值对象）。 */
    private record Frontmatter(String name, String description) {
    }
}