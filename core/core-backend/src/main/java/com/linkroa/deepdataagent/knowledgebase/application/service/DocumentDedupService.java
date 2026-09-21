package com.linkroa.deepdataagent.knowledgebase.application.service;

import com.linkroa.deepdataagent.knowledgebase.application.result.DedupHit;
import com.linkroa.deepdataagent.knowledgebase.domain.model.DedupPolicyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DedupConflictAction;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DedupMatchRule;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import com.linkroa.deepdataagent.knowledgebase.domain.repository.DocumentRepository;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 文档判重组件（应用层）。
 * <p>负责四件事：
 * 1）解析知识库级去重策略 JSON（{@code dedup_policy}，形态 {@code {"matchRule": "...", "conflictAction": "..."}}），
 * 未配置 / 空 / 非法内容一律按「不检测」降级放行，保持存量上传行为不变；
 * 2）对上传的原始文件字节计算服务端可信内容哈希（SHA-256），作为判重内容轴；
 * 3）按判定依据携带查询轴并执行库内重复检测（只读）；
 * 4）生成 REJECT 场景下回传给调用方的重复摘要文本。</p>
 */
@Service
public class DocumentDedupService {

    private static final Logger log = LoggerFactory.getLogger(DocumentDedupService.class);

    /** 去重策略 JSON 中的判定依据字段名 */
    private static final String FIELD_MATCH_RULE = "matchRule";

    /** 去重策略 JSON 中的冲突动作字段名 */
    private static final String FIELD_CONFLICT_ACTION = "conflictAction";

    /**
     * 命中轴标签：文件名轴。
     * <p>对外契约字面量，语义为「命中了哪个判定轴」，MUST NOT 随持久化列名或存储形态变更而改名。</p>
     */
    public static final String AXIS_FILE_NAME = "fileName";

    /**
     * 命中轴标签：内容哈希轴。
     * <p>对外契约字面量，语义为「命中了哪个判定轴」，MUST NOT 随持久化列名或存储形态变更而改名。</p>
     */
    public static final String AXIS_CONTENT_HASH = "contentHash";

    /** 判重内容轴哈希算法名（服务端对上传的原始文件字节实算，口径唯一来源见 {@link #sha256Hex(byte[])}） */
    private static final String ALGORITHM_SHA256 = "SHA-256";

    /** JSON 解析器（无状态，进程内共享） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 文档仓储（库内重复检测只读查询） */
    private final DocumentRepository documentRepository;

