package com.linkroa.deepdataagent.knowledgebase.application.validation;

import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.KbLanguage;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.KnowledgeBaseSortField;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 知识库应用级校验器。
 * <p>承载跨聚合与状态机层面的业务规则校验，校验失败抛出对应 HTTP 语义的异常：
 * 名称重复 → 409，状态不可操作 → 400。</p>
 */
public final class KnowledgeBaseValidator {

    /** 知识库名称最大长度 */
    private static final int NAME_MAX_LENGTH = 128;

    /** 排序方向参数取值：升序 */
    private static final String SORT_ORDER_ASC = "asc";

    /** 排序方向参数取值：降序 */
    private static final String SORT_ORDER_DESC = "desc";

    private KnowledgeBaseValidator() {
    }

    /**
     * 校验知识库名称合法性与全局唯一性。
     *
     * @param name   待校验的知识库名称
     * @param exists 同名知识库查询结果（创建时应为空；更新时由调用方排除自身后再传入）
     * @throws DeepDataAgentException    名称为空或超长（400）
     * @throws ResourceConflictException 名称已被占用（409）
     */
    public static void validateNameUnique(String name, Optional<KnowledgeBase> exists) {
        if (StringUtils.isBlank(name)) {
            throw new DeepDataAgentException("知识库名称不能为空");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new DeepDataAgentException("知识库名称长度不能超过" + NAME_MAX_LENGTH + "个字符");
        }
        if (ObjectUtils.isEmpty(exists)) {
            return;
        }
        exists.ifPresent(occupied -> {
            throw new ResourceConflictException("知识库名称「" + name + "」已存在");
        });
    }

    /**
     * 校验知识库语言入参（语言真相源收敛回
     * {@code knowledge_base.language} 列，校验语义自原 {@code RagEngineConfigValidator} 的
     * language 键校验迁移而来）。
     * <p>口径与原键值校验一致：空白 = 未配置放行（创建缺省落 Chinese、更新保持原值）；
     * 命中 {@link KbLanguage} 十一全名值域（trim、大小写不敏感）放行，原文落库、读取端归一；
     * 值域外（含历史裸语言码 {@code ja} 与未收录语言 {@code Portuguese}）拒绝并提示全部合法值域，
     * 禁止静默回落。</p>
     *
     * @param language 语言入参，可为空白（未配置）
     * @throws DeepDataAgentException 语言值域外（400）
     */
    public static void validateLanguage(String language) {
        if (StringUtils.isBlank(language)) {
            return;
        }
        if (KbLanguage.find(language).isEmpty()) {
            String legalValues = Arrays.stream(KbLanguage.values())
                    .map(KbLanguage::name)
                    .collect(Collectors.joining(", "));
            throw new DeepDataAgentException("知识库语言配置非法：当前值=" + language
                    + "，合法值为 11 个语言全名（" + legalValues + "），缺省视同 Chinese");
        }
    }

    /**
     * 校验知识库处于可操作状态（ACTIVE），已进入删除流程的库禁止读写。
     *
     * @param knowledgeBase 目标知识库
     * @throws DeepDataAgentException 知识库正在删除中或已删除（400）
     */
    public static void validateNotDeleting(KnowledgeBase knowledgeBase) {
        if (ObjectUtils.isEmpty(knowledgeBase) || !knowledgeBase.isActive()) {
            throw new DeepDataAgentException("知识库正在删除中或已删除，禁止该操作");
        }
    }

    /**
     * 解析列表排序字段：空白时回退默认字段（创建时间），白名单外取值直接拒绝而非静默忽略。
     *
     * @param sortBy 排序字段参数（name/createdAt/updatedAt，忽略大小写），可为空
     * @return 排序字段枚举，永不为空
     * @throws DeepDataAgentException 排序字段不在白名单内（400）
     */
    public static KnowledgeBaseSortField parseSortField(String sortBy) {
        if (StringUtils.isBlank(sortBy)) {
            return KnowledgeBaseSortField.CREATED_AT;
        }
        return KnowledgeBaseSortField.fromCode(sortBy)
                .orElseThrow(() -> new DeepDataAgentException(
                        "不支持的排序字段：" + sortBy + "（允许值：name/createdAt/updatedAt）"));
    }

    /**
     * 解析列表排序方向：空白时回退倒序，白名单外取值直接拒绝而非静默忽略。
     *
     * @param sortOrder 排序方向参数（asc/desc，忽略大小写），可为空
     * @return true 表示升序，false 表示倒序
     * @throws DeepDataAgentException 排序方向不在白名单内（400）
     */
    public static boolean parseAscendingSortOrder(String sortOrder) {
        if (StringUtils.isBlank(sortOrder)) {
            return false;
        }
        String normalized = sortOrder.trim();
        if (StringUtils.equalsIgnoreCase(SORT_ORDER_ASC, normalized)) {
            return true;
        }
        if (StringUtils.equalsIgnoreCase(SORT_ORDER_DESC, normalized)) {
            return false;
        }
        throw new DeepDataAgentException("不支持的排序方向：" + sortOrder + "（允许值：asc/desc）");
    }
}
