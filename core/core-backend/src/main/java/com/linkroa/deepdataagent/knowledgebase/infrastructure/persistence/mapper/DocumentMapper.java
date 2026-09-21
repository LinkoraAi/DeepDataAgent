package com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.KbAuditFieldUtils;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity.DocumentEntity;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 文档 Mapper。
 * <p>彻底物理删体系：实体无 is_deleted 列、
 * 无 {@code @TableLogic}，内置 delete / deleteById 即物理 DELETE，行消失即「已删除」。</p>
 * <p><b>Lambda 列写法约束</b>：条件构造器的列参数必须使用方法引用（如 {@code DocumentEntity::getId}），
 * 禁止箭头 lambda——MyBatis-Plus 无法从合成方法名解析列名，详见 {@link ChunkMapper} 同款说明。</p>
 */
@Mapper
public interface DocumentMapper extends BaseMapper<DocumentEntity> {

    /**
     * 悲观锁读取单条（状态流转 / 删除前的行锁）。
     *
     * @param id 主键
     * @return 命中的实体；无命中返回 null
     */
    default DocumentEntity selectByIdForUpdate(Long id) {
        return selectOne(Wrappers.<DocumentEntity>lambdaQuery()
                .eq(DocumentEntity::getId, id)
                .last("FOR UPDATE"));
    }

