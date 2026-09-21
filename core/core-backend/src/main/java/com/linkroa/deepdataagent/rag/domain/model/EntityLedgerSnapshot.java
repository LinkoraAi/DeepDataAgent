package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.ObjectUtils;

import java.util.List;

/**
 * 实体条目「账本 + 当前属性」快照值对象（重建分类阶段的读取载体，design D3 步骤②）。
 * <p>以向量表 {@code chunk_ids} 为账本权威（与 {@link EntityInfoVector#chunkIds()} 同源），
 * 携带该实体当前的向量内容与向量（供重建路径判断「期望内容未变则复用旧向量、免一次远程
 * 向量化」）以及图行 {@code properties}（供降级路径保留原语义字段、写回前比较）。
 * 图行缺失（摄入收敛中间态）时 {@link #properties()} 归一为空属性，向量列可为 {@code null}。</p>
 * <p>本快照为<b>不加锁普通读</b>产物，MUST NOT 作为写回基准——写回基准由重建服务阶段 B 的
 * 加锁重读提供（spec graph-contribution-rebuild / R4 写回纪律）。</p>
 *
 * @param kbId       所属知识库ID
 * @param entityName 实体名称（条目身份，库内唯一）
 * @param chunkIds   当前向量账本（{@code chunk_ids} 升序无关，按落库原序承载；账本权威）
 * @param content    向量行当前内容（可为 null——历史行缺失内容时按空处理）
 * @param vector     向量行当前向量（可为 null——未向量化时复用条件不成立）
 * @param properties 图行当前属性（图行缺失时为空属性，非 null）
 * @author DeepDataAgent
 */
public record EntityLedgerSnapshot(
        Long kbId,
        String entityName,
        List<Long> chunkIds,
        String content,
        float[] vector,
        EntityProperties properties) {

    /**
     * 紧凑构造器：不变量校验与空值归一（账本与属性恒非 null，降低下游防御分支）。
     */
    public EntityLedgerSnapshot {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("实体账本快照必须关联知识库");
        }
        if (ObjectUtils.isEmpty(entityName)) {
            throw new IllegalArgumentException("实体账本快照 entityName 不能为空");
        }
        chunkIds = ObjectUtils.isEmpty(chunkIds) ? List.of() : List.copyOf(chunkIds);
        properties = ObjectUtils.isEmpty(properties) ? EntityProperties.empty() : properties;
    }
}
