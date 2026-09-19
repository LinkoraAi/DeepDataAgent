package com.linkroa.deepdataagent.skill.controller.rest;

import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import com.linkroa.deepdataagent.skill.application.command.CreateSkillCommand;
import com.linkroa.deepdataagent.skill.application.command.CreateSkillVersionCommand;
import com.linkroa.deepdataagent.skill.application.convert.SkillCommandConvert;
import com.linkroa.deepdataagent.skill.application.service.SkillApplicationService;
import com.linkroa.deepdataagent.skill.controller.convert.SkillResponseConvert;
import com.linkroa.deepdataagent.skill.controller.response.SkillDetailResponse;
import com.linkroa.deepdataagent.skill.controller.response.SkillResponse;
import com.linkroa.deepdataagent.skill.controller.response.SkillVersionResponse;
import com.linkroa.deepdataagent.skill.domain.model.SkillAsset;
import com.linkroa.deepdataagent.skill.domain.model.SkillContent;
import com.linkroa.deepdataagent.skill.domain.model.SkillVersion;
import jakarta.annotation.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 技能资产 REST 控制器（版本化前缀，有效路径 {@code /api/v1/cloud/skills}）。
 * <ul>
 *   <li>{@code POST /}：multipart 技能包创建（201，首版随建）；</li>
 *   <li>{@code GET /}：游标分页列表（source / keyword 过滤 + limit / after_id / before_id）；</li>
 *   <li>{@code GET /{id}}：详情（壳对象 + 全部版本信息，不暴露内容全文）；</li>
 *   <li>{@code GET /{id}/versions}：游标版本列表（最新在前，游标为版本键）；</li>
 *   <li>{@code POST /{id}/versions}：multipart 发版（生成新的不可变 epoch 版本）；</li>
 *   <li>{@code GET /{id}/versions/{versionNo}}：版本元数据详情；</li>
 *   <li>{@code GET /{id}/versions/{versionNo}/content}：版本内容 zip 归档下载；</li>
 *   <li>{@code DELETE /{id}/versions/{versionNo}}：删除版本（被显式钉版绑定 → 409）；</li>
 *   <li>{@code DELETE /{id}}：删除技能（仍被绑定 → 409）。</li>
 * </ul>
 * <p>不再提供 JSON 正文创建 / 更新端点与独立 metadata PUT 端点；展示名创建后不可修改。
 * 包结构违规为 400 {@code invalid_request_error}，zip 体积超限为 413
 * {@code request_too_large_error}。</p>
 */
@RestController
@RequestMapping(path = "/cloud/skills", version = ApiVersionConstants.CURRENT_API_VERSION)
public class SkillController {

    @Resource
    private SkillApplicationService applicationService;

