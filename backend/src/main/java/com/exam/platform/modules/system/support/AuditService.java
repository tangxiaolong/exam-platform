package com.exam.platform.modules.system.support;

import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.system.entity.SysAuditLog;
import com.exam.platform.modules.system.mapper.SysAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 管理端高风险操作审计。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final SysAuditLogMapper auditLogMapper;

    public void record(String action, String target, String detail) {
        SysAuditLog entity = new SysAuditLog();
        entity.setTenantId(UserContext.tenantId());
        entity.setAction(action);
        entity.setTarget(target);
        entity.setDetail(detail);
        try {
            var loginUser = UserContext.get();
            if (loginUser != null) {
                entity.setUserId(loginUser.getUserId());
                entity.setUsername(loginUser.getUsername());
            }
            auditLogMapper.insert(entity);
        } catch (Exception e) {
            // 审计失败不得阻断主流程，但要留痕
            log.warn("写审计日志失败 action={} target={}", action, target, e);
        }
    }
}
