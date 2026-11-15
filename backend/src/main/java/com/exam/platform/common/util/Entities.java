package com.exam.platform.common.util;

import com.exam.platform.common.entity.BaseEntity;

/** 实体字段清洗：避免客户端回传的 id/tenantId/审计字段覆盖服务端数据。 */
public final class Entities {

    private Entities() {
    }

    public static <T extends BaseEntity> T forInsert(T entity) {
        entity.setId(null);
        entity.setTenantId(null);
        entity.setDeleted(null);
        entity.setCreatedAt(null);
        entity.setUpdatedAt(null);
        return entity;
    }

    public static <T extends BaseEntity> T forUpdate(T entity, Long id) {
        entity.setId(id);
        entity.setTenantId(null);
        entity.setDeleted(null);
        entity.setCreatedAt(null);
        entity.setUpdatedAt(null);
        return entity;
    }
}
