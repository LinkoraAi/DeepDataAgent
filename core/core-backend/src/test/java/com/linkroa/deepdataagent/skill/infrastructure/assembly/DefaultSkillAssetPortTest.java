package com.linkroa.deepdataagent.skill.infrastructure.assembly;

import com.linkroa.deepdataagent.shared.storage.ObjectMetadata;
import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageErrorKind;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageException;
import com.linkroa.deepdataagent.shared.storage.ObjectKeys;
import com.linkroa.deepdataagent.skill.domain.model.SkillContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultSkillAssetPort} 技能资产对象存储存取单测。
 * <p>以内存版 {@link ObjectStorage} fake 验证：读写往返 / 同版本重写清前缀 /
 * 正文缺失返回 null / 资源路径逃逸在清前缀前拒绝（既有版本不被破坏）。</p>
 */
class DefaultSkillAssetPortTest {

    private InMemoryObjectStorage storage;
    private DefaultSkillAssetPort port;

    @BeforeEach
    void setUp() {
        storage = new InMemoryObjectStorage();
        port = new DefaultSkillAssetPort();
        ReflectionTestUtils.setField(port, "objectStorage", storage);
    }

    @Test
    void should_roundTripMarkdownAndNestedResources_when_read_given_write() {
        // given
        SkillContent content = new SkillContent("# 正文",
                Map.of("references/a.md", text("引用正文"), "scripts/run.py", text("print(1)")));

        // when
        port.write("skill_x", "1000", content);
        SkillContent read = port.read("skill_x", "1000");

        // then（正文与资源按相对路径还原）
        assertEquals("# 正文", read.markdown());
        assertEquals(2, read.resources().size());
        assertArrayEquals(text("引用正文"), read.resources().get("references/a.md"));
        assertArrayEquals(text("print(1)"), read.resources().get("scripts/run.py"));
    }

    @Test
    void should_roundTripBinaryResource_when_read_given_nonUtf8ResourceBytes() {
        // given（含非 UTF-8 可解码字节：存储与读取均不得做字符解码）
        byte[] binary = {(byte) 0x89, 'P', 'N', 'G', 0x00, (byte) 0xFF, (byte) 0xFE, (byte) 0x80};
        port.write("skill_x", "1500", new SkillContent("# 正文", Map.of("assets/logo.png", binary)));

        // when
        SkillContent read = port.read("skill_x", "1500");

        // then（逐字节还原）
        assertArrayEquals(binary, read.resources().get("assets/logo.png"));
    }

    @Test
    void should_dropStaleResources_when_read_given_secondWriteOfSameVersion() {
        // given（首轮写入含独有资源 scripts/old.py）
        port.write("skill_x", "2000", new SkillContent("# v2 旧内容",
                Map.of("references/a.md", text("共用"), "scripts/old.py", text("旧脚本"))));

        // when（同版本二次写入：仅含 references/a.md）
        port.write("skill_x", "2000", new SkillContent("# v2 新内容", Map.of("references/a.md", text("共用"))));

        // then（版本前缀被清空重写：首轮独有对象与旧正文均不再残留）
        SkillContent read = port.read("skill_x", "2000");
        assertEquals("# v2 新内容", read.markdown());
        assertEquals(1, read.resources().size());
        assertArrayEquals(text("共用"), read.resources().get("references/a.md"));
        assertTrue(storage.backing().keySet().stream()
                .noneMatch(k -> k.equals("skills/skill_x/2000/scripts/old.py")));
    }

    @Test
    void should_returnNull_when_read_given_missingVersionObject() {
        // given // when // then（正文对象缺失视为内容不存在，由装配链显式失败）
        assertNull(port.read("skill_ghost", "1000"));
    }

    @Test
    void should_keepExistingContent_when_write_given_escapingResourceKey() {
        // given（先写一份合法内容）
        port.write("skill_x", "3000", new SkillContent("# v3 正文", Map.of("references/a.md", text("引用"))));

        // when // then（../ 逃逸资源键在清空旧前缀前即被拒，既有内容完好）
        SkillContent bad = new SkillContent("# v3 正文", Map.of("../../etc/passwd", text("逃逸")));
        assertThrows(IllegalArgumentException.class, () -> port.write("skill_x", "3000", bad));
        assertEquals("# v3 正文", port.read("skill_x", "3000").markdown());
        assertTrue(storage.backing().keySet().stream()
                .noneMatch(k -> k.contains("etc") || k.contains("passwd")));
    }

    @Test
    void should_rejectAbsolutePathAndBackslash_when_write_given_illegalResourceKey() {
        // given
        SkillContent base = new SkillContent("# v", Map.of("references/a.md", text("x")));
        port.write("skill_x", "4000", base);

        // when // then（绝对路径 / 反斜杠 / 保留文件名均 400，且不破坏既有前缀）
        assertThrows(IllegalArgumentException.class, () -> port.write("skill_x", "4000",
                new SkillContent("# v", Map.of("/etc/passwd", text("x")))));
        assertThrows(IllegalArgumentException.class, () -> port.write("skill_x", "4000",
                new SkillContent("# v", Map.of("a\\b.md", text("x")))));
        assertThrows(IllegalArgumentException.class, () -> port.write("skill_x", "4000",
                new SkillContent("# v", Map.of("SKILL.md", text("x")))));
        // 保留文件名写入被拒，既有正文对象内容未被覆盖
        assertEquals("# v", port.read("skill_x", "4000").markdown());
        assertEquals("# v", new String(storage.backing().get("skills/skill_x/4000/SKILL.md"),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    /** UTF-8 文本资源字节夹具。 */
    private static byte[] text(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 内存版对象存储 fake（实现端口全部原语，供 skill / 对账相关单测复用）。
     */
    static class InMemoryObjectStorage implements ObjectStorage {

        private final Map<String, byte[]> store = new ConcurrentHashMap<>();

        @Override
        public void put(String key, InputStream content, long size, String contentType) {
            ObjectKeys.validateKey(key);
            try {
                store.put(key, content.readAllBytes());
            } catch (java.io.IOException e) {
                throw new ObjectStorageException(ObjectStorageErrorKind.IO_ERROR, "读取失败", e);
            }
        }

        @Override
        public InputStream get(String key) {
            byte[] bytes = store.get(key);
            if (bytes == null) {
                throw new ObjectStorageException(ObjectStorageErrorKind.NOT_FOUND, "不存在: " + key);
            }
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public List<ObjectMetadata> list(String prefix) {
            ObjectKeys.validatePrefix(prefix);
            List<ObjectMetadata> result = new ArrayList<>();
            new ArrayList<>(store.keySet()).stream().sorted()
                    .filter(key -> key.startsWith(prefix))
                    .forEach(key -> result.add(new ObjectMetadata(key, store.get(key).length,
                            null, Instant.EPOCH, null)));
            return result;
        }

        @Override
        public void delete(String key) {
            store.remove(key);
        }

        @Override
        public void deletePrefix(String prefix) {
            ObjectKeys.validatePrefix(prefix);
            store.keySet().removeIf(key -> key.startsWith(prefix));
        }

        /** 暴露底层映射供断言（只读快照）。 */
        Map<String, byte[]> backing() {
            return new LinkedHashMap<>(store);
        }
    }
}
