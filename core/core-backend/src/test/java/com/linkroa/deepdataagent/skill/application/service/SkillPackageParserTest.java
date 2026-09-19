package com.linkroa.deepdataagent.skill.application.service;

import com.linkroa.deepdataagent.shared.exception.RequestTooLargeException;
import com.linkroa.deepdataagent.skill.domain.model.SkillContent;
import com.linkroa.deepdataagent.skill.domain.model.SkillPackage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SkillPackageParser} 技能包解析表驱动单测（7.8）。
 * <p>覆盖：单 zip 与裸文件树两种上传形态归一、zip 压缩包 50MB 上限（413）、
 * 结构违规（多顶级目录 / 缺 SKILL.md / 缺顶级目录）、frontmatter 严格 YAML 与宽松回退 /
 * 目录名一致 / 缺字段 / 缺块、zip-slip（绝对路径 / 路径穿越 / 反斜杠）、重复路径、
 * 空上传与展示名推导。</p>
 */
class SkillPackageParserTest {

    /** 合法 SKILL.md 原文（frontmatter name 与顶级目录一致）。 */
    private static final String SKILL_MD = """
            ---
            name: code-review
            description: 代码评审技能
            ---

            # 评审规范
            """;

    private final SkillPackageParser parser = new SkillPackageParser();

    // ---------- 正常形态 ----------

    @Test
    void should_parseZipPackage_when_parse_given_zipWithSkillMdAndResource() {
        // given（单个 zip：顶级目录 code-review 内含 SKILL.md 与资源）
        MultipartFile zip = new MockMultipartFile("files", "code-review.zip", "application/zip",
                zip(Map.of("code-review/SKILL.md", SKILL_MD, "code-review/references/a.md", "引用正文")));

        // when
        SkillPackage parsed = parser.parse(List.of(zip));

        // then（frontmatter 派生元数据 + 正文原文 + 资源相对路径）
        assertEquals("code-review", parsed.name());
        assertEquals("代码评审技能", parsed.description());
        assertEquals(SKILL_MD, parsed.content().markdown());
        assertEquals(1, parsed.content().resources().size());
        assertArrayEquals("引用正文".getBytes(StandardCharsets.UTF_8),
                parsed.content().resources().get("references/a.md"));
        assertEquals(Map.of("references/a.md", 12L), parsed.resourceSizes());
    }

    @Test
    void should_preserveBinaryResourceBytes_when_parse_given_zipWithBinaryEntry() {
        // given（含非 UTF-8 可解码字节的资源：解析必须原样保留字节）
        byte[] binary = {(byte) 0x89, 'P', 'N', 'G', 0x00, (byte) 0xFF, (byte) 0xFE};
        MultipartFile zip = new MockMultipartFile("files", "code-review.zip", "application/zip",
                zipRaw(Map.of("code-review/SKILL.md", SKILL_MD.getBytes(StandardCharsets.UTF_8),
                        "code-review/assets/logo.png", binary)));

        // when
        SkillPackage parsed = parser.parse(List.of(zip));

        // then
        assertArrayEquals(binary, parsed.content().resources().get("assets/logo.png"));
        assertEquals(Map.of("assets/logo.png", 7L), parsed.resourceSizes());
    }

    @Test
    void should_parseBareTree_when_parse_given_multipartFiles() {
        // given（裸文件树：filename 携带相对路径）
        List<MultipartFile> files = List.of(
                new MockMultipartFile("files", "code-review/SKILL.md", "text/markdown",
                        SKILL_MD.getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("files", "code-review/references/a.md", "text/markdown",
                        "引用正文".getBytes(StandardCharsets.UTF_8)));

        // when
        SkillPackage parsed = parser.parse(files);

        // then（与 zip 形态收敛为同一逻辑文件树）
        assertEquals("code-review", parsed.name());
        assertEquals(SKILL_MD, parsed.content().markdown());
        assertEquals(1, parsed.content().resources().size());
        assertArrayEquals("引用正文".getBytes(StandardCharsets.UTF_8),
                parsed.content().resources().get("references/a.md"));
    }

    @Test
    void should_fallbackToLenientFrontmatter_when_parse_given_nonStrictYaml() {
        // given（frontmatter 含嵌套列表，严格 YAML 判定失败 → 宽松逐行回退）
        String markdown = """
                ---
                name: code-review
                tags:
                  - review
                description: 代码评审技能
                ---

                # 正文
                """;
        MultipartFile zip = new MockMultipartFile("files", "code-review.zip", "application/zip",
                zip(Map.of("code-review/SKILL.md", markdown)));

        // when
        SkillPackage parsed = parser.parse(List.of(zip));

        // then（仅提取顶级 name / description）
        assertEquals("code-review", parsed.name());
        assertEquals("代码评审技能", parsed.description());
    }

    // ---------- 体积上限 ----------

    @Test
    void should_throwRequestTooLarge_when_parse_given_zipOverPackageLimit() {
        // given（zip 压缩包声明体积超过 50MB → 413）
        MultipartFile oversized = new StubMultipartFile("big.zip",
                new byte[]{'P', 'K'}, SkillPackageParser.MAX_PACKAGE_BYTES + 1);

        // when // then
        assertThrows(RequestTooLargeException.class, () -> parser.parse(List.of(oversized)));
    }

    @Test
    void should_throwIllegalArgument_when_parse_given_uncompressedTotalOverLimit() {
        // given（压缩包极小，但单条目解压后 50MB+1，总量超限 → 400；
        //        有界读取保证不把该条目整块读进内存）
        MultipartFile zip = new MockMultipartFile("files", "code-review.zip", "application/zip",
                zipWithZeroFilledEntry("code-review/SKILL.md", SKILL_MD,
                        "code-review/big.bin", SkillPackageParser.MAX_UNCOMPRESSED_BYTES + 1));

        // when
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(List.of(zip)));

        // then
        assertEquals("技能包解压后总量不能超过 50MB", ex.getMessage());
    }