    /**
     * 创建技能（multipart 技能包：单个 .zip 或裸文件树；{@code display_title} 表单字段可选）。
     * <p>以 JSON 正文请求时无 files part，解析器直接判空 → 400。</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SkillResponse> create(
            @RequestParam(name = "files", required = false) List<MultipartFile> files,
            @RequestParam(name = "display_title", required = false) String displayTitle) {
        SkillAsset asset = applicationService.createSkill(new CreateSkillCommand(files, displayTitle));
        return ApiResponse.success(SkillResponseConvert.INSTANCE.toResponse(asset));
    }

    /**
     * 游标分页列出技能。
     * <p>{@code source} 取值 catalog/custom（非法 → 400）；{@code keyword} 展示名模糊；
     * {@code limit / after_id / before_id} 统一游标约定（游标技能不存在 / 非本人 → 404）。</p>
     */
    @GetMapping
    public ApiResponse<CursorPage<SkillResponse>> list(
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "after_id", required = false) String afterId,
            @RequestParam(name = "before_id", required = false) String beforeId
    ) {
        CursorPage<SkillAsset> page = applicationService.listSkills(SkillCommandConvert.INSTANCE
                .toListQuery(AuthContext.requireUserId(), source, keyword, limit, afterId, beforeId));
        return ApiResponse.success(page.map(SkillResponseConvert.INSTANCE::toResponse));
    }

    /**
     * 技能详情（壳对象 + 全部版本信息，不含内容全文）。
     */
    @GetMapping("/{id}")
    public ApiResponse<SkillDetailResponse> detail(@PathVariable String id) {
        return ApiResponse.success(SkillResponseConvert.INSTANCE
                .toDetailResponse(applicationService.getSkill(id), applicationService.listAllVersions(id)));
    }

    /**
     * 游标分页列出版本（最新在前；游标 after_id/before_id 为版本键）。
     */
    @GetMapping("/{id}/versions")
    public ApiResponse<CursorPage<SkillVersionResponse>> listVersions(
            @PathVariable String id,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "after_id", required = false) String afterId,
            @RequestParam(name = "before_id", required = false) String beforeId
    ) {
        CursorPage<SkillVersion> page = applicationService.listVersions(id,
                CursorPageParams.parse(limit, afterId, beforeId));
        return ApiResponse.success(page.map(SkillResponseConvert.INSTANCE::toVersionResponse));
    }

    /**
     * 发版（multipart 技能包）：生成新的不可变 epoch 微秒版本，旧版本仍可查询 / 被旧绑定引用。
     * <p>包 frontmatter name 必须与首版一致（否则 400）。</p>
     */
    @PostMapping("/{id}/versions")
    public ApiResponse<SkillVersionResponse> createVersion(
            @PathVariable String id,
            @RequestParam(name = "files", required = false) List<MultipartFile> files) {
        CreateSkillVersionCommand command = new CreateSkillVersionCommand(id, files);
        return ApiResponse.success(SkillResponseConvert.INSTANCE
                .toVersionResponse(applicationService.createVersion(command)));
    }

    /**
     * 版本详情（元数据：版本键 / frontmatter 名称与描述 / 目录名 / 创建时间，不暴露内容全文）。
     * <p>路径变量名为 {@code versionNo}：避免与 API 版本化前缀 {@code /api/{version}} 的
     * URI 变量重名（PathPattern 不允许同一 pattern 两次捕获同名变量）。</p>
     */
    @GetMapping("/{id}/versions/{versionNo}")
    public ApiResponse<SkillVersionResponse> getVersion(@PathVariable String id,
                                                        @PathVariable("versionNo") String version) {
        return ApiResponse.success(SkillResponseConvert.INSTANCE
                .toVersionResponse(applicationService.getVersion(id, version)));
    }

    /**
     * 版本内容存档下载（zip 八位字节流：SKILL.md + 资源文件；技能 / 版本 / 磁盘内容缺失 → 404）。
     */
    @GetMapping("/{id}/versions/{versionNo}/content")
    public ResponseEntity<byte[]> downloadVersionContent(@PathVariable String id,
                                                         @PathVariable("versionNo") String version) {
        SkillContent content = applicationService.getContent(id, version);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", ContentDisposition.attachment()
                        .filename(id + "-" + version + ".zip", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(SkillResponseConvert.INSTANCE.toZipArchive(content));
    }

    /**
     * 删除特定版本：仍被未删除 Agent 版本显式钉版绑定的版本 → 409；
     * 头版本被删时 latest_version 重算，全部版本删除后为 null。
     */
    @DeleteMapping("/{id}/versions/{versionNo}")
    public ApiResponse<Void> deleteVersion(@PathVariable String id,
                                           @PathVariable("versionNo") String version) {
        applicationService.deleteVersion(id, version);
        return ApiResponse.success(null);
    }

    /**
     * 删除技能（逻辑删除，历史版本数据保留；仍被未删除 Agent 版本绑定 → 409）。
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> delete(@PathVariable String id) {
        applicationService.deleteSkill(id);
        return ResponseEntity.noContent().build();
    }
}