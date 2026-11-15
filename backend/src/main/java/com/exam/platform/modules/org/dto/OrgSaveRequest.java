package com.exam.platform.modules.org.dto;

/** 管理端新建/编辑组织入参。 */
public record OrgSaveRequest(
        String code,
        String name,
        String type,
        String contactName,
        String contactPhone,
        String status,
        String remark
) {
}