    // ---------- 结构违规 ----------

    @Test
    void should_throwIllegalArgument_when_parse_given_multipleTopDirectories() {
        // given（两个顶级目录）
        List<MultipartFile> files = List.of(
                bare("code-review/SKILL.md", SKILL_MD),
                bare("other-skill/references/a.md", "x"));

        // when // then
        assertThrows(IllegalArgumentException.class, () -> parser.parse(files));
    }

    @Test
    void should_throwIllegalArgument_when_parse_given_missingSkillMd() {
        // given（顶级目录内无 SKILL.md）
        MultipartFile zip = new MockMultipartFile("files", "code-review.zip", "application/zip",
                zip(Map.of("code-review/references/a.md", "引用")));

        // when // then
        assertThrows(IllegalArgumentException.class, () -> parser.parse(List.of(zip)));
    }

    @Test
    void should_throwIllegalArgument_when_parse_given_missingTopDirectory() {
        // given（裸文件 filename 不含顶级目录）
        MultipartFile flat = new MockMultipartFile("files", "SKILL.md", "text/markdown",
                SKILL_MD.getBytes(StandardCharsets.UTF_8));

        // when // then
        assertThrows(IllegalArgumentException.class, () -> parser.parse(List.of(flat)));
    }

    @Test
    void should_throwIllegalArgument_when_parse_given_duplicateBarePath() {
        // given（同一相对路径出现两次）
        List<MultipartFile> files = List.of(
                bare("code-review/SKILL.md", SKILL_MD),
                bare("code-review/SKILL.md", SKILL_MD));

        // when // then
        assertThrows(IllegalArgumentException.class, () -> parser.parse(files));
    }

    // ---------- zip-slip 防护 ----------

    @Test
    void should_throwIllegalArgument_when_parse_given_absolutePath() {
        // given（绝对路径条目）
        MultipartFile zip = new MockMultipartFile("files", "code-review.zip", "application/zip",
                zip(Map.of("/code-review/SKILL.md", SKILL_MD)));

        // when // then
        assertThrows(IllegalArgumentException.class, () -> parser.parse(List.of(zip)));
    }

    @Test
    void should_throwIllegalArgument_when_parse_given_pathTraversal() {
        // given（路径穿越条目）
        MultipartFile zip = new MockMultipartFile("files", "code-review.zip", "application/zip",
                zip(Map.of("code-review/../etc/SKILL.md", SKILL_MD)));

        // when // then
        assertThrows(IllegalArgumentException.class, () -> parser.parse(List.of(zip)));
    }

    @Test
    void should_throwIllegalArgument_when_parse_given_backslashPath() {
        // given（反斜杠分隔符条目）
        MultipartFile zip = new MockMultipartFile("files", "code-review.zip", "application/zip",
                zip(Map.of("code-review\\SKILL.md", SKILL_MD)));

        // when // then
        assertThrows(IllegalArgumentException.class, () -> parser.parse(List.of(zip)));
    }

    // ---------- frontmatter 非法形态 ----------

    @Test
    void should_throwIllegalArgument_when_parse_given_noFrontmatterBlock() {
        // given（正文未以 frontmatter 开头）
        MultipartFile zip = zipOf("code-review", "# 只有正文");

        // when // then
        assertThrows(IllegalArgumentException.class, () -> parser.parse(List.of(zip)));
    }

