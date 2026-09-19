package com.linkroa.deepdataagent.agent.controller.rest;

import com.linkroa.deepdataagent.agent.application.command.CreateAgentCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishAgentVersionCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateAgentCommand;
import com.linkroa.deepdataagent.agent.application.convert.AgentCommandConvert;
import com.linkroa.deepdataagent.agent.application.query.ListAgentQuery;
import com.linkroa.deepdataagent.agent.application.service.AgentApplicationService;
import com.linkroa.deepdataagent.agent.controller.convert.AgentResponseConvert;
import com.linkroa.deepdataagent.agent.controller.request.AgentConfigRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateAgentRequest;
import com.linkroa.deepdataagent.agent.controller.response.AgentResponse;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Agent 定义与版本管理 REST 控制器（统一前缀 {@code /api/v1/cloud/agents}）。
 * <p>列表与版本历史遵循 shared/api-conventions Cursor 约定
 * （{@code limit/page/after_id/before_id} → {@code {data, first_id, last_id, has_more, next_page}}，
 * 创建时间降序；版本历史游标为版本号字符串、有下一页时回传 {@code next_page}）；
 * 更新以 OCC 乐观并发控制（version 不匹配 → 409 conflict_error）。</p>
 * <p>Agent 对象与版本快照在公开契约中<b>同形</b>（{@link AgentResponse}），差别仅在 {@code version}
 * 取值口径：Agent 对象取当前生效版本，版本快照取该快照自身版本号。</p>
 */
@RestController
@RequestMapping(path = "/cloud/agents", version = ApiVersionConstants.CURRENT_API_VERSION)
public class AgentController {

    @Resource
    private AgentApplicationService applicationService;

    /**
     * 创建 Agent 定义，并同步产生首版快照与激活版本。
     *
     * @param request Agent 配置请求
     * @return 新建的完整 Agent 对象（version=1）
     */
    @PostMapping
    public ApiResponse<AgentResponse> create(@Valid @RequestBody AgentConfigRequest request) {
        CreateAgentCommand command = AgentCommandConvert.INSTANCE.toCreateCommand(request);
        AgentDefinition definition = applicationService.createAgent(command);
        return ApiResponse.success(toAgentResponse(definition));
    }

