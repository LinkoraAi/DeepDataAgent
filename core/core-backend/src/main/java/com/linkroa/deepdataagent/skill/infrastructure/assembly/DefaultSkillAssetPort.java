package com.linkroa.deepdataagent.skill.infrastructure.assembly;

import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageErrorKind;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageException;
import com.linkroa.deepdataagent.skill.application.port.SkillAssetPort;
import com.linkroa.deepdataagent.skill.domain.model.SkillContent;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 技能资产内容对象存储端口实现（{@link SkillAssetPort}），是 skill BC 内唯一接触
 * shared 对象存储技术类型的装配点。
 * <p>版本内容落对象存储前缀 {@code skills/<skill_id>/<epoch>/}：正文为前缀下
 * {@code SKILL.md}，其余资源按相对路径以原始字节归入映射。读取时以前缀列举还原 {@link SkillContent}；
 * 正文对象缺失视为内容不存在（返回 {@code null}，由装配链显式失败）。</p>
 * <p><b>写入顺序约束</b>：调用方（{@code SkillApplicationService}）在同一事务内先抢版本键
 * （台账唯一索引裁决）后调用本方法，故本方法清空 / 重写的前缀必然属于已抢到号的版本，
 * 不会触碰他人版本。写入前先解析并校验全部资源相对路径（非法即在清前缀前失败），
 * 再 {@code deletePrefix} 全量重写——内容版本不可变，同版本重写只发生在补偿 / 重放路径。</p>
 */
@Component
public class DefaultSkillAssetPort implements SkillAssetPort {

    private static final Logger log = LoggerFactory.getLogger(DefaultSkillAssetPort.class);

    /** 技能对象 key 前缀根。 */
    private static final String NAMESPACE_PREFIX = "skills/";

    /** 正文文件名。 */
    private static final String SKILL_FILE = SkillContent.RESERVED_SKILL_FILE;

    /** 正文对象内容类型。 */
    private static final String MARKDOWN_CONTENT_TYPE = "text/markdown; charset=UTF-8";

    /** 资源对象内容类型（资源按原始字节存取，不假定字符编码）。 */
    private static final String RESOURCE_CONTENT_TYPE = "application/octet-stream";

    @Resource
    private ObjectStorage objectStorage;

    @Override
    public void write(String skillId, String version, SkillContent content) {
        String prefix = versionPrefix(skillId, version);
        // 资源目标 key 先解析并校验（非法路径在清空旧内容前即失败，避免误毁既有版本前缀）
        Map<String, byte[]> targets = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : content.resources().entrySet()) {
            String relPath = entry.getKey();
            validateResourceRelPath(relPath);
            targets.put(prefix + relPath, entry.getValue());
        }
        // 同版本重写（补偿 / 重放路径）：先清空前缀，再全量写入
        objectStorage.deletePrefix(prefix);
        byte[] markdownBytes = content.markdown().getBytes(StandardCharsets.UTF_8);
        putObject(prefix + SKILL_FILE, markdownBytes, MARKDOWN_CONTENT_TYPE);
        for (Map.Entry<String, byte[]> target : targets.entrySet()) {
            putObject(target.getKey(), target.getValue(), RESOURCE_CONTENT_TYPE);
        }
    }

    @Override
    public SkillContent read(String skillId, String version) {
        String prefix = versionPrefix(skillId, version);
        String markdownKey = prefix + SKILL_FILE;
        String markdown = readStringObject(markdownKey);
        if (markdown == null) {
            log.warn("技能资产内容缺失: {}@{}", skillId, version);
            return null;
        }
        Map<String, byte[]> resources = new LinkedHashMap<>();
        // 以前缀列举还原资源（正文对象由 key 精确排除）；资源读缺失视为损坏，显式失败
        objectStorage.list(prefix).stream()
                .filter(metadata -> !metadata.key().equals(markdownKey))
                .filter(metadata -> metadata.key().startsWith(prefix))
                .forEach(metadata -> {
                    String relPath = metadata.key().substring(prefix.length());
                    resources.put(relPath, readObjectBytes(metadata.key()));
                });
        return new SkillContent(markdown, resources);
    }

    /**
     * 写入单个文本对象（字节流）。
     *
     * @param key         对象 key
     * @param bytes       UTF-8 字节
     * @param contentType 内容类型
     */
    private void putObject(String key, byte[] bytes, String contentType) {
        objectStorage.put(key, new ByteArrayInputStream(bytes), bytes.length, contentType);
    }

    /**
     * 读取单个对象的原始字节（资源按字节存取，二进制资源可逐字节还原）。
     *
     * @param key 对象 key
     * @return 对象字节
     */
    private byte[] readObjectBytes(String key) {
        try (InputStream in = objectStorage.get(key)) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException("技能资产对象读取失败: " + key, ex);
        }
    }

    /**
     * 读取正文对象为 UTF-8 字符串（正文恒为文本）。
     *
     * @param key 对象 key
     * @return UTF-8 文本；对象不存在（NOT_FOUND）时返回 null
     */
    private String readStringObject(String key) {
        try (InputStream in = objectStorage.get(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (ObjectStorageException ex) {
            if (ex.kind() == ObjectStorageErrorKind.NOT_FOUND) {
                return null;
            }
            throw ex;
        } catch (IOException ex) {
            throw new UncheckedIOException("技能资产对象读取失败: " + key, ex);
        }
    }

    /**
     * 组装版本前缀（结尾 /）。
     *
     * @param skillId 技能业务 ID
     * @param version 版本键（创建时刻 epoch 微秒字符串）
     * @return 对象 key 前缀 {@code skills/<skill_id>/<epoch>/}
     */
    private static String versionPrefix(String skillId, String version) {
        return NAMESPACE_PREFIX + skillId + "/" + version + "/";
    }

    /**
     * 业务侧资源相对路径不变量校验（第二道技术防线 ObjectKeys 在写入时再次校验完整 key）。
     * <p>拒绝：空 / 绝对路径（{@code /} 或 {@code \\} 开头）/ 含反斜杠（避免 Windows 分隔符歧义）/
     * {@code .}、{@code ..} 穿越段 / 空段 / 占用保留文件名 {@code SKILL.md}。业务侧统一以 {@code /}
     * 作为路径分隔符。</p>
     *
     * @param relPath 资源相对路径
     */
    private static void validateResourceRelPath(String relPath) {
        if (relPath == null || relPath.isBlank()
                || relPath.startsWith("/") || relPath.startsWith("\\")) {
            throw new IllegalArgumentException("非法技能资源路径: " + relPath);
        }
        if (relPath.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("技能资源路径不得使用反斜杠分隔符: " + relPath);
        }
        for (String segment : relPath.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("非法技能资源路径（空段或路径穿越）: " + relPath);
            }
            if (SKILL_FILE.equalsIgnoreCase(segment)) {
                throw new IllegalArgumentException("技能资源路径不得使用保留文件名 " + SKILL_FILE);
            }
        }
    }
}
