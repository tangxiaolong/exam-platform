package com.exam.platform.modules.org.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 机构额度账户：按 (机构, 权益类型, 资源) 三维记账。
 *
 * <p>余额不落库，由 {@code totalAmount - usedAmount} 现算——两个累计值都在同一个 UPDATE 里变，
 * 不会出现「余额和流水对不上」的漂移。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_org_quota")
public class SysOrgQuota extends BaseEntity {

    /** 通用额度：不绑定具体资源时使用（业务上等价于「这个类型的额度随便用」）。 */
    public static final long RESOURCE_UNIVERSAL = 0L;

    private Long orgId;
    private String entitlementType;
    private Long resourceId;
    private Long totalAmount;
    private Long usedAmount;
    private String remark;
    /** 额度有效期；null 表示长期有效（存量数据与不设期限的采购都是这个语义）。 */
    private LocalDateTime expireAt;

    public long balance() {
        long total = totalAmount == null ? 0L : totalAmount;
        long used = usedAmount == null ? 0L : usedAmount;
        return total - used;
    }

    /** 是否已过期：null（长期有效）永不过期。 */
    public boolean expired() {
        return expireAt != null && !expireAt.isAfter(LocalDateTime.now());
    }
}
