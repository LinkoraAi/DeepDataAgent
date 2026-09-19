package com.linkroa.deepdataagent.skill.controller.rest;

import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import com.linkroa.deepdataagent.skill.application.command.CreateSkillCommand;
import com.linkroa.deepdataagent.skill.application.command.CreateSkillVersionCommand;
import com.linkroa.deepdataagent.skill.application.query.ListSkillsQuery;
import com.linkroa.deepdataagent.skill.application.service.SkillApplicationService;
import com.linkroa.deepdataagent.skill.controller.response.SkillDetailResponse;
import com.linkroa.deepdataagent.skill.controller.response.SkillResponse;
import com.linkroa.deepdataagent.skill.controller.response.SkillVersionResponse;
import com.linkroa.deepdataagent.skill.domain.model.SkillAsset;
import com.linkroa.deepdataagent.skill.domain.model.SkillContent;
import com.linkroa.deepdataagent.skill.domain.model.SkillVersion;
import com.linkroa.deepdataagent.skill.domain.model.enums.SkillSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SkillController} 技能管理面协议装配单测（7.6）。
 * <p>锁定游标参数解析透传、multipart 创建 / 发版命令装配、版本资产端点委派与 zip 存档下载装配
 * （转换器走真实静态单例，仅应用服务替身）。</p>
 */
@ExtendWith(MockitoExtension.class)
class SkillControllerTest {

    /** 合法 64 位 hex 校验值夹具。 */
    private static final String SHA = "b".repeat(64);

    /** 版本键夹具（epoch 微秒字符串）。 */
    private static final String V3 = "1759178010641128";
    private static final String V1 = "1759178010641126";

    @Mock private SkillApplicationService applicationService;

    @InjectMocks private SkillController controller;

    @BeforeEach
    void setUp() {
        AuthContext.setUserId(1L);
    }

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void should_parseCursorAndFilters_when_list_given_queryParameters() {
        // given（source / keyword / limit / after_id 统一装配为查询对象）
        when(applicationService.listSkills(any(ListSkillsQuery.class)))
                .thenReturn(CursorPage.of(List.of(asset("skill_a")), false, SkillAsset::skillId));

        // when
        ApiResponse<CursorPage<SkillResponse>> response =
                controller.list("custom", "评审", "5", "skill_cursor", null);

        // then（信封项为 skill_ 游标 ID + 平台中性来源词汇；过滤词汇透传应用层）
        assertTrue(response.success());
        assertEquals("skill_a", response.data().data().get(0).id());
        assertEquals("skill", response.data().data().get(0).type());
        assertEquals("custom", response.data().data().get(0).source());
        verify(applicationService).listSkills(eq(new ListSkillsQuery(1L, SkillSource.CUSTOM, "评审",
                new CursorPageParams(5, "skill_cursor", null))));
    }

    @Test
    void should_returnVersionEnvelope_when_listVersions_given_versionCursor() {
        // given（游标版本列表：first/last 为版本键）
        when(applicationService.listVersions(eq("skill_x"), eq(new CursorPageParams(2, V3, null))))
                .thenReturn(CursorPage.of(List.of(version(V3), version(V1)), false, SkillVersion::version));

        // when
        ApiResponse<CursorPage<SkillVersionResponse>> response =
                controller.listVersions("skill_x", "2", V3, null);

        // then
        assertEquals(V3, response.data().firstId());
        assertEquals(V1, response.data().lastId());
        assertEquals(V3, response.data().data().get(0).version());
        assertEquals("skill_version", response.data().data().get(0).type());
    }

    @Test
    void should_delegateCreate_when_create_given_multipartPackage() {
        // given（multipart 创建：files 与 display_title 装配为命令并回填壳响应）
        List<MultipartFile> files = zipFiles();
        when(applicationService.createSkill(any(CreateSkillCommand.class))).thenReturn(asset("skill_a"));

        // when
        ApiResponse<SkillResponse> response = controller.create(files, "显式展示名");

        // then
        assertEquals("skill_a", response.data().id());
        assertEquals(V3, response.data().latestVersion());
        ArgumentCaptor<CreateSkillCommand> captor = ArgumentCaptor.forClass(CreateSkillCommand.class);
        verify(applicationService).createSkill(captor.capture());
        assertEquals(files, captor.getValue().files());
        assertEquals("显式展示名", captor.getValue().displayTitle());
    }

    @Test
    void should_delegateCreateVersion_when_createVersion_given_multipartPackage() {
        // given（发版：路径技能 ID + multipart files 装配为命令并回填版本响应）
        List<MultipartFile> files = zipFiles();
        when(applicationService.createVersion(any(CreateSkillVersionCommand.class))).thenReturn(version(V3));

        // when
        ApiResponse<SkillVersionResponse> response = controller.createVersion("skill_x", files);

        // then
        assertEquals(V3, response.data().version());
        ArgumentCaptor<CreateSkillVersionCommand> captor = ArgumentCaptor.forClass(CreateSkillVersionCommand.class);
        verify(applicationService).createVersion(captor.capture());
        assertEquals("skill_x", captor.getValue().skillId());
        assertEquals(files, captor.getValue().files());
    }

