package com.exam.platform.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.modules.system.dto.AuditLogView;
import com.exam.platform.modules.system.entity.SysAuditLog;
import com.exam.platform.modules.system.mapper.SysAuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志查询。
 *
 * <p>{@link com.exam.platform.modules.system.support.AuditService} 一直在写审计，但此前没有读的入口——
 * 「谁在什么时候强制收了谁的卷」这件事只有落库记录，运营和客服问起来只能翻库。
 * 这个查询面把那段数据变成可查的东西，且不需要把库权限下放给运营。</p>
 *
 * <p>租户隔离由全局租户插件负责，这里不再手写 tenant_id 条件。</p>
 */
@Service
@RequiredArgsConstructor
public class AuditLogQueryService {

    private final SysAuditLogMapper auditLogMapper;

    /**
     * @param action   动作名，精确匹配（动作是有限的枚举式取值，模糊匹配只会带出无关记录）
     * @param username 操作人，模糊匹配（运营通常只记得半个账号）
     * @param from     起始日期（含当天 00:00）
     * @param to       截止日期（含当天，按次日 00:00 开区间处理）
     */
    public PageResult<AuditLogView> page(String action, String username, LocalDate from, LocalDate to,
                                         PageQuery pageQuery) {
        LambdaQueryWrapper<SysAuditLog> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.hasText(action)) {
            wrapper.eq(SysAuditLog::getAction, action.trim());
        }
        if (StringUtils.hasText(username)) {
            wrapper.like(SysAuditLog::getUsername, username.trim());
        }
        if (from != null) {
            wrapper.ge(SysAuditLog::getCreatedAt, from.atStartOfDay());
        }
        if (to != null) {
            // 用「次日 0 点」开区间而不是 <= 当天 23:59:59：后者会漏掉最后一秒内的记录
            wrapper.lt(SysAuditLog::getCreatedAt, to.plusDays(1).atStartOfDay());
        }
        // 新记录在前：审计查询的典型诉求是「最近谁动了手」
        wrapper.orderByDesc(SysAuditLog::getId);

        Page<SysAuditLog> page = auditLogMapper.selectPage(pageQuery.toPage(), wrapper);
        List<AuditLogView> views = page.getRecords().stream().map(this::toView).toList();
        return PageResult.of(views, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 可选的动作品类，供前端下拉过滤。 */
    public List<String> actions() {
        QueryWrapper<SysAuditLog> wrapper = new QueryWrapper<>();
        wrapper.select("DISTINCT action").orderByAsc("action");
        return auditLogMapper.selectObjs(wrapper).stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .filter(StringUtils::hasText)
                .toList();
    }

    private AuditLogView toView(SysAuditLog entity) {
        return new AuditLogView(entity.getId(), entity.getUserId(), entity.getUsername(), entity.getAction(),
                entity.getTarget(), entity.getDetail(), entity.getIp(), entity.getCreatedAt());
    }
}
