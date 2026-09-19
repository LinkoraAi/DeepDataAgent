package com.linkroa.deepdataagent.agent.application.convert;

import com.linkroa.deepdataagent.agent.application.command.CreateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.application.query.ListEnvironmentQuery;
import com.linkroa.deepdataagent.agent.controller.request.CreateEnvironmentRequest;
import com.linkroa.deepdataagent.agent.controller.request.EnvironmentConfigRequest;
import com.linkroa.deepdataagent.agent.controller.request.UpdateEnvironmentRequest;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentConfig;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentPackages;
import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运行环境请求转换器（Request → Command）。
 * <p>config 结构化装配：整体缺省 / type 空白收敛为 {@code {"type":"cloud"}}；
 * packages 六类透传至值对象（条目格式与版本限定校验由值对象承载）；
 * metadata 对象序列化为 JSON 文本（领域 / 持久化统一 JSON 字符串形状）。
 * 非法类型与规格抛 {@link IllegalArgumentException}（协议层收敛为 400 校验错误）。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface EnvironmentCommandConvert {

    EnvironmentCommandConvert INSTANCE = Mappers.getMapper(EnvironmentCommandConvert.class);

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 请求 packages 允许的键集合（公开契约仅接受 apt / pip / npm；cargo/gem/go 为响应保留字段）。 */
    Set<String> ALLOWED_PACKAGE_KEYS = Set.of("apt", "pip", "npm");

    default CreateEnvironmentCommand toCreateCommand(CreateEnvironmentRequest request) {
        return new CreateEnvironmentCommand(
                request.name(),
                request.description(),
                toConfig(request.config()),
                toMetadataJson(request.metadata())
        );
    }

    default UpdateEnvironmentCommand toUpdateCommand(String environmentId, UpdateEnvironmentRequest request) {
        return new UpdateEnvironmentCommand(
                environmentId,
                request.name(),
                request.description(),
                toConfig(request.config()),
                toMetadataJson(request.metadata())
        );
    }

    /** 请求 config → 领域值对象（null 收敛为缺省云配置；type 空白同样缺省 cloud）。 */
    default EnvironmentConfig toConfig(EnvironmentConfigRequest request) {
        if (request == null) {
            return EnvironmentConfig.cloudDefault();
        }
        EnvironmentType type = StringUtils.isBlank(request.type())
                ? EnvironmentType.CLOUD
                : EnvironmentType.fromValue(request.type());
        return new EnvironmentConfig(type, toPackages(request.packages()), request.setup_script());
    }

    /**
     * 请求 packages 对象映射 → 值对象（null / 空对象收敛为六类全空）。
     * <p>形状判定：MUST 为对象映射且键集合仅 {@code apt}/{@code pip}/{@code npm}，
     * 出现 {@code cargo}/{@code gem}/{@code go}/{@code type} 或任何其他键一律 400
     * （不静默忽略）；键值 MUST 为字符串数组（数组形态 / 平铺字段同样拒绝）。
     * cargo/gem/go 为公开契约的响应保留字段，不参与装配，恒为空列表。</p>
     */
    default EnvironmentPackages toPackages(Map<String, Object> packages) {
        if (packages == null || packages.isEmpty()) {
            return EnvironmentPackages.empty();
        }
        for (String key : packages.keySet()) {
            if (!ALLOWED_PACKAGE_KEYS.contains(key)) {
                throw new IllegalArgumentException("config.packages 仅接受 apt/pip/npm 三键，不支持: " + key);
            }
        }
        return new EnvironmentPackages(
                stringEntries(packages, "apt"),
                List.of(),
                List.of(),
                List.of(),
                stringEntries(packages, "npm"),
                stringEntries(packages, "pip"));
    }

    /** 取单键的字符串数组（缺失 → 空列表；非字符串数组 → 400）。 */
    private List<String> stringEntries(Map<String, Object> packages, String key) {
        if (!packages.containsKey(key)) {
            return List.of();
        }
        Object value = packages.get(key);
        if (!(value instanceof List<?> entries)) {
            throw new IllegalArgumentException("config.packages." + key + " 必须为字符串数组");
        }
        List<String> values = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (!(entry instanceof String text)) {
                throw new IllegalArgumentException("config.packages." + key + " 必须为字符串数组");
            }
            values.add(text);
        }
        return values;
    }

    /** metadata 对象 → JSON 文本（null 收敛为 {@code "{}"}）。 */
    default String toMetadataJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("metadata 序列化失败", e);
        }
    }

    /**
     * 游标列表 HTTP 原始参数 → 查询对象（6.6 管理面 Cursor 约定）。
     * <p>{@code metadata} 为 JSON 对象文本（JSONB 包含过滤，非对象 → 400，空白=不过滤）；
     * {@code created_at[gte]/[lte]} 为 ISO-8601 带时区时间文本（空白=不过滤，非法 → 400）。</p>
     */
    default ListEnvironmentQuery toListQuery(Long ownerId, String metadata,
                                             String createdAfter, String createdBefore,
                                             String limit, String afterId, String beforeId) {
        return new ListEnvironmentQuery(
                ownerId,
                parseMetadataFilter(metadata),
                parseTime(createdAfter, "created_at[gte]"),
                parseTime(createdBefore, "created_at[lte]"),
                CursorPageParams.parse(limit, afterId, beforeId)
        );
    }

    /** metadata 过滤参数校验（必须为 JSON 对象文本；返回紧凑文本供 JSONB {@code @>} 判定）。 */
    private static String parseMetadataFilter(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            var node = OBJECT_MAPPER.readTree(raw.trim());
            if (!node.isObject()) {
                throw new IllegalArgumentException("metadata 过滤必须为 JSON 对象: " + raw);
            }
            return node.toString();
        } catch (JacksonException e) {
            throw new IllegalArgumentException("metadata 过滤必须为 JSON 对象: " + raw, e);
        }
    }

    /** ISO-8601 时间参数解析（空白=不过滤，非法 → 400）。 */
    private static OffsetDateTime parseTime(String raw, String paramName) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(paramName + " 必须为 ISO-8601 时间: " + raw);
        }
    }
}
