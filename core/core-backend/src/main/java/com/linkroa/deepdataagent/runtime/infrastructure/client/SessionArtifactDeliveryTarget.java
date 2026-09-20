package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.application.port.ArtifactDeliveryPort;
import com.linkroa.deepdataagent.runtime.application.port.ArtifactDeliverySignalPort;
import com.linkroa.deepdataagent.runtime.domain.event.ArtifactDeliveredSignal;
import com.linkroa.deepdataagent.runtime.infrastructure.util.ArtifactContentTypeResolver;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话产出交付目标：AgentScope Harness 2.0.3 {@link ArtifactDeliveryTarget} SPI 的进程内实现。
 * <p>替代 2.0.1 时期的自研 {@code DeliverArtifacts} 工具：框架内置 {@code deliver_artifact}
 * 工具负责参数面（filePath / fileName / description / force）与沙箱字节下载，本实现只承载
 * 「字节 → 平台 File 资源」的登记语义，经
 * {@code HarnessAgent.Builder#artifactDeliveryTarget(...)} 按轮装配。</p>
 *
 * <p>保留的平台不变量（与原自研工具逐条对齐）：</p>
 * <ul>
 *   <li>用途恒为 {@code tool_output}，归属 sessionId / ownerId 来自装配闭包，
 *       模型无从置入（不使用 {@link RuntimeContext} 的通用会话标识）；</li>
 *   <li>路径逃逸二次校验：框架 {@code WorkspacePathNormalizer} 仅剥离 workspace 前缀，
 *       绝对路径（如 {@code /etc/passwd}）会原样透传且框架允许下载——本目标在登记前
 *       再做一次 workspace 相对路径强校验，逃逸即 {@link ArtifactDeliveryResult#fail}，
 *       字节虽已被框架读取但绝不离开沙箱边界；</li>
 *   <li>会话级累计交付配额（500MB）跨轮记账，登记成功后才累加，失败交付不占额度；</li>
 *   <li>端口准入拒绝（空内容 / 超 50MB / 非文本等）转可读失败结果，不抛穿炸轮；</li>
 *   <li>登记成功后向应用层上抛交付信号（{@link ArtifactDeliverySignalPort}），由应用层落库并推送
 *       {@code agent.artifact_delivered} 事件（逐文件一条）；事件上抛失败仅告警，不改变已成功的交付结果。</li>
 * </ul>
 *
 * <p>不使用 {@link ArtifactDeliveryResult#conflict}：平台 File 登记恒为新增资源
 * （file_ id 唯一，同名文件允许并存），不存在「目的地同名冲突」语义，故 {@code force}
 * 标志无需消费；{@code description} 暂无落库面，一并忽略。显式交付裁定不变：
 * 只有模型显式调用 {@code deliver_artifact} 的沙箱产出才登记为会话产物。</p>
 */
public class SessionArtifactDeliveryTarget implements ArtifactDeliveryTarget {

    private static final Logger log = LoggerFactory.getLogger(SessionArtifactDeliveryTarget.class);

    /** 交付用途：沙箱平面产出统一按工具调用产物登记（审查裁定，见 register-sandbox-artifacts D3）。 */
    private static final String PURPOSE_TOOL_OUTPUT = "tool_output";
    /** 会话累计交付配额（字节）：与挂载面 500MB 同数值但语义独立（交付写入 vs 挂载读取）。 */
    static final long MAX_SESSION_DELIVERY_BYTES = 500L * 1024 * 1024;

    /** 会话累计已交付字节表（跨轮共享，按 sessionId 记账；登记成功后累加）。 */
    static final Map<String, AtomicLong> SESSION_DELIVERED_BYTES = new ConcurrentHashMap<>();

    private final ArtifactDeliveryPort artifactDeliveryPort;
    private final ArtifactDeliverySignalPort artifactDeliverySignalPort;
    private final String sessionId;
    private final Long ownerId;

    /**
     * 以装配闭包固定的会话归属构建交付目标（用途恒为 {@code tool_output}，模型无从置入归属）。
     *
     * @param artifactDeliveryPort       产出登记端口（平台 File 资源登记）
     * @param artifactDeliverySignalPort 交付信号上抛端口（应用层据此落库并推送事件）
     * @param sessionId                  归属会话 ID
     * @param ownerId                    归属用户 ID
     */
    public SessionArtifactDeliveryTarget(ArtifactDeliveryPort artifactDeliveryPort,
                                         ArtifactDeliverySignalPort artifactDeliverySignalPort,
                                         String sessionId, Long ownerId) {
        this.artifactDeliveryPort = artifactDeliveryPort;
        this.artifactDeliverySignalPort = artifactDeliverySignalPort;
        this.sessionId = sessionId;
        this.ownerId = ownerId;
    }

    @Override
    public ArtifactDeliveryResult deliver(RuntimeContext runtimeContext, ArtifactDeliveryRequest request) {
        // given 路径逃逸二次校验：框架只剥 workspace 前缀，绝对路径 / 越界相对路径在此拒绝登记
        String relativePath = normalizeWorkspaceRelative(request == null ? null : request.filePath());
        if (relativePath == null) {
            return ArtifactDeliveryResult.fail(
                    "路径非法，必须为沙箱 workspace 内的相对路径（不允许绝对路径或越出 workspace）: "
                            + (request == null ? null : request.filePath()));
        }
        byte[] content = request.content();
        long size = content == null ? 0 : content.length;

        // 配额前置校验：登记成功后才累加，失败交付不占额度
        AtomicLong delivered = SESSION_DELIVERED_BYTES.computeIfAbsent(sessionId, k -> new AtomicLong());
        if (delivered.get() + size > MAX_SESSION_DELIVERY_BYTES) {
            return ArtifactDeliveryResult.fail(
                    "本会话累计交付已超过配额 " + MAX_SESSION_DELIVERY_BYTES + " bytes，请精简产出后重试");
        }

        // when 端口登记：fileName 已由框架校验为纯文件名；用途与归属由装配上下文固定
        try {
            String fileId = artifactDeliveryPort.registerSessionArtifact(
                    sessionId, ownerId, PURPOSE_TOOL_OUTPUT, request.fileName(), content);
            delivered.addAndGet(size);
            // then 登记成功即上抛交付信号（应用层据此落 agent.artifact_delivered，逐文件一条、保序）
            publishDeliveredSignal(request.fileName(), fileId, size);
            // then 成功详情：file_<id>（<size> bytes），框架工具会在外层拼入交付结果文案
            return ArtifactDeliveryResult.success(fileId + "（" + size + " bytes）");
        } catch (IllegalArgumentException ex) {
            // 端口准入规则拒绝（空内容 / 超 50MB / 非文本 / 归属缺失等），转可读失败
            return ArtifactDeliveryResult.fail(summarize(ex.getMessage()));
        } catch (Exception ex) {
            return ArtifactDeliveryResult.fail("产出登记出错: " + summarize(ex.getMessage()));
        }
    }

    /**
     * 上抛交付信号（登记成功后调用）：事件装配 / 落库 / 广播由应用层承载。
     * <p>异常仅告警——产物已登记成功，事件上抛失败 MUST NOT 把交付结果改写为失败
     * （否则模型会重复交付同一产物）；事件表缺该条交付事件属可容忍的观测缺口。</p>
     */
    private void publishDeliveredSignal(String filename, String fileId, long size) {
        try {
            artifactDeliverySignalPort.publish(new ArtifactDeliveredSignal(sessionId, fileId, filename, size,
                    ArtifactContentTypeResolver.resolve(filename)));
        } catch (RuntimeException ex) {
            log.warn("产物交付事件上抛失败（交付已成功，不回滚登记）: sessionId={}, fileId={}",
                    sessionId, fileId, ex);
        }
    }

    /**
     * workspace 相对路径规范化：反斜杠统一为 {@code /}，消解 {@code .} / {@code ..} 段；
     * 绝对路径或越出 workspace 根的相对路径返回 {@code null}（逃逸判定）。
     */
    private static String normalizeWorkspaceRelative(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String unified = path.trim().replace('\\', '/');
        if (unified.startsWith("/")) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (String segment : unified.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    return null;
                }
                segments.remove(segments.size() - 1);
            } else {
                segments.add(segment);
            }
        }
        return segments.isEmpty() ? null : String.join("/", segments);
    }

    /** 错误信息摘要：压平换行并截断，避免长堆栈污染工具结果。 */
    private static String summarize(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        String flat = message.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }
}
