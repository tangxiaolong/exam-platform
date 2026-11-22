package com.exam.platform.modules.message;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.common.util.Entities;
import com.exam.platform.modules.message.dto.InboxAdminView;
import com.exam.platform.modules.message.dto.InboxView;
import com.exam.platform.modules.message.entity.UserInboxMessage;
import com.exam.platform.modules.message.mapper.UserInboxMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 站内消息（PRD 4.10）：管理端群发、考生收件箱、已读标记。
 *
 * <p>与订阅消息（MsgTask）互补：订阅消息依赖用户授权与模板，站内信不依赖——
 * 「今晚 22:00 系统维护」这类通知不能因为用户没点「允许通知」就失联。</p>
 */
@Service
@RequiredArgsConstructor
public class InboxService {

    private final UserInboxMessageMapper inboxMapper;
    private final JdbcTemplate jdbcTemplate;

    /** 发送站内信：userIds 为空则发给本租户全部非管理员账号；返回触达人数。 */
    @Transactional
    public int send(List<Long> userIds, String title, String content) {
        String trimmedTitle = title == null ? "" : title.trim();
        String trimmedContent = content == null ? "" : content.trim();
        if (trimmedTitle.isEmpty() || trimmedContent.isEmpty()) {
            throw BizException.badRequest("标题与正文不能为空");
        }
        List<Long> targets = userIds != null && !userIds.isEmpty()
                ? userIds
                : jdbcTemplate.queryForList(
                        "SELECT id FROM sys_user WHERE tenant_id = ? AND deleted = 0 "
                                + "AND (role_code IS NULL OR role_code <> 'ADMIN')",
                        Long.class, UserContext.tenantId());
        String batchNo = "B" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        for (Long userId : targets) {
            UserInboxMessage message = new UserInboxMessage();
            message.setUserId(userId);
            message.setTitle(trimmedTitle);
            message.setContent(trimmedContent);
            message.setReadFlag(0);
            message.setBatchNo(batchNo);
            Entities.forInsert(message);
            inboxMapper.insert(message);
        }
        return targets.size();
    }

    /** 批次聚合视图：一行 = 一次群发（历史无批次号的行按单行成组）。 */
    public PageResult<java.util.Map<String, Object>> batches(PageQuery pageQuery) {
        var page = pageQuery.toPage();
        int limit = (int) page.getSize();
        long offset = (page.getCurrent() - 1) * page.getSize();
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT COALESCE(batch_no, CONCAT('#', CAST(id AS CHAR))) ) "
                        + "FROM user_inbox_message WHERE tenant_id = ? AND deleted = 0",
                Long.class, UserContext.tenantId());
        var rows = jdbcTemplate.queryForList(
                "SELECT COALESCE(batch_no, CONCAT('#', CAST(id AS CHAR))) AS batch_no, "
                        + "MIN(title) AS title, MIN(content) AS content, "
                        + "COUNT(*) AS total, SUM(read_flag) AS read_count, "
                        + "MIN(created_at) AS created_at "
                        + "FROM user_inbox_message WHERE tenant_id = ? AND deleted = 0 "
                        + "GROUP BY COALESCE(batch_no, CONCAT('#', CAST(id AS CHAR))) "
                        + "ORDER BY MIN(created_at) DESC, MIN(id) DESC LIMIT ? OFFSET ?",
                UserContext.tenantId(), limit, offset);
        var normalized = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var row : rows) {
            var item = new java.util.LinkedHashMap<String, Object>();
            row.forEach((key, value) -> item.put(key.toLowerCase(java.util.Locale.ROOT), value));
            normalized.add(item);
        }
        return PageResult.of(normalized, total, page.getCurrent(), page.getSize());
    }

    /** 管理端发送历史：租户维度按时间倒序列出收件记录（租户隔离由插件保证）。 */
    public PageResult<InboxAdminView> history(PageQuery pageQuery) {
        var page = inboxMapper.selectPage(pageQuery.toPage(),
                Wrappers.<UserInboxMessage>lambdaQuery()
                        .orderByDesc(UserInboxMessage::getId));
        List<InboxAdminView> views = page.getRecords().stream()
                .map(message -> new InboxAdminView(message.getId(), message.getUserId(), message.getTitle(),
                        message.getContent(), message.getReadFlag() != null && message.getReadFlag() == 1,
                        message.getCreatedAt()))
                .toList();
        return PageResult.of(views, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 我的收件箱（按时间倒序分页）。 */
    public PageResult<InboxView> page(Long userId, PageQuery pageQuery) {
        var page = inboxMapper.selectPage(pageQuery.toPage(),
                Wrappers.<UserInboxMessage>lambdaQuery()
                        .eq(UserInboxMessage::getUserId, userId)
                        .orderByDesc(UserInboxMessage::getId));
        List<InboxView> views = page.getRecords().stream()
                .map(message -> new InboxView(message.getId(), message.getTitle(), message.getContent(),
                        message.getReadFlag() != null && message.getReadFlag() == 1,
                        message.getCreatedAt()))
                .toList();
        return PageResult.of(views, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 未读数：小程序「我的」页角标用。 */
    public long unreadCount(Long userId) {
        Long count = inboxMapper.selectCount(Wrappers.<UserInboxMessage>lambdaQuery()
                .eq(UserInboxMessage::getUserId, userId)
                .eq(UserInboxMessage::getReadFlag, 0));
        return count == null ? 0 : count;
    }

    /** 全部已读：一键清空本人未读，返回清零条数。 */
    public int markAllRead(Long userId) {
        return inboxMapper.update(null, Wrappers.<UserInboxMessage>lambdaUpdate()
                .eq(UserInboxMessage::getUserId, userId)
                .eq(UserInboxMessage::getReadFlag, 0)
                .set(UserInboxMessage::getReadFlag, 1));
    }

    /** 标记已读：仅按本人未读记录生效，重复标记无副作用。 */
    public void markRead(Long userId, Long id) {
        inboxMapper.update(null, Wrappers.<UserInboxMessage>lambdaUpdate()
                .eq(UserInboxMessage::getId, id)
                .eq(UserInboxMessage::getUserId, userId)
                .eq(UserInboxMessage::getReadFlag, 0)
                .set(UserInboxMessage::getReadFlag, 1));
    }
}
