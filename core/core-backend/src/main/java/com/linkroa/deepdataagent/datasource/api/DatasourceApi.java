package com.linkroa.deepdataagent.datasource.api;

import com.linkroa.deepdataagent.datasource.api.dto.DatasourcePreviewDTO;
import com.linkroa.deepdataagent.datasource.api.dto.DatasourceReferenceDTO;
import com.linkroa.deepdataagent.datasource.api.dto.TableSchemaDTO;

import java.util.List;
import java.util.Optional;

/**
 * 数据源服务契约（跨限界上下文（BC）只读能力面，未来 Feign 落点）。
 *
 * <p>由 datasource BC 定义并发布，供上游 BC（未来的 agent / runtime 绑定层）按契约引用数据源、
 * 读取表结构与预览表数据。当前由基础设施装配点 {@code DefaultDatasourceApi} 进程内实现
 * （{@code @Service}）；未来接入 Feign 时仅需在本接口追加 {@code @FeignClient} 注解并提供远程实现，
 * 消费方无需改动。依赖方向：消费方依赖本契约、由本 BC 提供实现，<b>不引入反向编译依赖</b>。</p>
 *
 * <p><b>能力边界（本面刻意收窄）</b>：</p>
 * <ul>
 *     <li><b>只读零副作用</b>：全部方法 MUST NOT 创建/修改/删除任何数据源配置、状态或元数据；</li>
 *     <li><b>不含 SQL 执行与写操作</b>：任意 SQL 执行 / Text-to-SQL 生成 / 连接管理写操作
 *     均不属本能力面（各自属独立增量，涉及只读校验、超时、配额等独立安全议题）；</li>
 *     <li><b>不泄露存在性</b>：数据源不存在、未启用（已禁用）或表不存在时，表结构与预览统一返回
 *     {@link Optional#empty()}，不以不同形态区分三者，语义由调用方自行映射；</li>
 *     <li><b>不返回凭证</b>：连接凭证（明文/密文）MUST NOT 出现在任何返回值中；</li>
 *     <li><b>载荷受限</b>：预览行数上限恒为 100，不提供无界取回；</li>
 *     <li><b>仅发布语言 DTO</b>：返回值全部为 {@code api.dto} 下的发布语言 DTO，
 *     MUST NOT 泄露 {@code datasource.domain} 领域模型（保证未来 Feign 化可行）。</li>
 * </ul>
 *
 * @author DeepDataAgent
 */
public interface DatasourceApi {

    /**
     * 按数据源标识批量解析引用（供上游了解可用数据源清单）。
     *
     * <p>解析语义：<b>缺失标识跳过</b>（已不存在的数据源不进入结果，MUST NOT 因此抛出异常）；
     * 结果保持与入参一致的相对顺序；<b>不做状态过滤</b>——已禁用数据源仍会出现在结果中
     * （是否可用由后续读取方法决定，过滤职责不在本方法）。</p>
     *
     * @param datasourceIds 数据源标识列表，可为空
     * @return 存在数据源的引用列表（按入参相对顺序）；入参为空或全部缺失时返回空列表（非 {@code null}）
     */
    List<DatasourceReferenceDTO> listDatasources(List<Long> datasourceIds);

    /**
     * 按数据源标识与表名获取结构化表结构（表名、表注释、字段清单）。
     *
     * <p>字段注释保留"数据库原始注释"与"用户自定义注释"双来源，本面不做合并。
     * JDBC 型数据源按本 BC 既有的<b>单活动 schema</b>口径取首个 schema 下的表；
     * API 型数据源以已配置的 API 数据表及其字段映射作为表结构。</p>
     *
     * <p>以下情形统一返回 {@link Optional#empty()}（不抛异常、不区分存在性）：
     * 数据源不存在、数据源未启用（已禁用）、表不存在。</p>
     *
     * @param datasourceId 数据源标识，可为空（空返回 empty）
     * @param tableName    表名，空白返回 empty
     * @return 结构化表结构；不可用时返回 {@link Optional#empty()}
     */
    Optional<TableSchemaDTO> describeTable(Long datasourceId, String tableName);

    /**
     * 按数据源标识与表名预览样例数据（列名顺序 + 行数据）。
     *
     * <p>返回行数 MUST 限制在 1~100：请求行数超过上限时收敛到上限，非正数时收敛为下界
     * （钳制由被委托的应用层执行，本面透传调用方请求值，不做二次放宽）。</p>
     *
     * <p>以下情形统一返回 {@link Optional#empty()}（不抛异常、不区分存在性）：
     * 数据源不存在、数据源未启用（已禁用）。<b>远端取数失败等真实运行期故障不属此列</b>——
     * 该类异常 MUST 向上抛出，MUST NOT 被静默收敛为空结果。</p>
     *
     * @param datasourceId 数据源标识，可为空（空返回 empty）
     * @param tableName    表名，空白返回 empty
     * @param limit        期望返回行数（应用层钳制至 1~100）
     * @return 预览结果；不可用时返回 {@link Optional#empty()}
     */
    Optional<DatasourcePreviewDTO> previewTable(Long datasourceId, String tableName, int limit);
}