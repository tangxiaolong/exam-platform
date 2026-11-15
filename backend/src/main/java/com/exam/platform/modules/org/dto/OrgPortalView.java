package com.exam.platform.modules.org.dto;

/**
 * 机构侧可见的机构信息（带「我在其中的角色」）。
 *
 * <p>不复用 {@code SysOrg} 实体：机构管理员只该看到自己机构的基本资料，
 * 不该顺带拿到平台侧的备注、租户 ID、逻辑删除标记这类内部字段。</p>
 */
public record OrgPortalView(
        Long orgId,
        String orgCode,
        String orgName,
        String orgType,
        String orgStatus,
        String myRole
) {
}
