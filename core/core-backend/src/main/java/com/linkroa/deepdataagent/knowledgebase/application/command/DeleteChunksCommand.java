package com.linkroa.deepdataagent.knowledgebase.application.command;

import java.util.List;

/**
 * 批量删除切片命令（人工批量清退，「Storage 先删、DB 后删」）。
 * <p><b>来源准入</b>：本批必须全部为「人工新增」（{@code MANUAL}）来源的切片；
 * 批内含任何「解析产生」（{@code PARSED}）的切片即整批拒绝（400，附引导文案——
 * 解析块单独删除会使图谱账本永久引用已消失分块，需改用重新解析或删除文档）。</p>
 * <p>单请求不限制总条数：应用服务按删除原语的批次上限（500 条/批）自动分批，每批一个独立物理删除事务，
 * 任一批失败即终止剩余批次，已提交批次不回滚，由用户重删幂等续跑。</p>
 *
 * @param ids 待删除切片ID集合（服务端去重后执行；集合为空直接拒绝；含解析来源切片则整批拒绝）
 */
public record DeleteChunksCommand(
        List<Long> ids
) {
}
