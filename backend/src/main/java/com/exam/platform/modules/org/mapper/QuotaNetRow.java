package com.exam.platform.modules.org.mapper;

import lombok.Data;

/**
 * 按账户聚合的发放流水净额（{@code consumed_amount - refunded_amount}）。
 *
 * <p>对账要拿它跟账户上的 {@code used_amount} 逐个比对，所以先在库里按 quota_id 聚合成
 * 一个结果集，而不是「列出账户、再逐个查 SUM」——后者是典型的 N+1：
 * 1000 个额度账户就是 1001 次查询，对账任务本来就跑在凌晨、更容易被慢查询超时打断。</p>
 */
@Data
public class QuotaNetRow {

    private Long quotaId;
    private Long net;
}
