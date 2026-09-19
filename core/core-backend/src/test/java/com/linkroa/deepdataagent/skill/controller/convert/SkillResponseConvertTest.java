package com.linkroa.deepdataagent.skill.controller.convert;

import com.linkroa.deepdataagent.skill.controller.response.SkillResponse;
import com.linkroa.deepdataagent.skill.controller.response.SkillVersionResponse;
import com.linkroa.deepdataagent.skill.domain.model.SkillAsset;
import com.linkroa.deepdataagent.skill.domain.model.SkillContent;
import com.linkroa.deepdataagent.skill.domain.model.SkillVersion;
import com.linkroa.deepdataagent.skill.domain.model.enums.SkillSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillResponseConvert} 协议层响应转换单测（7.2 Skill 壳与不可变版本的对象形状）：
 * 同名字段映射、来源枚举降级为平台中性词汇，以及对外字段名统一 snake_case
 * （{@code display_title} / {@code latest_version} / {@code skill_id} / {@code created_at} /
 * {@code updated_at}）的序列化锁定。
 */
class SkillResponseConvertTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** 版本键夹具（epoch 微秒字符串）。 */
    private static final String EPOCH = "1759178010641128";

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-17T10:00:00+08:00");

    @Test
    void should_mapShellFieldsWithNeutralSource_when_toResponse_given_customAsset() {
        // given（自建技能壳：来源枚举降级为中性词 custom）
        SkillAsset asset = SkillAsset.restore(9L, "skill_a", "评审技能", SkillSource.CUSTOM, EPOCH,
                Map.of("k", "v"), 1L, NOW, NOW);

        // when
        SkillResponse response = SkillResponseConvert.INSTANCE.toResponse(asset);

        // then
        assertEquals("skill_a", response.id());
        assertEquals("skill", response.type());
        assertEquals("评审技能", response.displayTitle());
        assertEquals("custom", response.source());
        assertEquals(EPOCH, response.latestVersion());
        assertEquals(Map.of("k", "v"), response.metadata());
    }

    @Test
    void should_serializeSnakeCaseKeys_when_writeValueAsString_given_shellResponse() {
        // given
        SkillResponse response = SkillResponseConvert.INSTANCE.toResponse(
                SkillAsset.restore(9L, "skill_a", "评审技能", SkillSource.CUSTOM, EPOCH, Map.of(), 1L, NOW, NOW));

        // when
        String json = MAPPER.writeValueAsString(response);

        // then（规格要求 snake_case；MUST NOT 出现 camelCase 变体）
        assertTrue(json.contains("\"display_title\":\"评审技能\""));
        assertTrue(json.contains("\"latest_version\":\"" + EPOCH + "\""));
        assertTrue(json.contains("\"created_at\":"));
        assertTrue(json.contains("\"updated_at\":"));
        assertFalse(json.contains("displayTitle"));
        assertFalse(json.contains("latestVersion"));
        assertFalse(json.contains("createdAt"));
    }

    @Test
    void should_serializeSnakeCaseKeys_when_writeValueAsString_given_versionResponse() {
        // given（版本快照：id 为 skillver_ 前缀、skill_id 指向所属技能）
        SkillVersion version = SkillVersion.restore(SkillVersion.VERSION_ID_PREFIX + EPOCH, "skill_a", EPOCH,
                "code-review", "描述", "code-review", "b".repeat(64), 10L, Map.of(), NOW);

        // when
        SkillVersionResponse response = SkillResponseConvert.INSTANCE.toVersionResponse(version);
        String json = MAPPER.writeValueAsString(response);

        // then（规格要求 snake_case：skill_id / created_at；对象头 id/type 保持）
        assertEquals("skill_version", response.type());
        assertTrue(json.contains("\"skill_id\":\"skill_a\""));
        assertTrue(json.contains("\"created_at\":"));
        assertFalse(json.contains("skillId"));
    }

    @Test
    void should_preserveBinaryResourceBytes_when_toZipArchive_given_nonUtf8Resource() throws IOException {
        // given（含非 UTF-8 可解码字节的资源：归档条目须与原始字节逐字节一致）
        byte[] binary = {(byte) 0x89, 'P', 'N', 'G', 0x00, (byte) 0xFF, (byte) 0xFE};
        SkillContent content = new SkillContent("# 正文", Map.of("assets/logo.png", binary));

        // when
        byte[] archive = SkillResponseConvert.INSTANCE.toZipArchive(content);

        // then
        Map<String, byte[]> entries = unzip(archive);
        assertArrayEquals(binary, entries.get("assets/logo.png"));
        assertArrayEquals("# 正文".getBytes(StandardCharsets.UTF_8), entries.get("SKILL.md"));
    }

    /** 解压归档为「条目名 → 原始字节」。 */
    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}