package com.linkroa.deepdataagent.runtime.application.service.assembly;

import com.linkroa.deepdataagent.file.api.FileApi;
import com.linkroa.deepdataagent.file.api.dto.FileMountMetaDTO;
import com.linkroa.deepdataagent.file.application.dto.FileMountMaterializationDTO;
import com.linkroa.deepdataagent.file.application.port.FileMountMaterializationPort;
import com.linkroa.deepdataagent.runtime.application.service.UserIds;
import com.linkroa.deepdataagent.runtime.domain.factory.SessionWorkspacePort;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 会话挂载物化编排（挂载写入即物化 + 装配解析期幂等自愈，D5 / D20）。
 * <p>把 file 类挂载资源经 {@code file} BC 的
 * {@link FileMountMaterializationPort} 流式物化为宿主副本，落点由
 * {@link SessionWorkspacePort} 按 {@code <agentWorkspace>/<sessionId>/mounts} 派生
 * （恒按 sessionId、与框架 IsolationScope 解耦，D17）。四条通路：</p>
 * <ul>
 *   <li><b>创建 / 追加物化</b>（{@link #materializeAll} / {@link #materialize}）：
 *   MUST 在判重与配额校验通过后、入库事务之前调用（D19：超配额请求不得留下宿主副本）；
 *   批内任一项失败（内容缺失 / 门禁拒 / 摘要不符）即整批拒绝，并补偿删除本轮已物化副本；</li>
 *   <li><b>入库失败补偿</b>：事务失败由调用方以 {@link #release}（追加项）/
 *   {@link #releaseAll}（创建项）删除已物化副本，不留孤儿（D5「先物化、后入库」的对偶义务）；
 *   补偿尽力而为，失败仅记日志不覆盖主异常；</li>
 *   <li><b>装配期对账</b>（{@link #reconcile}）：缓存未命中路径逐项检查副本、缺失才补物化
 *   （历史会话 / 宿主目录被外部清理 / 磁盘故障三类漂移的自愈口），返回本轮实际可见的
 *   挂载视图（D20 单一事实源：清单与 bind mount 同源）；<b>不做孤儿副本清理</b>
 *   （物化先于入库事务，孤儿判定会与并发追加竞态误删）；不重算摘要
 *   （同 fileId 内容不可变，副本在位即视为正确）；</li>
 *   <li><b>会话删除清理</b>（{@link #releaseAll}）：委托端口递归清理会话命名空间目录，
 *   MUST NOT 触及 Agent 级工作区根内容。</li>
 * </ul>
 */
@Slf4j
@Service
public class SessionMountMaterializer {

    /** 文件挂载物化端口（file BC 进程内出站，流式复制 + SHA-256 校验）。 */
    @Resource
    private FileMountMaterializationPort fileMountMaterializationPort;
    /** 文件服务契约：对账期取轻量元数据（原始文件名 / 字节数），不读磁盘内容。 */
    @Resource
    private FileApi fileApi;
    /** 会话工作区布局端口：宿主挂载落点派生 + 命名空间清理。 */
    @Resource
    private SessionWorkspacePort sessionWorkspacePort;

    /**
     * 创建路径物化：会话全量 file 挂载逐项物化到宿主（校验已在上游完成，此处只管落盘）。
     *
     * @param session 待落库的会话镜像（sessionId 已生成，尚未入库）
     */
    public void materializeAll(AgentSession session) {
        materialize(session, session.resources());
    }

    /**
     * 物化给定资源集合中的 file 项；任一项失败即抛可读错误，且本轮已成功物化的副本
     * 全部补偿删除（整批全有或全无，不留残缺）。
     *
     * @param session   会话镜像（提供 agentId / sessionId / userId 落点与门禁上下文）
     * @param resources 本批挂载资源（非 file 项自动忽略）
     * @throws IllegalArgumentException 文件不存在 / 越权 / 未就绪 / 磁盘内容缺失
     * @throws RuntimeException         摘要不符（{@code FileContentIntegrityException}）等物化异常；
     *                                  两路抛出前均已补偿删除本轮副本
     */
    public void materialize(AgentSession session, List<SessionResource> resources) {
        List<SessionResource> fileResources = fileResources(resources);
        if (fileResources.isEmpty()) {
            return;
        }
        Long ownerId = UserIds.parse(session.userId());
        List<Path> placed = new ArrayList<>();
        try {
            for (SessionResource resource : fileResources) {
                Path target = sessionWorkspacePort.mountTarget(
                        session.agentId(), session.sessionId(), resource.mountPath());
                Optional<FileMountMaterializationDTO> materialized =
                        fileMountMaterializationPort.materialize(resource.fileId(), ownerId, target);
                if (materialized.isEmpty()) {
                    // 门禁 / 磁盘内容缺失统一收敛为可读拒绝（挂载无其他承载通道，静默跳过 = 文件彻底不可见）
                    throw new IllegalArgumentException(
                            "挂载文件不存在、无权访问或内容不可用，拒绝挂载: " + resource.fileId());
                }
                placed.add(target);
            }
        } catch (RuntimeException ex) {
            placed.forEach(this::deleteQuietly);
            throw ex;
        }
    }

    /**
     * 移除单个挂载项的宿主副本（移除挂载 / 追加入库失败补偿共用入口）。
     * <p>尽力而为：副本本就不存在视为成功；删除失败仅记 error 日志，不影响调用方主流程。</p>
     *
     * @param session  会话镜像
     * @param resource 待释放的 file 挂载资源（非 file 项空操作）
     */
    public void release(AgentSession session, SessionResource resource) {
        if (!SessionResource.FILE_TYPE.equals(resource.type())) {
            return;
        }
        Path target = sessionWorkspacePort.mountTarget(
                session.agentId(), session.sessionId(), resource.mountPath());
        deleteQuietly(target);
    }

    /**
     * 装配解析期幂等对账（D20）：逐项检查宿主副本，缺失才补物化；返回本轮实际可见的挂载视图。
     * <p>视图 = 「有挂载记录」的每一项（含补物化成功项）：元数据缺失（文件记录已删）时
     * 以 fileId 回落文件名、体积记 0 并告警，保证「有记录即有行」。补物化遇摘要不符
     * 异常照常上抛（一致性事故不允许以「无副本」静默呈现）。</p>
     * <p><b>不做孤儿副本清理</b>：「副本已落盘、记录未提交」是并发追加的合法中间态，
     * 对账期删除会与并发追加竞态误删；无记录副本因整目录挂载而对沙箱可见但不进清单，
     * 登记为已知残留、随会话删除整体回收。</p>
     *
     * @param session 会话（提供挂载资源与落点）
     * @param ownerId 归属用户 ID（补物化门禁上下文）
     * @return 挂载视图（保序，与 {@code session.resources()} 中 file 项一一对应）
     */
    public List<AgentAssemblySpec.FileMountRef> reconcile(AgentSession session, Long ownerId) {
        List<AgentAssemblySpec.FileMountRef> view = new ArrayList<>();
        for (SessionResource resource : fileResources(session.resources())) {
            Path target = sessionWorkspacePort.mountTarget(
                    session.agentId(), session.sessionId(), resource.mountPath());
            if (!Files.exists(target)) {
                // 自愈口：副本缺失才补物化（在位即信任——同 fileId 内容不可变，不重算摘要）
                fileMountMaterializationPort.materialize(resource.fileId(), ownerId, target);
            }
            FileMountMetaDTO meta = fileApi.findReadyMountMeta(resource.fileId(), ownerId).orElse(null);
            if (meta == null) {
                log.warn("挂载文件元数据缺失（记录已删或未就绪），清单以 fileId 回落、体积记 0: "
                        + "sessionId={}, fileId={}", session.sessionId(), resource.fileId());
                view.add(new AgentAssemblySpec.FileMountRef(
                        resource.fileId(), resource.fileId(), 0L, resource.mountPath()));
                continue;
            }
            view.add(new AgentAssemblySpec.FileMountRef(
                    meta.fileId(), meta.filename(), meta.sizeBytes(), resource.mountPath()));
        }
        return List.copyOf(view);
    }

    /**
     * 会话删除清理入口：递归清理宿主会话命名空间目录（含 mounts 与框架同层数据）。
     * <p>尽力而为：目录不存在不抛，失败记 error 日志；MUST NOT 删到 Agent 级工作区根内容。</p>
     *
     * @param session 会话镜像
     */
    public void releaseAll(AgentSession session) {
        sessionWorkspacePort.cleanup(session.agentId(), session.sessionId());
    }

    /** 提取 file 类挂载资源（保序）。 */
    private List<SessionResource> fileResources(List<SessionResource> resources) {
        if (resources == null) {
            return List.of();
        }
        return resources.stream()
                .filter(resource -> SessionResource.FILE_TYPE.equals(resource.type()))
                .toList();
    }

    /** 尽力删除单个宿主副本：不存在即成功，IO 失败仅记日志（不覆盖调用方主异常）。 */
    private void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException | RuntimeException ex) {
            log.error("挂载副本补偿删除失败（需人工关注，不覆盖主异常）: target={}", target, ex);
        }
    }
}
