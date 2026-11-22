package com.exam.platform.modules.message;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.message.dto.InboxView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 考生端：站内消息收件箱。 */
@RestController
@RequestMapping("/v1/inbox")
@RequiredArgsConstructor
public class InboxController {

    private final InboxService inboxService;

    @GetMapping
    public Result<PageResult<InboxView>> page(PageQuery pageQuery) {
        return Result.ok(inboxService.page(UserContext.userId(), pageQuery));
    }

    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.ok(inboxService.unreadCount(UserContext.userId()));
    }

    @PostMapping("/read-all")
    public Result<Integer> markAllRead() {
        return Result.ok(inboxService.markAllRead(UserContext.userId()));
    }

    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        inboxService.markRead(UserContext.userId(), id);
        return Result.ok(null);
    }
}