    /**
     * 知识库内文档条件分页查询：文件名模糊匹配，状态精确过滤。
     *
     * @param kbId     知识库ID
     * @param fileName 文件名关键字（可空）
     * @param status   文档状态（可空）
     * @param offset   偏移量
     * @param size     每页条数
     * @return 文档实体列表
     */
    default List<DocumentEntity> selectByKbId(Long kbId, String fileName, String status, long offset, int size) {
        return selectList(buildCondition(kbId, fileName, status)
                .orderByDesc(DocumentEntity::getCreatedAt)
                .orderByDesc(DocumentEntity::getId)
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    /**
     * 知识库内文档条件统计。
     *
     * @param kbId     知识库ID
     * @param fileName 文件名关键字（可空）
     * @param status   文档状态（可空）
     * @return 命中记录数
     */
    default long countByKbId(Long kbId, String fileName, String status) {
        Long count = selectCount(buildCondition(kbId, fileName, status));
        return ObjectUtils.isEmpty(count) ? 0L : count;
    }

    /**
     * 查询知识库内全部文档（不分页，级联清理 / 重建用）。
     *
     * @param kbId 知识库ID
     * @return 文档实体列表
     */
    default List<DocumentEntity> selectByKbIdOnly(Long kbId) {
        return selectList(Wrappers.<DocumentEntity>lambdaQuery()
                .eq(DocumentEntity::getKbId, kbId)
                .orderByAsc(DocumentEntity::getId));
    }

    /**
     * 统计知识库内文档数。
     *
     * @param kbId 知识库ID
     * @return 命中记录数
     */
    default long countByKbIdOnly(Long kbId) {
        Long count = selectCount(Wrappers.<DocumentEntity>lambdaQuery()
                .eq(DocumentEntity::getKbId, kbId));
        return ObjectUtils.isEmpty(count) ? 0L : count;
    }

    /**
     * 去重预检：命中同名或同内容哈希的文档。
     * <p>判重两轴各有唯一载体：文件名轴取 {@code file_name} 列、内容哈希轴取 {@code file_content_hash} 列
     * （{@code varchar(64)}，服务端对上传的原始文件字节计算 SHA-256，小写十六进制、恒 64 字符；
     * 该值唯一用途是判重轴，MUST NOT 用作文件完整性校验、秒传或解析产物指纹），
     * 均为纯 Lambda 条件构造，不使用 {@code apply()} 裸 SQL。</p>
     * <p><b>该方法故意不按 {@code status} 过滤</b>：判重命中集合可能包含 {@code DELETING} /
     * {@code DELETE_FAILED} 的旧命中项，这些项由 {@code DocumentApplicationService#acceptDelete}
     * 的幂等分支消化（已在飞 / 崩溃遗留重触发、DELETE_FAILED 首次推回 DELETING 续跑）。
     * 若在 SQL 层加 {@code WHERE status NOT IN ('DELETING','DELETE_FAILED')}，会导致崩溃遗留
     * {@code DELETING} 未清理的旧文档在下一次覆盖换版时被跳过、从而永久残留。
     * 用户可见的重复文案过滤属展示层职责，见 {@code DocumentDedupService#describeHits}。
     * </p>
     *
     * @param kbId        知识库ID
     * @param fileName    待判重文件名（可空，为空则该轴不参与匹配）
     * @param contentHash 待判重内容哈希（可空，为空则该轴不参与匹配）
     * @return 命中的文档实体列表（不区分状态，按主键升序）；两个判重条件均为空时返回空列表
     */
    default List<DocumentEntity> selectDuplicates(Long kbId, String fileName, String contentHash) {
        boolean namePresent = StringUtils.isNotBlank(fileName);
        boolean hashPresent = StringUtils.isNotBlank(contentHash);
        if (!namePresent && !hashPresent) {
            return List.of();
        }
        LambdaQueryWrapper<DocumentEntity> wrapper = Wrappers.<DocumentEntity>lambdaQuery()
                .eq(DocumentEntity::getKbId, kbId);
        if (namePresent && hashPresent) {
            wrapper.and(w -> w
                    .eq(DocumentEntity::getFileName, fileName)
                    .or()
                    .eq(DocumentEntity::getFileContentHash, contentHash));
        } else if (namePresent) {
            wrapper.eq(DocumentEntity::getFileName, fileName);
        } else {
            wrapper.eq(DocumentEntity::getFileContentHash, contentHash);
        }
        return selectList(wrapper.orderByAsc(DocumentEntity::getId));
    }

    /**
     * 物理删除知识库内全部文档（无 @TableLogic，delete(wrapper) 即 DELETE FROM）。
     *
     * @param kbId 知识库ID
     * @return 受影响行数
     */
    default int deleteByKbId(Long kbId) {
        return delete(Wrappers.<DocumentEntity>lambdaQuery()
                .eq(DocumentEntity::getKbId, kbId));
    }

    /**
     * 分批取知识库内文档的主键 ID（id 升序，清退调度用，仅回读 id 列）。
     * <p>彻底物理删体系：已删文档行物理不存在，逐轮从头取 LIMIT 即可续跑。</p>
     *
     * @param kbId  知识库ID
     * @param limit 单批上限
     * @return 文档 ID 列表（升序）；无命中返回空列表
     */
    default List<Long> selectIdsByKbId(Long kbId, int limit) {
        if (ObjectUtils.isEmpty(kbId) || limit <= 0) {
            return List.of();
        }
        return selectList(Wrappers.<DocumentEntity>lambdaQuery()
                .select(DocumentEntity::getId)
                .eq(DocumentEntity::getKbId, kbId)
                .orderByAsc(DocumentEntity::getId)
                .last("LIMIT " + limit))
                .stream()
                .map(DocumentEntity::getId)
                .toList();
    }

    /**
     * 按主键集合批量物理删除文档（无 @TableLogic，delete(wrapper) 即 DELETE FROM）。
     *
     * @param ids 文档 ID 集合
     * @return 受影响行数；空集合直接返回 0
     */
    default int deleteByIds(List<Long> ids) {
        if (ObjectUtils.isEmpty(ids)) {
            return 0;
        }
        return delete(Wrappers.<DocumentEntity>lambdaQuery()
                .in(DocumentEntity::getId, ids));
    }

    /**
     * 状态 CAS 条件更新：主键命中且当前状态在允许集合内时，原子更新状态与失败原因。
     * <p>error_message 无条件写入（值为 null 即清除）；wrapper 更新不触发
     * {@code MetaObjectHandler} 自动填充，故此处显式补齐 updated_at / updated_by
     * （时间口径与操作人缺省值统一取 {@link KbAuditFieldUtils}）。</p>
     *
     * @param id              主键
     * @param fromStatusNames 允许的当前状态名集合（CAS 前置条件）
     * @param toStatusName    目标状态名
     * @param errorMessage    失败原因（写入 error_message，null 表示清除）
     * @return 受影响行数（0 或 1）
     */
    default int transitStatus(Long id, Collection<String> fromStatusNames, String toStatusName, String errorMessage) {
        if (ObjectUtils.isEmpty(id) || ObjectUtils.isEmpty(fromStatusNames) || StringUtils.isBlank(toStatusName)) {
            return 0;
        }
        OffsetDateTime now = KbAuditFieldUtils.now();
        return update(null, Wrappers.<DocumentEntity>lambdaUpdate()
                .eq(DocumentEntity::getId, id)
                .in(DocumentEntity::getStatus, fromStatusNames)
                .set(DocumentEntity::getStatus, toStatusName)
                .set(DocumentEntity::getErrorMessage, errorMessage)
                .set(DocumentEntity::getUpdatedAt, now)
                .set(DocumentEntity::getUpdatedBy, KbAuditFieldUtils.DEFAULT_OPERATOR));
    }

    /**
     * 非终态批量 CAS 条件更新（保留能力；启动恢复链路已改为按本实例在飞注册表键逐条一致性校验收敛，
     * 本方法当前无生产调用点）：
     * 单条 UPDATE 把「状态命中允许集合」的全部行置为目标状态并回写失败原因。
     * <p>彻底物理删体系：已删文档行物理不存在，天然不参与收敛；天然幂等——二次执行状态已
     * 脱离允许集合，影响 0 行。wrapper 更新不触发 {@code MetaObjectHandler}，
     * 显式补齐 updated_at / updated_by（口径统一取 {@link KbAuditFieldUtils}）。</p>
     *
     * @param fromStatusNames 允许的非终态状态名集合（CAS 前置条件，空集合返回 0）
     * @param toStatusName    目标状态名（清理场景为 FAILED）
     * @param errorMessage    失败原因（写入 error_message），空白返回 0
     * @return 受影响行数
     */
    default int failNonTerminal(Collection<String> fromStatusNames, String toStatusName, String errorMessage) {
        if (ObjectUtils.isEmpty(fromStatusNames) || StringUtils.isBlank(toStatusName)
                || StringUtils.isBlank(errorMessage)) {
            return 0;
        }
        OffsetDateTime now = KbAuditFieldUtils.now();
        return update(null, Wrappers.<DocumentEntity>lambdaUpdate()
                .in(DocumentEntity::getStatus, fromStatusNames)
                .set(DocumentEntity::getStatus, toStatusName)
                .set(DocumentEntity::getErrorMessage, errorMessage)
                .set(DocumentEntity::getUpdatedAt, now)
                .set(DocumentEntity::getUpdatedBy, KbAuditFieldUtils.DEFAULT_OPERATOR));
    }

    /**
     * 构建文档查询条件：知识库ID / 文件名 / 状态为空时表示该维度不加过滤（支持全库统计口径）。
     *
     * @param kbId     知识库ID（可空）
     * @param fileName 文件名关键字（可空）
     * @param status   文档状态（可空）
     * @return 条件构造器
     */
    private LambdaQueryWrapper<DocumentEntity> buildCondition(Long kbId, String fileName, String status) {
        return Wrappers.<DocumentEntity>lambdaQuery()
                .eq(!ObjectUtils.isEmpty(kbId), DocumentEntity::getKbId, kbId)
                .like(StringUtils.isNotBlank(fileName), DocumentEntity::getFileName, fileName)
                .eq(StringUtils.isNotBlank(status), DocumentEntity::getStatus, status);
    }
}
