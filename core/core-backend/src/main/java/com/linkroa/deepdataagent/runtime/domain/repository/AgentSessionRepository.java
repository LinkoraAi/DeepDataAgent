package com.linkroa.deepdataagent.runtime.domain.repository;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.SessionListFilter;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;

import java.util.List;
import java.util.Optional;

/**
 * Agent 会话仓储接口（依赖倒置，领域语义方法声明）。
 * <p>双列模型：所有状态迁移方法均原子更新 {@code status + turn_phase} 两列（按迁移声明
 * 选择性写入），相位为内部事实、永不外显。</p>
 * <p><b>CAS 面唯一入口为 {@link #transition(String, Transition)}</b>：前置条件、目标双列取值
 * 全部来自 {@code SessionStateMachine} 的命名迁移常量；归档已移出状态机，
 * 唯一落点为 {@link #archive(String)}（仅写 archived_at）。</p>
 */
public interface AgentSessionRepository {

    /**
     * 保存会话（新增或更新）。
     */
    AgentSession save(AgentSession session);

    /**
     * 按业务会话 ID 查询。
     */
    Optional<AgentSession> findBySessionId(String sessionId);

    /**
     * 按用户 + 过滤条件游标分页查询（创建时间降序，keyset 行值比较；
     * 6.3 管理面 Cursor 约定，替代旧 {@code findByFilters} 升序不透明游标）。
     *
     * @param userId 用户 ID
     * @param filter 列表过滤条件（agent / 环境 / 状态 / metadata / 归档排除 / 游标行位点与方向）
     * @param limit  本次最多返回条数（含用于判断是否还有下一页的多取一条）
     * @return 会话列表（after 方向降序、before 方向升序）
     */
    List<AgentSession> findByCursor(String userId, SessionListFilter filter, int limit);

    /**
     * 状态机迁移（CAS 唯一落点）：按 {@link Transition} 声明原子更新
     * {@code status + turn_phase}（选择性写列），前置条件由迁移声明的
     * {@code statusFrom} / {@code phaseFrom} 集合生成（空集 = 该维度不拼条件）。
     *
     * @param sessionId  会话 ID
     * @param transition 命名迁移常量（如 {@code Transition.BEGIN_TURN} / {@code Transition.FINISH_TURN}）
     * @return 受影响行数（1=迁移成功；0=前置条件不匹配）
     */
    int transition(String sessionId, Transition transition);

    /**
     * 归档会话（唯一归档落点）：仅写 {@code archived_at}，不改写 {@code status} / {@code turn_phase}。
     * <p>守卫 {@code archived_at IS NULL}：并发归档仅一方命中，另一方返回 0 行。</p>
     *
     * @param sessionId 会话 ID
     * @return 受影响行数（1=归档成功；0=已归档或不存在）
     */
    int archive(String sessionId);

    /**
     * 终态事务内的当前状态窄读（单列 {@code status}）：供
     * {@code TurnFinalizationPolicy} 判定「显式指令胜出（已归档 / 已终止）」一行。
     * <p><b>不做仲裁</b>：仲裁者始终是 {@link #transition} 的 CAS 守卫，本读仅用于把必然 0 行的
     * 迁移提前显式化为 no-op。会话行不存在 / 已逻辑删除 / 存量取值不在枚举值域内时返回
     * {@link Optional#empty()}，调用方据此跳过前置判定（与现状一致）。</p>
     *
     * @param sessionId 会话 ID
     * @return 当前会话状态（读不到为空）
     */
    Optional<AgentSessionStatus> currentStatus(String sessionId);

    /**
     * 会话当前是否处于取消中相位（持久化的在途取消痕迹）。
     * <p>终态判定「在途取消」谓词的跨进程权威依据：取消侧 {@code PHASE_CANCEL} 迁移的 CAS
     * 提交于共享库，任意实例可读、不受短 TTL 协调凭证过期限制。</p>
     *
     * @param sessionId 会话 ID
     * @return true=内部相位当前为 cancelling（取消在途，等待执行侧收敛）
     */
    boolean isCancelling(String sessionId);

    /**
     * 更新会话可变属性（update 面：title / metadata / environment_variables 三列）。
     * <p>{@code titlePresent=true} 时 {@code title} 生效（可为 null = 清空标题）；
     * {@code metadata} / {@code environmentVariables} 传 null 的列不更新
     * （「缺省不覆盖」由 SQL 条件 set 承载，环境变量为整体替换语义）。</p>
     *
     * @param sessionId             会话 ID
     * @param title                 会话标题（{@code titlePresent=true} 时生效；null=清空）
     * @param titlePresent          请求是否显式提交标题（false=不改）
     * @param metadata              扩展元数据（JSON 文本，可空 = 不改）
     * @param environmentVariables  环境变量（JSON 文本，可空 = 不改；整体替换语义）
     */
    void updateProfile(String sessionId, String title, boolean titlePresent,
                       String metadata, String environmentVariables);

    /**
     * 覆盖更新会话挂载资源列表（创建后追加挂载：写入合并后的全量资源，序列化为 jsonb）。
     *
     * @param sessionId 会话 ID
     * @param resources 合并后的挂载资源列表
     * @return 受影响行数（1=成功）
     */
    int updateResources(String sessionId, List<SessionResource> resources);

    /**
     * 查询存在活跃执行（对外 running/rescheduling 且内部相位 running/cancelling）的会话 ID
     * （启动恢复孤儿执行复位用；{@code awaiting_confirmation} 不在命中范围）。
     */
    List<String> findActiveExecutionSessionIds();

    /**
     * 统计仍引用指定执行环境的未删除会话数（环境删除引用校验）。
     *
     * @param environmentId 环境业务 ID
     * @return 引用数（0 表示无会话挂载，可安全删除）
     */
    long countByEnvironmentId(String environmentId);

    /**
     * 统计仍挂载指定记忆库的未删除会话数（记忆库删除引用校验，JSONB 包含查询）。
     *
     * @param storeId 记忆库业务 ID（ms_ 前缀）
     * @return 引用数（0 表示无会话挂载，可安全删除）
     */
    long countByMemoryStoreId(String storeId);

    /**
     * 统计仍挂载指定保管库的未删除会话数（保管库删除引用校验，vault_ids JSONB 包含查询）。
     *
     * @param vaultId 保管库业务 ID（vault_ 前缀）
     * @return 引用数（0 表示无会话挂载，可安全删除）
     */
    long countByVaultId(String vaultId);

    /**
     * 刷新 last_active_at。
     */
    void touchLastActive(String sessionId);

    /**
     * 删除会话（6.3 delete 面，逻辑删除 is_deleted=1；历史事件由调用方同事务清理）。
     *
     * @param sessionId 会话 ID
     * @return 受影响行数（1=删除成功，0=会话不存在）
     */
    int deleteBySessionId(String sessionId);
}