    @Test
    void should_delegateDetail_when_detail_given_id() {
        // given（详情 = 壳对象 + 全部版本信息，不含内容全文）
        when(applicationService.getSkill("skill_a")).thenReturn(asset("skill_a"));
        when(applicationService.listAllVersions("skill_a")).thenReturn(List.of(version(V3)));

        // when
        ApiResponse<SkillDetailResponse> response = controller.detail("skill_a");

        // then
        assertEquals("skill_a", response.data().skill().id());
        assertEquals(1, response.data().versions().size());
        assertEquals(V3, response.data().versions().get(0).version());
    }

    @Test
    void should_delegateGetVersion_when_getVersion_given_versionPath() {
        // given
        when(applicationService.getVersion("skill_x", V3)).thenReturn(version(V3));

        // when
        ApiResponse<SkillVersionResponse> response = controller.getVersion("skill_x", V3);

        // then（版本元数据不含内容全文：仅台账字段）
        assertEquals(V3, response.data().version());
        assertTrue(response.data().id().startsWith("skillver_"));
        assertEquals("code-review", response.data().name());
        assertEquals("code-review", response.data().directory());
    }

    @Test
    void should_packZipArchive_when_downloadVersionContent_given_contentWithResources() throws IOException {
        // given（下载端点：SKILL.md + 资源打包 zip 八位字节流）
        when(applicationService.getContent("skill_x", V3))
                .thenReturn(new SkillContent("# 正文", Map.of("references/a.md",
                        "资源正文".getBytes(StandardCharsets.UTF_8))));

        // when
        ResponseEntity<byte[]> response = controller.downloadVersionContent("skill_x", V3);

        // then（attachment 文件名 + zip 条目完整可解）
        assertEquals(200, response.getStatusCode().value());
        String disposition = String.valueOf(response.getHeaders().getContentDisposition());
        assertTrue(disposition.contains("attachment"));
        Map<String, String> entries = unzip(response.getBody());
        assertEquals("# 正文", entries.get("SKILL.md"));
        assertEquals("资源正文", entries.get("references/a.md"));
    }

    @Test
    void should_preserveBinaryResourceBytes_when_downloadVersionContent_given_binaryResource() throws IOException {
        // given（含非 UTF-8 可解码字节的资源：下载 zip 条目字节须与原始字节一致）
        byte[] binary = {(byte) 0x89, 'P', 'N', 'G', 0x00, (byte) 0xFF, (byte) 0xFE};
        when(applicationService.getContent("skill_x", V3))
                .thenReturn(new SkillContent("# 正文", Map.of("assets/logo.png", binary)));

        // when
        ResponseEntity<byte[]> response = controller.downloadVersionContent("skill_x", V3);

        // then
        assertArrayEquals(binary, unzipRaw(response.getBody()).get("assets/logo.png"));
    }

    @Test
    void should_delegateDeleteVersion_when_deleteVersion_given_versionPath() {
        // given（409 门禁在应用服务，控制器仅委派）
        // when
        ApiResponse<Void> response = controller.deleteVersion("skill_x", V3);

        // then
        assertTrue(response.success());
        verify(applicationService).deleteVersion("skill_x", V3);
    }

    @Test
    void should_returnNoContent_when_delete_given_id() {
        // given // when（技能删除：204 空体）
        ResponseEntity<Void> response = controller.delete("skill_x");

        // then
        assertEquals(204, response.getStatusCode().value());
        verify(applicationService).deleteSkill("skill_x");
    }

    /** 解包 zip 字节为「条目名 → 文本内容」。 */
    private static Map<String, String> unzip(byte[] archive) throws IOException {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zip.transferTo(out);
                entries.put(entry.getName(), out.toString(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /** 解压 zip 归档为「条目名 → 原始字节」（二进制资源断言用）。 */
    private static Map<String, byte[]> unzipRaw(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    /** 单 zip multipart 上传夹具。 */
    private static List<MultipartFile> zipFiles() {
        return List.of(new MockMultipartFile("files", "code-review.zip", "application/zip", new byte[]{'P', 'K'}));
    }

    private static SkillAsset asset(String skillId) {
        return new SkillAsset(9L, skillId, "技能", SkillSource.CUSTOM, V3, Map.of(), 1L,
                OffsetDateTime.parse("2026-08-20T10:00:00+08:00"), null);
    }

    private static SkillVersion version(String key) {
        return SkillVersion.restore(SkillVersion.VERSION_ID_PREFIX + key, "skill_x", key, "code-review", "描述",
                "code-review", SHA, 10L, Map.of(), OffsetDateTime.parse("2026-08-21T10:00:00+08:00"));
    }
}