    /**
     * 游标分页查询 Agent 列表（创建时间降序，不提供 total）。
     *
     * @param keyword       名称模糊匹配（可空）
     * @param status        状态过滤：active（缺省）/ archived
     * @param metadata      元数据键值包含过滤 JSON 对象文本（可空；按激活版本快照匹配）
     * @param createdAfter  创建时间下界（RFC3339，含，可空）
     * @param createdBefore 创建时间上界（RFC3339，含，可空）
     * @param limit         每页条数（1-100，缺省 20）
     * @param afterId       向后翻页游标（当页 last_id）
     * @param beforeId      向前翻页游标（当页 first_id，与 after_id 互斥）
     */
    @GetMapping
    public ApiResponse<CursorPage<AgentResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String metadata,
            @RequestParam(name = "created_after", required = false) String createdAfter,
            @RequestParam(name = "created_before", required = false) String createdBefore,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "after_id", required = false) String afterId,
            @RequestParam(name = "before_id", required = false) String beforeId
    ) {
        ListAgentQuery query = AgentCommandConvert.INSTANCE.toListQuery(
                keyword, status, metadata, createdAfter, createdBefore, limit, afterId, beforeId);
        CursorPage<AgentDefinition> page = applicationService.listAgents(query);
        // 当页各 Agent 的当前生效版本快照一次批量取回（避免逐行回表）
        Map<String, AgentVersion> currentVersions = applicationService.currentVersions(page.data());
        return ApiResponse.success(page.map(definition -> AgentResponseConvert.INSTANCE.toResponse(
                definition, currentVersions.get(definition.agentId()))));
    }

    /**
     * Agent 详情：省略 {@code version} 返回完整 Agent 对象（配置取当前生效版本）；
     * {@code version=N} 返回该版本的快照对象（与 Agent 对象同形，{@code version} 为 N）。
     *
     * @param agentId Agent 业务 ID
     * @param version 目标发布号（可空 = 当前生效版本）
     */
    @GetMapping("/{agentId}")
    public ApiResponse<AgentResponse> detail(
            @PathVariable String agentId,
            @RequestParam(required = false) Integer version
    ) {
        AgentDefinition definition = applicationService.getAgent(agentId);
        if (version == null) {
            return ApiResponse.success(toAgentResponse(definition));
        }
        AgentVersion snapshot = applicationService.getVersion(agentId, version);
        return ApiResponse.success(AgentResponseConvert.INSTANCE.toVersionResponse(snapshot, definition.archivedAt()));
    }

    /**
     * 更新 Agent 定义属性（名称/描述，OCC：请求 MUST 携带当前 version，不匹配 409 conflict_error；
     * 更新同步产生新版本快照并递增 latest_version）。
     * <p>方法为 {@code POST}（公开契约 {@code POST /api/v1/cloud/agents/{agent_id}}），
     * 与平台其他资源更新端点形态一致。</p>
     */
    @PostMapping("/{agentId}")
    public ApiResponse<AgentResponse> update(
            @PathVariable String agentId,
            @Valid @RequestBody UpdateAgentRequest request
    ) {
        UpdateAgentCommand command = AgentCommandConvert.INSTANCE.toUpdateCommand(agentId, request);
        return ApiResponse.success(toAgentResponse(applicationService.updateAgent(command)));
    }

    /**
     * 发布新版本快照：版本号由服务端按版本台账递增，请求体为完整 Agent 配置。
     *
     * @param agentId Agent 业务 ID
     * @param request Agent 配置请求
     * @return 新发布的版本快照对象
     */
    @PostMapping("/{agentId}/versions")
    public ApiResponse<AgentResponse> publishVersion(
            @PathVariable String agentId,
            @Valid @RequestBody AgentConfigRequest request
    ) {
        PublishAgentVersionCommand command = AgentCommandConvert.INSTANCE.toPublishCommand(agentId, request);
        AgentVersion version = applicationService.publishVersion(command);
        // 归档 Agent 不可发版（应用服务已拒绝），故发版响应的归档位恒为空
        return ApiResponse.success(AgentResponseConvert.INSTANCE.toVersionResponse(version, null));
    }

    /**
     * 游标分页查询版本历史（发布号降序，含 name/model/system 等快照字段，不提供 total）。
     * <p>游标为「版本号字符串」：{@code first_id} / {@code last_id} 即当页首尾版本号，
     * 有下一页时回传 {@code next_page = last_id}；入参 {@code page} 为不透明下页游标
     * （与 {@code after_id} / {@code before_id} 互斥，同传 400，非正整数 400）。</p>
     */
    @GetMapping("/{agentId}/versions")
    public ApiResponse<CursorPage<AgentResponse>> listVersions(
            @PathVariable String agentId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String page,
            @RequestParam(name = "after_id", required = false) String afterId,
            @RequestParam(name = "before_id", required = false) String beforeId
    ) {
        AgentDefinition definition = applicationService.getAgent(agentId);
        CursorPage<AgentVersion> versionPage = applicationService.listVersionPage(
                agentId, CursorPageParams.parse(limit, page, afterId, beforeId));
        return ApiResponse.success(versionPage.map(version ->
                AgentResponseConvert.INSTANCE.toVersionResponse(version, definition.archivedAt())));
    }

    /**
     * 激活 / 回滚版本：切换当前激活版本（会话省略版本装配与调度器创建解析的依据）；
     * 激活号越出已发布台账区间 → 400，版本台账缺失 → 404，已归档 Agent → 409。
     */
    @PostMapping("/{agentId}/versions/{versionNumber}/activate")
    public ApiResponse<AgentResponse> activateVersion(@PathVariable String agentId,
                                                      @PathVariable int versionNumber) {
        return ApiResponse.success(toAgentResponse(applicationService.activateVersion(agentId, versionNumber)));
    }

    /**
     * 归档 Agent：仅写 {@code archived_at}，已发布版本仍可供既有 Session 装配。
     *
     * @param agentId Agent 业务 ID
     * @return 空响应
     */
    @PostMapping("/{agentId}/archive")
    public ApiResponse<Void> archive(@PathVariable String agentId) {
        applicationService.archiveAgent(agentId);
        return ApiResponse.success(null);
    }

    /**
     * 删除 Agent 定义及其全部版本（逻辑删除）。
     *
     * @param agentId Agent 业务 ID
     * @return 空响应
     */
    @DeleteMapping("/{agentId}")
    public ApiResponse<Void> delete(@PathVariable String agentId) {
        applicationService.deleteAgent(agentId);
        return ApiResponse.success(null);
    }

    /** 装配完整 Agent 对象（定义属性 + 当前生效版本配置快照）。 */
    private AgentResponse toAgentResponse(AgentDefinition definition) {
        return AgentResponseConvert.INSTANCE.toResponse(definition, applicationService.currentVersion(definition));
    }
}