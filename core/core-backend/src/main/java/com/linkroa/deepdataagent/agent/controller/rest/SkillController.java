package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.assembler.SkillCommandAssembler;
import com.linkroa.deepdataagent.agent.application.command.CreateSkillCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishSkillVersionCommand;
import com.linkroa.deepdataagent.agent.application.query.ListSkillQuery;
import com.linkroa.deepdataagent.agent.application.service.SkillApplicationService;
import com.linkroa.deepdataagent.agent.controller.request.CreateSkillRequest;
import com.linkroa.deepdataagent.agent.controller.request.PublishSkillVersionRequest;
import com.linkroa.deepdataagent.agent.controller.response.SkillDetailResponse;
import com.linkroa.deepdataagent.agent.controller.response.SkillResourceResponse;
import com.linkroa.deepdataagent.agent.controller.response.SkillResourceResponseMapper;
import com.linkroa.deepdataagent.agent.domain.model.SkillResource;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.PaginatedResponse;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 技能资源管理 REST 控制器（统一前缀 {@code /api/v1/agent/skills}）。
 * 上传/发布版本采用 multipart/form-data：{@code file} 为技能包二进制，
 * {@code meta} 为 JSON 元数据部分（严格对齐 D7 存储端口设计）。
 */
@RestController
@RequestMapping(path = "/agent/skills", version = ApiVersionConstants.CURRENT_API_VERSION)
public class SkillController {

    @Resource
    private SkillApplicationService applicationService;
    @Resource
    private SkillResourceResponseMapper responseMapper;
    @Resource
    private SkillCommandAssembler commandAssembler;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SkillResourceResponse> create(
            @RequestPart("file") MultipartFile file,
            @Valid @RequestPart("meta") CreateSkillRequest meta
    ) throws IOException {
        byte[] content = readContent(file);
        CreateSkillCommand command = commandAssembler.toCreateCommand(meta, content);
        return ApiResponse.success(responseMapper.toResponse(applicationService.createSkill(command)));
    }

    @PostMapping(value = "/{skillId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SkillResourceResponse> publishVersion(
            @PathVariable String skillId,
            @RequestPart("file") MultipartFile file,
            @Valid @RequestPart("meta") PublishSkillVersionRequest meta
    ) throws IOException {
        byte[] content = readContent(file);
        PublishSkillVersionCommand command = commandAssembler.toPublishCommand(skillId, meta, content);
        return ApiResponse.success(responseMapper.toResponse(applicationService.publishVersion(command)));
    }

    @GetMapping
    public ApiResponse<PaginatedResponse<SkillResourceResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit
    ) {
        ListSkillQuery query = toListQuery(keyword, page, limit);
        List<SkillResource> skills = applicationService.listSkills(query);
        long total = applicationService.countSkills(query);
        List<SkillResourceResponse> responses = skills.stream()
                .map(responseMapper::toResponse)
                .toList();
        return ApiResponse.success(new PaginatedResponse<>(responses, total, query.page(), query.size()));
    }

    @GetMapping("/{skillId}")
    public ApiResponse<SkillDetailResponse> detail(@PathVariable String skillId) {
        List<SkillResourceResponse> versions = applicationService.getSkillVersions(skillId).stream()
                .map(responseMapper::toResponse)
                .toList();
        return ApiResponse.success(new SkillDetailResponse(skillId, versions));
    }

    @GetMapping("/{skillId}/versions/{version}/content")
    public ResponseEntity<byte[]> downloadContent(
            @PathVariable String skillId,
            @PathVariable int version
    ) {
        byte[] content = applicationService.downloadContent(skillId, version);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + skillId + "-" + version + ".zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    @DeleteMapping("/{skillId}")
    public ApiResponse<Void> delete(@PathVariable String skillId) {
        applicationService.deleteSkill(skillId);
        return ApiResponse.success(null);
    }

    private byte[] readContent(MultipartFile file) throws IOException {
        return file.getBytes();
    }

    private ListSkillQuery toListQuery(String keyword, Integer page, Integer limit) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safeSize = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
        return new ListSkillQuery(keyword, safePage, safeSize);
    }
}