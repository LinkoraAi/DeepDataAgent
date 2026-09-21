package com.linkroa.deepdataagent.knowledgebase.application.validation;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.BuiltinEntityType;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 实体类型配置应用级校验器（REST 更新实体类型配置入口使用，仅校验不回写原文）。
 * <p>配置 JSON 结构：{@code {"entityTypes":[{"entityType":"名称"}]}}。校验规则
 * （任一失败即抛 400，错误信息指明具体条目，配置不落库）：</p>
 * <ul>
 *   <li>自定义类型总数不得超过 {@value #MAX_CUSTOM_ENTITY_TYPES} 个；</li>
 *   <li>名称长度 {@value #MIN_ENTITY_TYPE_NAME_LENGTH}~{@value #MAX_ENTITY_TYPE_NAME_LENGTH} 个字符；</li>
 *   <li>名称仅允许中英文字母、数字与下划线；</li>
 *   <li>名称按大小写归一后不得重复；</li>
 *   <li>名称不得与内置 11 类实体（英文代码或中文名）重名；</li>
 *   <li>OTHER（其他）为强制保留类型，禁止出现在可配置集合中。</li>
 * </ul>
 * <p>无外部 BC 依赖、无状态静态工具；校验须在事务开启前完成。</p>
 */
public final class EntityTypeConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(EntityTypeConfigValidator.class);

    /** 实体类型配置 JSON 字段名：类型列表 */
    private static final String FIELD_ENTITY_TYPES = "entityTypes";

    /** 实体类型配置 JSON 字段名：类型名称 */
    private static final String FIELD_ENTITY_TYPE = "entityType";

    /** 自定义实体类型数量上限 */
    private static final int MAX_CUSTOM_ENTITY_TYPES = 10;

    /** 实体类型名称最小长度 */
    private static final int MIN_ENTITY_TYPE_NAME_LENGTH = 2;

    /** 实体类型名称最大长度 */
    private static final int MAX_ENTITY_TYPE_NAME_LENGTH = 30;

    /** 实体类型名称合法字符：中英文字母、数字、下划线 */
    private static final Pattern ENTITY_TYPE_NAME_PATTERN = Pattern.compile("^[\\u4e00-\\u9fa5A-Za-z0-9_]+$");

    /** 实体类型配置 JSON 解析器（无状态，进程内共享） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private EntityTypeConfigValidator() {
    }

    /**
     * 校验实体类型配置 JSON（合法时原样落库，本校验器不做任何回写）。
     *
     * @param entityTypeConfigJson 实体类型配置 JSON 文本，必填
     * @throws DeepDataAgentException 配置为空、非法 JSON 或任一规则不通过（400）
     */
    public static void validate(String entityTypeConfigJson) {
        if (StringUtils.isBlank(entityTypeConfigJson)) {
            throw new DeepDataAgentException("实体类型配置不能为空");
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(entityTypeConfigJson);
        } catch (JacksonException e) {
            log.error("实体类型配置不是合法JSON，原始值={}", entityTypeConfigJson, e);
            throw new DeepDataAgentException("实体类型配置不是合法的JSON");
        }
        if (!root.isObject()) {
            throw new DeepDataAgentException("实体类型配置必须是JSON对象");
        }
        JsonNode typesNode = root.path(FIELD_ENTITY_TYPES);
        if (typesNode.isMissingNode() || !typesNode.isArray()) {
            throw new DeepDataAgentException("实体类型配置格式非法：entityTypes必须是JSON数组");
        }
        if (typesNode.size() > MAX_CUSTOM_ENTITY_TYPES) {
            throw new DeepDataAgentException("自定义实体类型不得超过" + MAX_CUSTOM_ENTITY_TYPES + "个，当前数量="
                    + typesNode.size());
        }
        validateEachEntityType(typesNode);
    }

    /**
     * 逐条校验实体类型条目：名称合法性、内置同名、强制保留、大小写归一防重。
     */
    private static void validateEachEntityType(JsonNode typesNode) {
        Set<String> normalizedNames = new HashSet<>(16);
        for (int i = 0; i < typesNode.size(); i++) {
            JsonNode item = typesNode.get(i);
            if (!item.isObject()) {
                throw new DeepDataAgentException("第" + (i + 1) + "个实体类型条目必须是JSON对象");
            }
            JsonNode nameNode = item.path(FIELD_ENTITY_TYPE);
            String name = nameNode.isTextual() ? nameNode.stringValue() : null;
            if (StringUtils.isBlank(name)) {
                throw new DeepDataAgentException("第" + (i + 1) + "个实体类型条目缺少名称（entityType）");
            }
            validateNameFormat(name, i);
            validateNotBuiltin(name, i);
            if (!normalizedNames.add(name.toLowerCase(Locale.ROOT))) {
                throw new DeepDataAgentException("第" + (i + 1) + "个实体类型名称重复（大小写归一后）：" + name);
            }
        }
    }

    /**
     * 校验名称格式：长度 2~30 个字符，仅允许中英文字母、数字与下划线。
     */
    private static void validateNameFormat(String name, int index) {
        int length = name.length();
        if (length < MIN_ENTITY_TYPE_NAME_LENGTH || length > MAX_ENTITY_TYPE_NAME_LENGTH) {
            throw new DeepDataAgentException("第" + (index + 1) + "个实体类型名称（" + name
                    + "）长度必须在" + MIN_ENTITY_TYPE_NAME_LENGTH + "到" + MAX_ENTITY_TYPE_NAME_LENGTH + "个字符之间");
        }
        if (!ENTITY_TYPE_NAME_PATTERN.matcher(name).matches()) {
            throw new DeepDataAgentException("第" + (index + 1) + "个实体类型名称（" + name
                    + "）仅允许中英文字母、数字与下划线");
        }
    }

    /**
     * 校验名称不与内置类型冲突：OTHER 为强制保留类型禁止配置，其余内置 11 类不得重名。
     */
    private static void validateNotBuiltin(String name, int index) {
        Optional<BuiltinEntityType> builtin = BuiltinEntityType.find(name);
        if (builtin.isEmpty()) {
            return;
        }
        if (builtin.get() == BuiltinEntityType.OTHER) {
            throw new DeepDataAgentException("第" + (index + 1) + "个实体类型名称（" + name
                    + "）为强制保留类型，禁止配置");
        }
        throw new DeepDataAgentException("第" + (index + 1) + "个实体类型名称（" + name
                + "）不得与内置类型重名（内置类型：" + builtin.get().cnName() + "）");
    }
}
