package com.linkroa.deepdataagent.file.domain.model.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * 文件用途领域枚举（purpose 五态，上传时必填）。
 * 每个用途对应「谁创建 + 可否经 /content 下载」——downloadable 由用途派生、
 * 不接受客户端指定，仅 {@code tool_output} / {@code skill_output} 可下载，
 * 其余用途仅供 Agent 内部使用，请求 /content 返回 403。</p>
 */
public enum FilePurpose {

    /**
     * 用户上传的输入文件（创建方：用户）。经 Session Resources API 挂载后作为
     * Agent 的任务上下文（代码仓库、配置文件、参考文档等），Agent 可读取；
     * 出于安全约定原始上传件不可经 /content 取回——需要导出时 Agent 会以
     * tool_output / skill_output 产出新文件
     */
    USER_UPLOAD("user_upload", false),

    /**
     * 工具执行产出的文件（创建方：Agent/工具）。Agent 向用户导出结果的两条
     * 下载通道之一，可经 /content 下载
     */
    TOOL_OUTPUT("tool_output", true),

    /**
     * Skill 执行产出的文件（创建方：Agent/Skill）。与 tool_output 同为
     * 可下载的产出通道。裁定口径（enforce-tool-visibility-execution D5）：
     * 登记通道已参数化就绪，当前交付工具（契约名 {@code DeliverArtifacts}、运行时实名
     * {@code deliver_artifact}）恒登记 tool_output，
     * skill_output 生产方待 Skill 独立产出能力接入时启用
     */
    SKILL_OUTPUT("skill_output", true),

    /**
     * Session 级别的资源文件（创建方：用户/系统）。随 Session 生命周期管理、
     * 可挂载供 Agent 读取，内部使用不可下载
     */
    SESSION_RESOURCE("session_resource", false),

    /**
     * Agent 最终输出文件（创建方：Agent）。公开契约中的终稿用途，不可下载
     * （正文已由事件表承载）。裁定口径（enforce-tool-visibility-execution D5）：
     * 沙箱产物交付统一登记为 tool_output（见 register-sandbox-artifacts 裁定）；
     * 本值非欠账——登记通道已参数化就绪，agent_output 生产方待未来出现
     * 非交付工具产出的运行时能力时自然接入
     */
    AGENT_OUTPUT("agent_output", false);

    /** 契约词汇（API 传输值） */
    private final String code;

    /** 由用途派生的可下载标记 */
    private final boolean downloadable;

    FilePurpose(String code, boolean downloadable) {
        this.code = code;
        this.downloadable = downloadable;
    }

    /**
     * 返回 API 传输层使用的用途词汇（契约取值，非 Java 枚举名）。
     *
     * @return 契约词汇
     */
    public String code() {
        return code;
    }

    /**
     * 该用途是否允许经 {@code /content} 下载（由用途派生，不接受客户端指定）。
     *
     * @return true 表示可下载
     */
    public boolean downloadable() {
        return downloadable;
    }

    /**
     * 按契约词汇解析用途枚举。
     *
     * @param code 用途字符串（user_upload / tool_output / skill_output / session_resource / agent_output）
     * @return 匹配的枚举
     * @throws IllegalArgumentException 空值或非法用途（400 语义）
     */
    public static FilePurpose fromCode(String code) {
        if (StringUtils.isBlank(code)) {
            throw new IllegalArgumentException("文件用途(purpose)不能为空");
        }
        for (FilePurpose purpose : values()) {
            if (purpose.code.equals(code)) {
                return purpose;
            }
        }
        throw new IllegalArgumentException("文件用途非法: " + code);
    }
}
