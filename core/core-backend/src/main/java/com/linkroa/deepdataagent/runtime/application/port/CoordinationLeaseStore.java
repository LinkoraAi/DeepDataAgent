package com.linkroa.deepdataagent.runtime.application.port;

import com.linkroa.deepdataagent.runtime.domain.model.enums.CoordLeaseType;

import java.time.Duration;
import java.util.List;

/**
 * 协调层租约存储出站端口（move-coordination-leases-to-redis D3，七方法定稿面）。
 *
 * <p>依赖倒置：{@code CoordinationLeaseService} 经本端口读写两类协调租约，具体存储介质
 * 自 move-coordination-leases-to-redis PR-C4 起<b>唯一</b>为 Redis（owner-scoped Lua 原子裁决
 * + 服务端 TTL 自过期 + owner 二级索引），原 PG 实现与二选一装配开关已下线。方法面逐条对齐
 * 迁移前的仓储 / 服务语义，<b>不增不减</b>：</p>
 *
 * <p>端口面<b>不含</b> {@code deleteExpired} 与无条件 {@code release}：前者随 Redis 服务端 TTL
 * 自过期整体退役（design D3-1，无需任何清扫语句），后者在前置
 * {@code remove-interrupt-lease-ticket} 删除中断票后零调用者（design D3-2）。</p>
 *
 * <p><b>续约失败二分类（design D5，落在调用方语义）</b>：{@link #renew} 返回 {@code false}
 * 即确证失权（Lua 返回 0）——调用方 MUST 立即 fail-closed；连接 / 命令
 * 异常则以运行时异常抛出，调用方据异常与 {@code false} 区分「确证失权」与「瞬断容忍重试」。</p>
 *
 * <p><b>通道划分</b>：通用四方法（{@link #tryAcquire}/{@link #renew}/{@link #releaseOwned}/
 * {@link #findActive}）只承载 {@code TURN} 租约（fire 无续约、无 owner 二级索引、TTL 为语义常量），
 * 实现收到非 TURN 类型 MUST 快速失败；fire 走 {@link #fireTryAcquire}/{@link #fireReleaseOwned}。</p>
 */
public interface CoordinationLeaseStore {

    /**
     * 抢占式获取租约（等价现状 {@code tryAcquire}）：同键无有效租约、或既有租约已过期时写入 /
     * 接管成功，并发下单赢家。
     *
     * @param leaseType 租约类型（TURN / FIRE）
     * @param leaseKey  租约键（{@code CoordLeaseType.keyOf} 派生的业务键形态）
     * @param owner     持有者实例标识
     * @param ttl       存活时长（正数）
     * @return true=获取成功；false=同键存在未过期租约（并发拒绝）
     */
    boolean tryAcquire(CoordLeaseType leaseType, String leaseKey, String owner, Duration ttl);

    /**
     * owner-scoped 续约（等价现状 {@code renew}）：仅当租约仍由指定持有者持有且未过期时生效。
     *
     * @return true=续约成功；false=确证失权（不存在 / 已过期 / 已易主，调用方立即 fail-closed）
     * @throws RuntimeException 存储连接 / 命令异常（调用方据此区分瞬断容忍，MUST NOT 误判失权）
     */
    boolean renew(CoordLeaseType leaseType, String leaseKey, String owner, Duration ttl);

    /**
     * owner-scoped 释放（等价现状 {@code releaseOwned}）：仅删除仍由该持有者持有的租约，
     * 迟到释放不误摘接管者（审查修复 F11）。
     *
     * @return true=本次删除生效；false=不存在 / 已易主
     */
    boolean releaseOwned(CoordLeaseType leaseType, String leaseKey, String owner);

    /**
     * 只读存在性（等价现状 {@code findActive}）：是否存在指定键的<b>有效</b>（未过期）租约。
     * 启动恢复兜底复位据此跳过他实例在跑的会话。
     */
    boolean findActive(CoordLeaseType leaseType, String leaseKey);

    /**
     * 列出本实例持有的有效 turn 租约键（等价现状 {@code listActiveByTypeAndOwner} 的键投影）：
     * 启动恢复仅回收本实例槽位崩溃残留的执行权，不触碰其他存活实例（审查修复 F13）。
     *
     * @return 本实例持有的 turn 租约键列表（{@code turn:session:<sessionId>} 形态，顺序不保证）
     */
    List<String> listOwnedTurnKeys(String owner);

    /**
     * 调度 fire lease 抢占（等价现状 {@code tryAcquireFireLease}）：30s 窗口防重，无 owner 二级索引。
     *
     * @param deploymentId 调度器业务 ID
     * @param owner        持有者实例标识
     * @return true=获取成功；false=窗口内已有触发在途
     */
    boolean fireTryAcquire(String deploymentId, String owner);

    /**
     * 调度 fire lease owner-scoped 释放（等价现状 {@code releaseFireLease}）：
     * 现状 fire 释放走 owner-scoped {@code releaseOwned}（F11 修复），本方法正名，
     * <b>MUST NOT</b> 暗示无条件释放。
     *
     * @param deploymentId 调度器业务 ID
     * @param owner        持有者实例标识
     * @return true=本次删除生效；false=不存在 / 已易主
     */
    boolean fireReleaseOwned(String deploymentId, String owner);
}
