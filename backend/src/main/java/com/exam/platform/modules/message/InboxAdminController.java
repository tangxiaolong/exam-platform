package com.exam.platform.modules.message;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.modules.message.dto.InboxAdminView;
import com.exam.platform.modules.system.support.Audited;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理端：站内消息群发。 */
@RestController
@RequestMapping("/v1/admin/inbox")
@RequiredArgsConstructor
public class InboxAdminController {

    private final InboxService inboxService;

    /** 批次聚合视图：一行 = 一次群发（含收件总数与已读数；历史无批次行按单行成组）。 */
    @GetMapping("/batches")
    public Result<PageResult<java.util.Map<String, Object>>> batches(PageQuery pageQuery) {
        return Result.ok(inboxService.batches(pageQuery));
    }

    /** 发送历史：按时间倒序的收件记录（含收件人与已读状态），运营核对群发落地情况。 */
    @GetMapping("/history")
    public Result<PageResult<InboxAdminView>> history(PageQuery pageQuery) {
        return Result.ok(inboxService.history(pageQuery));
    }

    /** 群发站内信：指定 userIds 或全体非管理员账号；触达人数写入审计（INBOX_SEND）。 */
    @PostMapping
    @Audited(action = "INBOX_SEND", target = "'user_inbox_message'",
            detail = "'count=' + #result.data")
    public Result<Integer> send(@RequestBody InboxSendRequest request) {
        return Result.ok(inboxService.send(request.userIds(), request.title(), request.content()));
    }

    public record InboxSendRequest(List<Long> userIds, String title, String content) {
    }
}
