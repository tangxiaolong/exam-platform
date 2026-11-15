package com.exam.platform.modules.system.support;

import com.exam.platform.modules.auth.dto.UserView;
import com.exam.platform.modules.system.entity.SysUser;

/** 用户实体到对外视图的转换（对外不返回 password 等敏感字段）。 */
public final class UserViews {

    private UserViews() {
    }

    public static UserView of(SysUser user) {
        if (user == null) {
            return null;
        }
        return new UserView(user.getId(), user.getUsername(), user.getNickname(), user.getAvatar(),
                user.getPhone(), user.getRoleCode(), user.getStatus(), user.getTenantId());
    }
}
