package com.linkroa.deepdataagent.runtime.application.validation;

import com.linkroa.deepdataagent.file.api.FileApi;
import com.linkroa.deepdataagent.runtime.application.command.InboundEventDraft;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 入站请求校验器（应用级手工校验，协议入站契约的 400 / 404 语义权威）。
 * <p>承载入站事件批次的结构校验，由 {@code InboundEventService} 在<b>方法体首行</b>
 * （{@code requireOwnedSession} 之前）调用，以保持「载荷类 400 先于会话不存在」的对外优先序：</p>
 * <ul>
 *   <li>content blocks 强校验：text 块文本非空白；image 块三 source
 *       （{@code base64} / {@code url} / {@code file}，{@code file} 经 {@link FileApi} 校验
 *       存在 + 归属 + ready，失败统一 404，不泄露文件存在性）、每条消息 ≤100 图、
 *       单图 base64 文本 ≤10 MiB、显式宽高 ≤8000px；</li>
 *   <li>{@code system.message} 公开入站批规则：每批至多一条、必须批尾、必须紧跟
 *       {@code user.message} / {@code user.tool_result} / {@code user.custom_tool_result}，
 *       content 仅非空 text 块；</li>
 *   <li>{@code user.tool_confirmation} 形状与 {@code deny_message} 规则（仅 result=deny 允许）；</li>
 *   <li>{@code user.tool_result} / {@code user.custom_tool_result}：定位键必填，
 *       content 接受 text/image/document/search_result 且允许空数组；</li>
 *   <li>追加挂载批次校验（空批次拒绝，整批全有或全无）。</li>
 * </ul>
 * <p>事件类型名解析（{@link #parseKnownType}）供接口层装配草案时复用：非权威事件表成员即
 * {@code unknown_event_type}（400 语义）——{@code user.define_outcome} 已移出白名单，
 * 因此天然按未知类型 400 拒收。入参一律为已归一的类型化对象或基础类型，
 * MUST NOT 接收 {@code controller.request} 类型（否则 application→controller 反向依赖只是换包未消除）。</p>
 */
@Component
public class InboundEventValidator {

    /** 每条消息允许的最大 image 块数量。 */
    private static final int MAX_IMAGE_BLOCKS = 100;

    /** 单图 base64 文本上限（10 MiB）。 */
    private static final int MAX_BASE64_CHARS = 10 * 1024 * 1024;

    /** 单图显式宽 / 高上限（像素）。 */
    private static final int MAX_IMAGE_DIMENSION = 8000;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** image 块允许的 source 形态。 */
    private static final Set<String> IMAGE_SOURCE_TYPES = Set.of("base64", "url", "file");

    /** user.message 允许的 content block 类型。 */
    private static final Set<String> MESSAGE_BLOCK_TYPES = Set.of("text", "image");

    /** tool_result 类事件允许的 content block 类型。 */
    private static final Set<String> TOOL_RESULT_BLOCK_TYPES =
            Set.of("text", "image", "document", "search_result");

    /** 文件服务契约：image {@code file} source 的存在性 / 归属 / ready 门禁（跨 BC 只读查询）。 */
    @Resource
    private FileApi fileApi;

    /**
     * 事件类型解析：非权威事件表成员抛 {@code unknown_event_type}（400 语义）。
     *
     * @param type 请求携带的事件类型原值
     * @return 权威事件表成员
     */
    public static ChatEventType parseKnownType(String type) {
        if (!ChatEventType.isKnown(type)) {
            throw new DeepDataAgentException("unknown_event_type: 未知事件类型 " + type);
        }
        return ChatEventType.fromValue(type);
    }

    /**
     * 追加挂载批次校验：空批次拒绝（400 语义，整批无部分变更）。
     * <p>单项字段与类型不变量由 {@link SessionResource} 工厂紧凑构造器兜底（装配阶段即拒绝）；
     * 追加路径仅允许 file 的门禁属会话聚合规则，仍在应用服务承担。</p>
     *
     * @param resources 已归一、已装配的挂载资源值对象列表
     */
    public static void validateAppendResourceBatch(List<SessionResource> resources) {
        if (resources == null || resources.isEmpty()) {
            throw new IllegalArgumentException("追加挂载资源不能为空");
        }
    }

    // ==================== 入站批次结构校验（公开事件入站契约） ====================

    /**
     * 入站事件批次校验（批量全有或全无：任一事件非法即整批 400，零部分落库）。
     *
     * @param drafts  已归一的入站事件草案（按到达顺序，可空 = 无事件直接通过）
     * @param ownerId 当前认证用户数字 ID（image {@code file} source 的归属门禁）
     */
    public void validateInboundBatch(List<InboundEventDraft> drafts, Long ownerId) {
        if (drafts == null || drafts.isEmpty()) {
            return;
        }
        for (InboundEventDraft draft : drafts) {
            validateInboundPayload(draft.type(), readPayload(draft.payloadJson()), ownerId);
        }
        validateSystemMessageBatch(drafts);
    }

    /**
     * 按事件类型校验入站 payload 结构。
     */
    private void validateInboundPayload(ChatEventType type, Map<String, Object> payload, Long ownerId) {
        switch (type) {
            case USER_MESSAGE -> validateMessageContent(payload, ownerId);
            case SYSTEM_MESSAGE -> validateSystemMessageContent(payload);
            case USER_TOOL_CONFIRMATION -> requireToolConfirmation(payload);
            case USER_TOOL_RESULT -> requireToolResult(payload, "tool_use_id", ownerId);
            case USER_CUSTOM_TOOL_RESULT -> requireToolResult(payload, "custom_tool_use_id", ownerId);
            default -> {
                // user.interrupt 的 session_thread_id 可选、无附加结构约束；其余入站类型无附加约束
            }
        }
    }

    /** user.message：content MUST 为非空 content block 数组（纯字符串 / 缺省 / 空数组被拒）。 */
    private void validateMessageContent(Map<String, Object> payload, Long ownerId) {
        Object content = payload.get("content");
        if (!(content instanceof List<?> blocks) || blocks.isEmpty()) {
            throw validationError("user.message 的 content MUST 为非空 content block 数组");
        }
        validateBlocks(blocks, "user.message 的 content", MESSAGE_BLOCK_TYPES, ownerId);
    }

    /** system.message：content MUST 为非空 text content block 数组（仅 text 块）。 */
    private void validateSystemMessageContent(Map<String, Object> payload) {
        Object content = payload.get("content");
        if (!(content instanceof List<?> blocks) || blocks.isEmpty()) {
            throw validationError("system.message 的 content MUST 为非空 text content block 数组");
        }
        validateBlocks(blocks, "system.message 的 content", Set.of("text"), null);
    }

    /** user.tool_confirmation：tool_use_id 必填；result ∈ allow|deny；deny_message 仅 result=deny 时合法。 */
    private void requireToolConfirmation(Map<String, Object> payload) {
        requireNonBlankString(payload, "tool_use_id", "user.tool_confirmation");
        Object result = payload.get("result");
        if (!"allow".equals(result) && !"deny".equals(result)) {
            throw validationError("user.tool_confirmation 的 result 必须为 allow 或 deny");
        }
        if (payload.containsKey("deny_message")) {
            if (!"deny".equals(result)) {
                throw validationError("deny_message 仅在 result=deny 时合法");
            }
            requireNonBlankString(payload, "deny_message", "user.tool_confirmation");
        }
    }

    /** user.tool_result / user.custom_tool_result：定位键必填；可选 content blocks 数组（允许空）与 is_error 布尔。 */
    private void requireToolResult(Map<String, Object> payload, String idKey, Long ownerId) {
        requireNonBlankString(payload, idKey, "user 工具结果事件");
        Object content = payload.get("content");
        if (content != null) {
            if (!(content instanceof List<?> blocks)) {
                throw validationError(idKey + " 事件的 content 若出现必须为 content block 数组");
            }
            validateBlocks(blocks, idKey + " 事件的 content", TOOL_RESULT_BLOCK_TYPES, ownerId);
        }
        if (payload.get("is_error") != null && !(payload.get("is_error") instanceof Boolean)) {
            throw validationError(idKey + " 事件的 is_error 必须为布尔值");
        }
    }

    /**
     * system.message 批规则：每批至多一条、必须位于批次最后、必须紧跟
     * {@code user.message} / {@code user.tool_result} / {@code user.custom_tool_result}。
     */
    private void validateSystemMessageBatch(List<InboundEventDraft> drafts) {
        int count = 0;
        for (int i = 0; i < drafts.size(); i++) {
            if (drafts.get(i).type() != ChatEventType.SYSTEM_MESSAGE) {
                continue;
            }
            if (++count > 1) {
                throw validationError("每批至多允许一条 system.message");
            }
            if (i != drafts.size() - 1) {
                throw validationError("system.message 必须位于批次最后");
            }
            if (i == 0 || !isSystemMessagePredecessor(drafts.get(i - 1).type())) {
                throw validationError(
                        "system.message 必须紧跟 user.message / user.tool_result / user.custom_tool_result");
            }
        }
    }

    /** system.message 允许的前驱事件类型（user.message / user.tool_result / user.custom_tool_result）。 */
    private static boolean isSystemMessagePredecessor(ChatEventType type) {
        return type == ChatEventType.USER_MESSAGE
                || type == ChatEventType.USER_TOOL_RESULT
                || type == ChatEventType.USER_CUSTOM_TOOL_RESULT;
    }

    // ==================== content block 强校验 ====================

    /**
     * content block 数组强校验：块类型受 {@code allowedTypes} 约束，text 正文非空白，
     * image 三 source 与数量 / 体积 / 尺寸上限。
     */
    private void validateBlocks(List<?> blocks, String subject, Set<String> allowedTypes, Long ownerId) {
        int imageCount = 0;
        for (Object raw : blocks) {
            if (!(raw instanceof Map<?, ?> map)) {
                throw validationError(subject + " 的数组元素必须为 content block 对象");
            }
            Map<String, Object> block = asStringKeyedMap(map);
            Object typeValue = block.get("type");
            if (!(typeValue instanceof String blockType) || blockType.isBlank()) {
                throw validationError(subject + " 的 content block 必须携带非空 type");
            }
            if (!allowedTypes.contains(blockType)) {
                throw validationError(subject + " 不接受 " + blockType + " 类型的 content block");
            }
            switch (blockType) {
                case "text" -> requireNonBlankText(block, subject);
                case "image" -> {
                    imageCount++;
                    validateImageBlock(block, subject, ownerId);
                }
                // document / search_result 本期无附加内部结构约束（契约允许词形）
                default -> {
                }
            }
        }
        if (imageCount > MAX_IMAGE_BLOCKS) {
            throw validationError(subject + " 的 image 块数量 MUST NOT 超过 " + MAX_IMAGE_BLOCKS);
        }
    }

    /** image 块：source 三形态（base64 / url / file）+ 单图体积与显式尺寸上限。 */
    private void validateImageBlock(Map<String, Object> block, String subject, Long ownerId) {
        Object sourceValue = block.get("source");
        if (!(sourceValue instanceof Map<?, ?> sourceMap)) {
            throw validationError(subject + " 的 image 块必须携带 source 对象");
        }
        Map<String, Object> source = asStringKeyedMap(sourceMap);
        Object typeValue = source.get("type");
        if (!(typeValue instanceof String sourceType) || !IMAGE_SOURCE_TYPES.contains(sourceType)) {
            throw validationError("image source.type 仅允许 base64 / url / file");
        }
        switch (sourceType) {
            case "base64" -> {
                requireNonBlankString(source, "media_type", "image base64 source");
                String data = requireNonBlankString(source, "data", "image base64 source");
                if (data.length() > MAX_BASE64_CHARS) {
                    throw validationError("单图 base64 文本 MUST NOT 超过 10 MiB");
                }
            }
            case "url" -> {
                String url = requireNonBlankString(source, "url", "image url source");
                if (!url.startsWith("https://")) {
                    throw validationError("image url source 必须为外部 HTTPS 地址");
                }
            }
            case "file" -> {
                String fileId = requireNonBlankString(source, "file_id", "image file source");
                if (ownerId == null || !fileApi.readyForMount(fileId, ownerId)) {
                    // 不存在 / 越权 / 未就绪统一 404，不泄露文件存在性（FileApi 同一门禁语义）
                    throw new ResourceNotFoundException("引用文件不存在或无权访问: " + fileId);
                }
            }
            default -> throw validationError("image source.type 仅允许 base64 / url / file");
        }
        requireDimensionWithinLimit(block, "width", subject);
        requireDimensionWithinLimit(block, "height", subject);
    }

    /** 显式 width / height 若出现必须为正整数且 ≤8000px。 */
    private static void requireDimensionWithinLimit(Map<String, Object> block, String field, String subject) {
        Object value = block.get(field);
        if (value == null) {
            return;
        }
        if (!(value instanceof Number number) || number.longValue() <= 0 || number.longValue() > MAX_IMAGE_DIMENSION) {
            throw validationError(subject + " 的 image " + field + " MUST 为 1-" + MAX_IMAGE_DIMENSION + " 的整数");
        }
    }

    /** text 块：{@code text} MUST 为非空白字符串。 */
    private static void requireNonBlankText(Map<String, Object> block, String subject) {
        Object text = block.get("text");
        if (!(text instanceof String value) || value.isBlank()) {
            throw validationError(subject + " 的 text block 必须携带非空白 text");
        }
    }

    // ==================== 通用小件 ====================

    /** payload JSON 文本 → 待校验对象（空白 / 非对象收敛为空对象，与序列化前的空载荷等价）。 */
    private static Map<String, Object> readPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = OBJECT_MAPPER.readValue(payloadJson, Object.class);
            return parsed instanceof Map<?, ?> map ? asStringKeyedMap(map) : Map.of();
        } catch (JacksonException | IllegalArgumentException ex) {
            throw validationError("事件 payload 必须为合法 JSON 对象");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyedMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /** 必填非空字符串字段校验（返回其原值供后续长度 / 前缀判定）。 */
    private static String requireNonBlankString(Map<String, Object> payload, String field, String subject) {
        if (!(payload.get(field) instanceof String value) || value.isBlank()) {
            throw validationError(subject + " 必须携带非空 " + field);
        }
        return value;
    }

    /** 入站 payload 结构校验失败（接口层 400 语义）。 */
    private static DeepDataAgentException validationError(String message) {
        return new DeepDataAgentException("validation_error: " + message);
    }
}