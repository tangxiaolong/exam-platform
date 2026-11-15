package com.exam.platform.modules.message;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.message.dto.SubscribeRequest;
import com.exam.platform.modules.message.entity.MsgSubscription;
import com.exam.platform.modules.message.entity.MsgTask;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 考生端：订阅授权上报与我的消息。 */
@RestController
@RequestMapping("/v1/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /** 小程序点击“允许通知”后上报，累加可发送次数。 */
    @PostMapping("/subscriptions")
    public Result<MsgSubscription> subscribe(@RequestBody SubscribeRequest request) {
        return Result.ok(messageService.subscribe(UserContext.userId(), request.templateCode(),
                request.times() == null ? 1 : request.times()));
    }

    @GetMapping("/subscriptions")
    public Result<List<MsgSubscription>> subscriptions() {
        return Result.ok(messageService.mySubscriptions(UserContext.userId()));
    }

    @GetMapping
    public Result<PageResult<MsgTask>> myMessages(PageQuery pageQuery) {
        return Result.ok(messageService.pageMy(UserContext.userId(), pageQuery));
    }
}
