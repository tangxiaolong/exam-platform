package com.exam.platform.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 管理端高风险操作审计日志。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_audit_log")
public class SysAuditLog extends BaseEntity {

    private Long userId;
    private String username;
    private String action;
    private String target;
    private String detail;
    private String ip;
}