    @Test
    void should_throwIllegalArgument_when_parse_given_unterminatedFrontmatter() {
        // given（frontmatter 缺结束定界符）
        MultipartFile zip = zipOf("code-review", "---\nname: code-review\ndescription: 描述\n");

        // when // then
        assertThrows(IllegalArgumentException.class, () -> parser.parse(List.of(zip)));
    }

    @Test
    void should_throwIllegalArgument_when_parse_given_missingDescription() {
        // given（frontmatter 缺 description）
        MultipartFile zip = zipOf("code-review", "---\nname: code-review\n---\n\n# 正文");

        // when // then
        assertThrows(IllegalArgumentException.class, () -> parser.parse(List.of(zip)));
    }

    @Test
    void should_throwIllegalArgument_when_parse_given_directoryNameMismatch() {
        // given（顶级目录名与 frontmatter name 不一致）
        MultipartFile zip = zipOf("other-dir", SKILL_MD);

        // when // then
        assertThrows(IllegalArgumentException.class, () -> parser.parse(List.of(zip)));
    }

    // ---------- 空上传 ----------

    @Test
    void should_throwIllegalArgument_when_parse_given_emptyFiles() {
        // given // when // then（无 files part 视为空包）
        assertThrows(IllegalArgumentException.class, () -> parser.parse(List.of()));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(null));
    }

    // ---------- 展示名推导 ----------

    @Test
    void should_suggestZipBasename_when_suggestedDisplayTitle_given_zipFile() {
        // given（单 zip：取文件名去 .zip 后缀与目录段）
        List<MultipartFile> files = List.of(new MockMultipartFile("files", "upload/My Skill.zip",
                "application/zip", new byte[]{'P', 'K'}));

        // when // then
        assertEquals("My Skill", parser.suggestedDisplayTitle(files));
    }

    @Test
    void should_returnNull_when_suggestedDisplayTitle_given_bareTree() {
        // given（多 part 裸文件树无单一 zip 文件名可用）
        List<MultipartFile> files = List.of(
                bare("code-review/SKILL.md", SKILL_MD),
                bare("code-review/references/a.md", "引用"));

        // when // then
        assertNull(parser.suggestedDisplayTitle(files));
    }

    // ---------- 夹具 ----------

    /** 裸树单文件夹具（filename 携带相对路径）。 */
    private static MultipartFile bare(String relativePath, String text) {
        return new MockMultipartFile("files", relativePath, "text/plain",
                text.getBytes(StandardCharsets.UTF_8));
    }

    /** 单 zip 夹具（单个顶级目录 + SKILL.md 正文）。 */
    private static MultipartFile zipOf(String topDirectory, String markdown) {
        return new MockMultipartFile("files", topDirectory + ".zip", "application/zip",
                zip(Map.of(topDirectory + "/SKILL.md", markdown)));
    }

    /** 打包「条目名 → 文本」为 zip 字节。 */
    private static byte[] zip(Map<String, String> entries) {
        Map<String, byte[]> raw = new java.util.LinkedHashMap<>();
        entries.forEach((name, text) -> raw.put(name, text.getBytes(StandardCharsets.UTF_8)));
        return zipRaw(raw);
    }

    /** 打包「条目名 → 原始字节」为 zip 字节（二进制资源夹具）。 */
    private static byte[] zipRaw(Map<String, byte[]> entries) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("zip 夹具装配失败", e);
        }
    }

    /** 打包「SKILL.md + 指定解压体积的全零条目」为 zip 字节（压缩后极小，用于解压总量上限用例）。 */
    private static byte[] zipWithZeroFilledEntry(String markdownPath, String markdown,
                                                  String fillerPath, long fillerBytes) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(markdownPath));
            zip.write(markdown.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(fillerPath));
            byte[] chunk = new byte[1024 * 1024];
            long written = 0L;
            while (written < fillerBytes) {
                int length = (int) Math.min(chunk.length, fillerBytes - written);
                zip.write(chunk, 0, length);
                written += length;
            }
            zip.closeEntry();
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("zip 夹具装配失败", e);
        }
    }

    /** multipart part 替身（可声明与内容解耦的体积，用于体积上限用例）。 */
    private static final class StubMultipartFile implements MultipartFile {

        private final String originalFilename;
        private final byte[] content;
        private final long size;

        private StubMultipartFile(String originalFilename, byte[] content, long size) {
            this.originalFilename = originalFilename;
            this.content = content;
            this.size = size;
        }

        @Override
        public String getName() {
            return "files";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return "application/zip";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return size;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) {
            throw new UnsupportedOperationException("测试替身不支持落盘");
        }
    }
}