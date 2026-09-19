package com.linkroa.deepdataagent.runtime.domain.model.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * 协调层租约类型（领域枚举，同时承载**租约键的领域派生规则**）。
 *
 * <p>move-coordination-leases-to-redis PR-C4：协调租约已全部迁至 Redis，原 PG 侧
 * {@code CoordLease} 聚合（行形状：id / expires_at / acquired_at）随之删除；键派生
 * （{@code keyOf} / {@code bizIdFromKey}）属领域语义（「哪把锁管哪个对象」），
 * 留在本枚举，应用层与领域层据此构造 / 反解租约键，
 * <b>MUST NOT</b> 改引基础设施层 Redis 物理键构造器（那是存储侧布局，见
 * {@code infrastructure.config.CoordLeaseKeys}）。</p>
 *
 * <ul>
 *   <li>{@link #TURN}：turn 执行租约，会话级独占（同一会话同一时刻仅一个执行持有），
 *       进程崩溃后由启动恢复按 owner 二级索引枚举并回收（租约即执行权，key 在＝有权）；</li>
 *   <li>{@link #FIRE}：调度 fire lease，调度器触发防重（窗口内拒绝重复触发），
 *       触发流程结束后显式 owner-scoped 释放，异常退出由 TTL 兜底。</li>
 * </ul>
 */
public enum CoordLeaseType {

    /** turn 执行租约（会话级独占），键段承载会话 ID。 */
    TURN("turn", "turn:session:", "sessionId"),

    /** 调度触发防重租约（窗口内拒绝重复触发），键段承载调度器（Deployment）业务 ID。 */
    FIRE("fire", "fire:scheduler:", "schedulerId");

    /**
     * 租约键最大长度（防御性上限）。
     *
     * <p>原为 PG 列宽约束（协调租约表 {@code lease_key VARCHAR(128)}），迁至 Redis 后保留为
     * 领域不变量：键同时充当 owner 二级索引成员与日志排查线索，禁止无界业务 ID 拼入。</p>
     */
    public static final int MAX_KEY_LENGTH = 128;

    private final String value;

    /** 租约键前缀（键格式「类型:对象:<业务 ID>」的类型段）。 */
    private final String keyPrefix;

    /** 键段业务 ID 的语义名（仅用于校验失败时的可读报错）。 */
    private final String bizIdName;

    CoordLeaseType(String value, String keyPrefix, String bizIdName) {
        this.value = value;
        this.keyPrefix = keyPrefix;
        this.bizIdName = bizIdName;
    }

    /**
     * 源码小写值域（对外契约 / 日志口径使用该值；Redis 侧物理键前缀另见基础设施层）。
     */
    public String getValue() {
        return value;
    }

    /**
     * 按源码值解析租约类型（大小写不敏感）。
     *
     * @param value 源码值（{@code turn} / {@code fire}）
     * @return 对应枚举
     * @throws IllegalArgumentException 空白或未知值
     */
    public static CoordLeaseType fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("租约类型不能为空");
        }
        for (CoordLeaseType type : values()) {
            if (type.value.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知租约类型: " + value);
    }

    /**
     * 派生本类型的租约键（{@code TURN → turn:session:<sessionId>}、{@code FIRE → fire:scheduler:<schedulerId>}）。
     *
     * @param bizId 业务 ID（会话 ID / 调度器 ID）
     * @return 租约键（存储侧寻址用，跨实现口径一致）
     * @throws IllegalArgumentException 业务 ID 空白或拼出的键超过 {@link #MAX_KEY_LENGTH}
     */
    public String keyOf(String bizId) {
        requireBizId(bizId);
        String leaseKey = keyPrefix + bizId;
        if (leaseKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("租约键长度不能超过 " + MAX_KEY_LENGTH);
        }
        return leaseKey;
    }

    /**
     * 从租约键反解业务 ID（启动恢复据此把 turn 租约映射回会话）。
     *
     * @param leaseKey 租约键（须以本类型前缀开头）
     * @return 业务 ID（{@link #TURN} 为会话 ID，{@link #FIRE} 为调度器 ID）
     * @throws IllegalArgumentException 键空白、前缀与本类型不符，或键段业务 ID 为空
     */
    public String bizIdFromKey(String leaseKey) {
        if (StringUtils.isBlank(leaseKey) || !leaseKey.startsWith(keyPrefix)) {
            throw new IllegalArgumentException("非法 " + value + " 租约键: " + leaseKey);
        }
        String bizId = leaseKey.substring(keyPrefix.length());
        requireBizId(bizId);
        return bizId;
    }

    /** 业务 ID 段校验（非空白；长度上限在拼键后统一校验）。 */
    private void requireBizId(String bizId) {
        if (StringUtils.isBlank(bizId)) {
            throw new IllegalArgumentException(bizIdName + " 不能为空");
        }
    }
}
