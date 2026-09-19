package com.linkroa.deepdataagent.skill.controller.convert;

import com.linkroa.deepdataagent.skill.controller.response.SkillDetailResponse;
import com.linkroa.deepdataagent.skill.controller.response.SkillResponse;
import com.linkroa.deepdataagent.skill.controller.response.SkillVersionResponse;
import com.linkroa.deepdataagent.skill.domain.model.SkillAsset;
import com.linkroa.deepdataagent.skill.domain.model.SkillContent;
import com.linkroa.deepdataagent.skill.domain.model.SkillVersion;
import com.linkroa.deepdataagent.skill.domain.model.enums.SkillSource;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 技能领域模型 → 协议层响应转换器（MapStruct 静态单例）。
 * <p>对象类型词汇（{@code skill} / {@code skill_version}）与技能来源枚举（catalog/custom）
 * 在协议层降级为小写字符串，不外泄领域枚举；版本内容 zip 归档装配也在此承载。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SkillResponseConvert {

    SkillResponseConvert INSTANCE = Mappers.getMapper(SkillResponseConvert.class);

    /** 技能壳对象类型词汇。 */
    String SKILL_TYPE = "skill";

    /** 技能版本对象类型词汇。 */
    String SKILL_VERSION_TYPE = "skill_version";

    /** 归档内主文件条目名。 */
    String SKILL_ENTRY = SkillContent.RESERVED_SKILL_FILE;

    @Mapping(target = "id", source = "skillId")
    @Mapping(target = "type", constant = SKILL_TYPE)
    @Mapping(target = "source", source = "source", qualifiedByName = "toSourceValue")
    SkillResponse toResponse(SkillAsset asset);

    @Mapping(target = "type", constant = SKILL_VERSION_TYPE)
    SkillVersionResponse toVersionResponse(SkillVersion version);

    /**
     * 组装技能详情（壳对象 + 全部版本）。
     *
     * @param asset    技能壳
     * @param versions 内容版本列表
     * @return 详情响应
     */
    default SkillDetailResponse toDetailResponse(SkillAsset asset, List<SkillVersion> versions) {
        List<SkillVersionResponse> versionResponses = versions.stream()
                .map(this::toVersionResponse)
                .toList();
        return new SkillDetailResponse(toResponse(asset), versionResponses);
    }

    /** 来源枚举 → 小写字符串词汇。 */
    @Named("toSourceValue")
    default String toSourceValue(SkillSource source) {
        return source == null ? null : source.value();
    }

    /**
     * 版本内容装配为 zip 存档字节：{@code SKILL.md} 主文件 + 资源按相对路径入档（UTF-8 文件名）。
     * <p>资源以原始字节入档，二进制资源可逐字节还原上传时的内容。</p>
     *
     * @param content 技能内容
     * @return zip 存档字节
     */
    default byte[] toZipArchive(SkillContent content) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            writeEntry(zip, SKILL_ENTRY, content.markdown().getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> entry : content.resources().entrySet()) {
                writeEntry(zip, entry.getKey(), entry.getValue());
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("技能版本存档装配失败", e);
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}