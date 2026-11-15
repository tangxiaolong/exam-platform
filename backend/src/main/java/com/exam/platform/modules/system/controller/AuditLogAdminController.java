package com.exam.platform.modules.system.controller;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.modules.system.dto.AuditLogView;
import com.exam.platform.modules.system.service.AuditLogQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 审计日志查询入口。
 *
 * <p>落在 {@code /v1/admin/**} 下，由鉴权拦截器统一要求平台管理员角色——
 * 审计记录含操作人账号与 IP，考生与机构侧都无权查看。</p>
 */
@RestController
@RequestMapping("/v1/admin/audit-logs")
@RequiredArgsConstructor
public class AuditLogAdminController {

    private final AuditLogQueryService auditLogQueryService;

    @GetMapping
    public Result<PageResult<AuditLogView>> page(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            PageQuery pageQuery) {
        return Result.ok(auditLogQueryService.page(action, username, from, to, pageQuery));
    }

    @GetMapping("/actions")
    public Result<List<String>> actions() {
        return Result.ok(auditLogQueryService.actions());
    }
}
