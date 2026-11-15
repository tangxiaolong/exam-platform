package com.exam.platform.modules.message;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.modules.message.dto.MessageTemplateRequest;
import com.exam.platform.modules.message.entity.MsgSendLog;
import com.exam.platform.modules.message.entity.MsgTask;
import com.exam.platform.modules.message.entity.MsgTemplate;
import com.exam.platform.modules.system.support.Audited;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 运营后台：消息任务与发送日志。 */
@RestController
@RequestMapping("/v1/admin/messages")
@RequiredArgsConstructor
public class MessageAdminController {

    private final MessageService messageService;
    private final MessageTemplateService templateService;

    @GetMapping
    public Result<PageResult<MsgTask>> page(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) String scene,
                                           PageQuery pageQuery) {
        return Result.ok(messageService.pageAll(status, scene, pageQuery));
    }

    @GetMapping("/{taskId}/logs")
    public Result<List<MsgSendLog>> logs(@PathVariable Long taskId) {
        return Result.ok(messageService.logs(taskId));
    }

    /** 手动重发（如用户补授权后）。 */
    @PostMapping("/{taskId}/resend")
    @Audited(action = "MESSAGE_RESEND", target = "'msg_task:' + #taskId", detail = "#result.data.status")
    public Result<MsgTask> resend(@PathVariable Long taskId) {
        return Result.ok(messageService.resend(taskId));
    }

    /** 当前租户已配置的消息模板（未配置的编码不在列表里，发送时走代码兜底文案）。 */
    @GetMapping("/templates")
    public Result<List<MsgTemplate>> templates() {
        return Result.ok(templateService.list());
    }

    /** 维护文案：改完立刻对后续发送生效，不需要发版。 */
    @PutMapping("/templates/{templateCode}")
    @Audited(action = "MESSAGE_TEMPLATE_UPDATE", target = "'msg_template:' + #templateCode",
            detail = "#request.title()")
    public Result<MsgTemplate> upsertTemplate(@PathVariable String templateCode,
                                             @RequestBody MessageTemplateRequest request) {
        return Result.ok(templateService.upsert(templateCode, request.title(), request.content(),
                request.enabled()));
    }
}