    /**
     * 构造文档判重组件。
     *
     * @param documentRepository 文档仓储（库内重复检测只读查询）
     */
    public DocumentDedupService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /**
     * 解析知识库级去重策略。
     * <p>降级语义：策略为空、JSON 非法、判定依据或冲突动作缺失 / 取值未知时记录告警并返回 {@code null}
     * （按不检测处理），不阻断上传主流程——配置脏数据不应造成业务不可用。</p>
     *
     * @param knowledgeBase 所属知识库聚合根
     * @return 去重策略配置；未启用或不可解析时返回 {@code null}
     */
    public DedupPolicyConfig resolvePolicy(KnowledgeBase knowledgeBase) {
        String policyJson = knowledgeBase.dedupPolicy();
        if (StringUtils.isBlank(policyJson)) {
            return null;
        }
        DedupMatchRule matchRule;
        DedupConflictAction conflictAction;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(policyJson);
            matchRule = parseMatchRule(root.path(FIELD_MATCH_RULE).stringValue(null));
            conflictAction = parseConflictAction(root.path(FIELD_CONFLICT_ACTION).stringValue(null));
        } catch (JacksonException e) {
            log.warn("知识库去重策略不是合法JSON，按不检测放行，kbId={}，原始值={}",
                    knowledgeBase.id(), policyJson, e);
            return null;
        }
        if (ObjectUtils.isEmpty(matchRule)) {
            log.warn("知识库去重策略缺少可识别的判定依据，按不检测放行，kbId={}，原始值={}",
                    knowledgeBase.id(), policyJson);
            return null;
        }
        if (matchRule == DedupMatchRule.NONE) {
            // 显式关闭检测：动作无意义，直接返回不启用策略
            return new DedupPolicyConfig(matchRule, null);
        }
        if (ObjectUtils.isEmpty(conflictAction)) {
            log.warn("知识库去重策略缺少可识别的冲突动作，按不检测放行，kbId={}，原始值={}",
                    knowledgeBase.id(), policyJson);
            return null;
        }
        return new DedupPolicyConfig(matchRule, conflictAction);
    }

    /**
     * 计算判重内容轴哈希（SHA-256）。
     * <p>口径定版（与预检入参校验、持久化列口径镜像一致）：服务端对<b>上传的原始文件字节</b>
     * 计算 SHA-256，输出<b>小写十六进制、恒 64 字符</b>；该值唯一用途是判重轴，
     * MUST NOT 用作文件完整性校验、秒传或解析产物指纹。</p>
     * <p>调用方自报的哈希不参与判重；与 rag 侧解析产物的 {@code parsedTextHash}
     * （解析文本摘要、MD5、仅观测用）是两个键，不互改。</p>
     *
     * @param content 文件字节（非空）
     * @return 64 位小写十六进制哈希
     * @throws IllegalStateException SHA-256 算法不可用（JVM 环境异常）
     */
    public String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM_SHA256);
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 执行库内重复检测（只读查询）。
     * <p>按判定依据组装查询轴：BY_NAME 只带文件名、BY_CONTENT 只带内容哈希、
     * BY_NAME_OR_CONTENT 两轴同时带（仓储侧为 OR 语义）。查询轴全空或策略未启用时短路返回空列表，
     * 不触发数据库访问。返回顺序沿用仓储的文档ID升序，即覆盖处置时的固定清理（加锁）顺序。</p>
     * <p>上传链路 MUST 与文档登记置于同一事务内调用（先锁知识库行），避免判重与登记之间的
     * check-then-insert 竞态；预检端点等纯只读探测场景独立调用同样安全。</p>
     *
     * @param kbId        知识库ID
     * @param policy      已解析的去重策略，可为 {@code null}（不检测）
     * @param fileName    文件名判重轴取值，可为空白（该轴不参与检测）
     * @param contentHash 判重内容轴取值（服务端实算的 64 位小写十六进制 SHA-256），可为空白（该轴不参与检测）
     * @return 命中列表；无命中返回空列表
     */
    public List<DedupHit> detect(Long kbId, DedupPolicyConfig policy, String fileName, String contentHash) {
        if (ObjectUtils.isEmpty(policy) || !policy.isDetectionEnabled()) {
            return List.of();
        }
        DedupMatchRule rule = policy.matchRule();
        String queryName = needNameAxis(rule) ? StringUtils.trimToNull(fileName) : null;
        String queryHash = needHashAxis(rule) ? StringUtils.trimToNull(contentHash) : null;
        if (StringUtils.isAllBlank(queryName, queryHash)) {
            return List.of();
        }
        List<Document> duplicates = documentRepository.findDuplicates(kbId, queryName, queryHash);
        if (CollectionUtils.isEmpty(duplicates)) {
            return List.of();
        }
        List<DedupHit> hits = new ArrayList<>(duplicates.size());
        for (Document duplicate : duplicates) {
            hits.add(new DedupHit(duplicate, resolveMatchAxes(rule, queryName, queryHash, duplicate)));
        }
        return hits;
    }

    /**
     * 生成 REJECT 场景回传给调用方的重复摘要文本。
     * <p>共享异常体系不支持结构化字段（shared 零改动约束），故将命中文档的
     * ID / 文件名 / 命中轴嵌入消息，由 409 响应体透出。</p>
     * <p>状态为 {@link DocumentStatus#DELETING} / {@link DocumentStatus#DELETE_FAILED} 的命中项
     * <b>不计入</b>用户可见的重复提示：这类旧文档正在（或已失败待重跑）删除清退，其处置由
     * {@code DocumentApplicationService#acceptDelete} 的幂等分支消化，向用户展示会造成
     * 「重复了一个正在删除中的文件」这类语义混乱（参见 
     * {@code }）。若过滤后集合为空，返回值与
     * 「无命中」路径保持一致。</p>
     *
     * @param hits 命中列表（非空）
     * @return 重复摘要，形如「检测到重复文档：文档ID=12「手册.pdf」（命中轴：fileName）」
     */
    public String describeHits(List<DedupHit> hits) {
        List<DedupHit> visibleHits = filterDeletionInFlight(hits);
        if (CollectionUtils.isEmpty(visibleHits)) {
            return "检测到重复文档";
        }
        StringBuilder message = new StringBuilder("检测到重复文档：");
        for (int i = 0; i < visibleHits.size(); i++) {
            DedupHit hit = visibleHits.get(i);
            if (i > 0) {
                message.append("；");
            }
            message.append("文档ID=").append(hit.document().id())
                    .append("「").append(hit.document().fileName()).append("」")
                    .append("（命中轴：").append(String.join("、", hit.matchAxes())).append("）");
        }
        return message.toString();
    }

    /**
     * 过滤掉处于删除中 / 删除失败态的命中项，仅保留可向用户展示的重复命中。
     * <p>判重命中集合本身不按状态过滤（见 {@code DocumentMapper#selectDuplicates} 的 Javadoc），
     * 展示层的这一过滤与 {@code acceptDelete} 幂等受理相配合，各司其职。</p>
     *
     * @param hits 原始命中列表，可为 {@code null} 或空
     * @return 排除 {@code DELETING} / {@code DELETE_FAILED} 后的命中列表；入参为空时返回空列表
     */
    private List<DedupHit> filterDeletionInFlight(List<DedupHit> hits) {
        if (CollectionUtils.isEmpty(hits)) {
            return List.of();
        }
        List<DedupHit> visibleHits = new ArrayList<>(hits.size());
        for (DedupHit hit : hits) {
            DocumentStatus status = ObjectUtils.isEmpty(hit) ? null : hit.document().status();
            if (status == DocumentStatus.DELETING || status == DocumentStatus.DELETE_FAILED) {
                continue;
            }
            visibleHits.add(hit);
        }
        return visibleHits;
    }

    /**
     * 解析判定依据枚举；为空或未知取值返回 {@code null}（触发整体降级）。
     */
    private DedupMatchRule parseMatchRule(String rawValue) {
        if (StringUtils.isBlank(rawValue)) {
            return null;
        }
        return EnumUtils.getEnum(DedupMatchRule.class, rawValue.trim().toUpperCase());
    }

    /**
     * 解析冲突动作枚举；为空或未知取值返回 {@code null}（触发整体降级）。
     */
    private DedupConflictAction parseConflictAction(String rawValue) {
        if (StringUtils.isBlank(rawValue)) {
            return null;
        }
        return EnumUtils.getEnum(DedupConflictAction.class, rawValue.trim().toUpperCase());
    }

    /**
     * 判定当前规则是否需要携带文件名轴。
     */
    private boolean needNameAxis(DedupMatchRule rule) {
        return rule == DedupMatchRule.BY_NAME || rule == DedupMatchRule.BY_NAME_OR_CONTENT;
    }

    /**
     * 判定当前规则是否需要携带内容哈希轴。
     */
    private boolean needHashAxis(DedupMatchRule rule) {
        return rule == DedupMatchRule.BY_CONTENT || rule == DedupMatchRule.BY_NAME_OR_CONTENT;
    }

    /**
     * 计算单条命中文档实际命中的判定轴。
     * <p>逐轴与命中文档自身的一等列取值直接比对（文件名轴取 {@code fileName}、内容哈希轴取
     * {@code fileContentHash}）；若因脏数据导致比对不出任何轴（该行确由仓储查询命中），
     * 则回退为本次实际携带的查询轴，保证摘要至少给出一条可解释的命中依据。</p>
     */
    private List<String> resolveMatchAxes(DedupMatchRule rule, String queryName, String queryHash, Document duplicate) {
        List<String> axes = new ArrayList<>(2);
        if (needNameAxis(rule) && StringUtils.isNotBlank(queryName)
                && StringUtils.equals(queryName, duplicate.fileName())) {
            axes.add(AXIS_FILE_NAME);
        }
        if (needHashAxis(rule) && StringUtils.isNotBlank(queryHash)
                && StringUtils.equals(queryHash, duplicate.fileContentHash())) {
            axes.add(AXIS_CONTENT_HASH);
        }
        if (axes.isEmpty()) {
            if (StringUtils.isNotBlank(queryName)) {
                axes.add(AXIS_FILE_NAME);
            } else if (StringUtils.isNotBlank(queryHash)) {
                axes.add(AXIS_CONTENT_HASH);
            }
        }
        return axes;
    }
}
