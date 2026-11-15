package com.exam.platform.modules.org.dto;

/** 组织成员维护入参。 */
public record OrgMemberRequest(
        Long userId,
        String username,
        String role
) {
}
