package com.exam.platform.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 渠道身份绑定（微信 openid 等）。openid 只作为渠道标识，不作为平台主键。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user_identity")
public class SysUserIdentity extends BaseEntity {

    private Long userId;
    private String provider;
    private String openId;
    private String unionId;
}
