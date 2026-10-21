package com.linkroa.deepdataagent.agent.application.validation;

import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelProfileStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.apache.commons.lang3.ObjectUtils;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模型配置应用级校验器（存在性 / 状态 / 类型槽位 / 变更守卫）。
 */
public class ModelProfileValidator {

    /** 智能体模型槽位可接受的类型集合（视觉对话模型具备对话能力，允许作为智能体大脑）。 */
    public static final Set<ModelType> AGENT_MODEL_TYPES = Set.of(ModelType.CHAT_LLM, ModelType.CHAT_VLM);

    /**
     * 校验删除冲突：仍被未删除的 Agent 版本引用时不可删除
     *
     * @param profile  目标配置
     * @param refCount 引用数量
     */
    public static void validateDelete(ModelProfile profile, long refCount) {
        if (refCount > 0) {
            throw new ResourceConflictException("模型配置「" + profile.displayName()
                    + "」仍被 " + refCount + " 个 Agent 版本引用，无法删除");
        }
    }

    /**
     * 校验引用合法性：配置存在且状态为 ENABLED，且类型落在槽位期望集合内。
     *
     * @param profile       被引用配置（可空）
     * @param expectedTypes 槽位期望类型集合；空集表示不校验类型
     */
    public static void validateReferable(ModelProfile profile, Set<ModelType> expectedTypes) {
        if (profile == null) {
            throw new ResourceNotFoundException("模型配置不存在");
        }
        if (profile.status() == ModelProfileStatus.DISABLED) {
            throw new ResourceNotFoundException("模型配置已禁用，不可被新引用");
        }
        validateType(profile, expectedTypes);
    }

    /**
     * 校验配置类型是否落在槽位期望集合内（仅类型，不含存在性与状态）。
     * <p>调用方 MUST 先完成归属校验：类型不符为 400，若先于归属判定执行会泄露他人资源存在性。</p>
     *
     * @param profile       被引用配置
     * @param expectedTypes 槽位期望类型集合；空集表示不校验类型
     * @throws DeepDataAgentException 类型不符（400，消息含期望与实际类型）
     */
    public static void validateType(ModelProfile profile, Set<ModelType> expectedTypes) {
        if (ObjectUtils.isEmpty(expectedTypes)) {
            return;
        }
        if (!expectedTypes.contains(profile.modelType())) {
            throw new DeepDataAgentException("模型配置「" + profile.displayName() + "」类型为"
                    + describe(profile.modelType()) + "，该槽位仅接受 " + describe(expectedTypes)
                    + "，请改选对应类型的模型");
        }
    }

    /**
     * 校验类型变更：类型发生变化且仍被 Agent 版本引用时拒绝（409）。
     *
     * @param existing  变更前的配置
     * @param newType   变更后的类型
     * @param refCount  引用的 Agent 版本数量
     * @throws ResourceConflictException 类型变化且存在引用
     */
    public static void validateTypeChange(ModelProfile existing, ModelType newType, long refCount) {
        if (Objects.equals(existing.modelType(), newType)) {
            return;
        }
        if (refCount > 0) {
            throw new ResourceConflictException("模型配置「" + existing.displayName()
                    + "」仍被 " + refCount + " 个 Agent 版本引用，不可变更模型类型");
        }
    }

    /**
     * 类型集合转可读文案（如「对话LLM(1) / 视觉对话(2)」）。
     */
    private static String describe(Set<ModelType> types) {
        return types.stream().map(ModelProfileValidator::describe).collect(Collectors.joining(" 或 "));
    }

    /**
     * 单个类型转可读文案（如「视觉对话(2)」）。
     */
    private static String describe(ModelType type) {
        return type == null ? "未知类型" : type.getLabel() + "(" + type.getCode() + ")";
    }
}
