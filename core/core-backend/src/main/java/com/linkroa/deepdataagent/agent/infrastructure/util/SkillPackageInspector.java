package com.linkroa.deepdataagent.agent.infrastructure.util;

import com.linkroa.deepdataagent.agent.domain.model.SkillResourceManifest;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 技能包结构检查器（上传侧）：校验技能包 ZIP 结构并提取结构化资源元数据。
 * <p>技能包知识结构约定为 {@code SKILL.md}（必需）+ {@code references/}（参考资料）+
 * {@code scripts/}（脚本）；本检查器在落库前校验必需 {@code SKILL.md} 存在，并收集
 * references / scripts 文件相对路径清单（值对象 {@link SkillResourceManifest}），
 * 与运行时物化器 {@code SkillPackageMaterializer} 保持一致的路径归一化规则。</p>
 */
@Component
public class SkillPackageInspector {

    private static final String SKILL_MD_SUFFIX = "/SKILL.md";

    /**
     * 检查技能包结构：缺少必需 {@code SKILL.md} 抛 {@link IllegalArgumentException}（400），
     * 否则返回 references / scripts 文件相对路径清单。
     */
    public SkillResourceManifest inspect(byte[] content) {
        List<String> references = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        boolean hasSkillMd = false;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                if (name.endsWith(SKILL_MD_SUFFIX) || name.equalsIgnoreCase("SKILL.md")) {
                    hasSkillMd = true;
                } else if (name.startsWith("references/")) {
                    references.add(name);
                } else if (name.startsWith("scripts/")) {
                    scripts.add(name);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("技能包解压失败，请上传合法的 ZIP 技能包", e);
        }
        if (!hasSkillMd) {
            throw new IllegalArgumentException("技能包缺少必需的 SKILL.md");
        }
        return SkillResourceManifest.create(references, scripts);
    }
}