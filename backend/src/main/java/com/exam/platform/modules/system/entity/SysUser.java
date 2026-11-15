package com.exam.platform.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 平台用户（管理员与考生共用一张表）。
 *
 * <p>简化说明：首期用单个 {@code roleCode} 表达角色（ADMIN / STUDENT），
 * 若需要多角色，改为 sys_role + sys_user_role 关联即可，接口层不用改动。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private String username;
    private String password;
    private String nickname;
    private String phone;
    private String avatar;
    private String roleCode;
    private String status;
    private LocalDateTime lastLoginAt;
}
