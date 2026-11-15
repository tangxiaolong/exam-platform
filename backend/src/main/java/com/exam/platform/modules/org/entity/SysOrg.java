package com.exam.platform.modules.org.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 机构 / 企业组织。
 *
 * <p>租户（{@code sys_tenant}）是数据隔离维度；组织是租户内部的业务实体，
 * 用于机构采购、企业授权等按组织维度批量发放权益。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_org")
public class SysOrg extends BaseEntity {

    public static final String TYPE_INSTITUTION = "INSTITUTION";
    public static final String TYPE_ENTERPRISE = "ENTERPRISE";

    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    private String code;
    private String name;
    /** INSTITUTION / ENTERPRISE */
    private String type;
    private String contactName;
    private String contactPhone;
    private String status;
    private String remark;
}
