package com.exam.platform.modules.org.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 组织成员：一个人可在多个组织，同一组织内唯一。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_org_member")
public class SysOrgMember extends BaseEntity {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_MEMBER = "MEMBER";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REMOVED = "REMOVED";

    private Long orgId;
    private Long userId;
    private String role;
    private String status;
}
