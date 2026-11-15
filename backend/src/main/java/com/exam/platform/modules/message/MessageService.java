package com.exam.platform.modules.message;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.Entities;
import com.exam.platform.common.util.Json;
import com.exam.platform.modules.message.entity.MsgSendLog;
import com.exam.platform.modules.message.entity.MsgSubscription;
import com.exam.platform.modules.message.entity.MsgTask;
import com.exam.platform.modules.message.mapper.MsgSendLogMapper;
import com.exam.platform.modules.message.mapper.MsgSubscriptionMapper;
import com.exam.platform.modules.message.mapper.MsgTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 消息中心。
 *
 * <p>设计要点（对应文档 4.10）：</p>
 * <ul>
 *   <li>业务事件先落 {@code msg_task}，投递失败只影响任务状态，不影响业务成功；</li>
 *   <li>发送前校验订阅授权（微信订阅消息为一次性授权，按剩余次数计量）与模板配置；</li>
 *   <li>同一业务事件通过唯一键幂等，重复触发不会重复发送；</li>
 *   <li>失败任务按退避时间重试，达到上限后置为终态并保留错误信息。</li>
 * </ul>
 */
@Slf4j
@Service
public class MessageService {

    private static final int MAX_RETRY = 3;
    private static final int RETRY_BACKOFF_MINUTES = 5;

    private final MsgTaskMapper taskMapper;
    private final MsgSendLogMapper sendLogMapper;
    private final MsgSubscriptionMapper subscriptionMapper;
    private final MessageChannel channel;

    public MessageService(MsgTaskMapper taskMapper,
                          MsgSendLogMapper sendLogMapper,
                          MsgSubscriptionMapper subscriptionMapper,
                          MessageChannel channel) {
        this.taskMapper = taskMapper;
        this.sendLogMapper = sendLogMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.channel = channel;
    }

    /** 用户在小程序授权订阅后上报，累加可发送次数。 */
    @Transactional
    public MsgSubscription subscribe(Long userId, String templateCode, int times) {
        if (templateCode == null || templateCode.isBlank()) {
            throw BizException.badRequest("模板编码不能为空");
        }
        int add = times <= 0 ? 1 : Math.min(times, 5);
        MsgSubscription subscription = subscriptionMapper.selectOne(Wrappers.<MsgSubscription>lambdaQuery()
                .eq(MsgSubscription::getUserId, userId)
                .eq(MsgSubscription::getTemplateCode, templateCode));
        if (subscription == null) {
            subscription = new MsgSubscription();
            subscription.setUserId(userId);
            subscription.setTemplateCode(templateCode);
            subscription.setRemaining(add);
            Entities.forInsert(subscription);
            subscriptionMapper.insert(subscription);
        } else {
            subscription.setRemaining(subscription.getRemaining() + add);
            subscriptionMapper.updateById(subscription);
        }
        return subscription;
    }

    public List<MsgSubscription> mySubscriptions(Long userId) {
        return subscriptionMapper.selectList(Wrappers.<MsgSubscription>lambdaQuery()
                .eq(MsgSubscription::getUserId, userId));
    }

    /**
     * 业务事件入口：创建任务并尝试立即投递。
     *
     * <p>方法内部吞掉所有异常，调用方（报名、支付、成绩发布）不会因消息失败而回滚。</p>
     */
    @Transactional
    public void publish(String scene, String bizKey, Long userId, String templateCode,
                        Map<String, Object> payload) {
        try {
            MsgTask task = createTask(scene, bizKey, userId, templateCode, payload);
            if (task != null) {
                deliver(task);
            }
        } catch (Exception e) {
            log.warn("创建/发送消息失败 scene={} bizKey={} userId={}", scene, bizKey, userId, e);
        }
    }

    private MsgTask createTask(String scene, String bizKey, Long userId, String templateCode,
                               Map<String, Object> payload) {
        Long exists = taskMapper.selectCount(Wrappers.<MsgTask>lambdaQuery()
                .eq(MsgTask::getScene, scene)
                .eq(MsgTask::getBizKey, bizKey)
                .eq(MsgTask::getUserId, userId));
        if (exists != null && exists > 0) {
            log.debug("消息任务已存在，跳过 scene={} bizKey={}", scene, bizKey);
            return null;
        }
        MsgTask task = new MsgTask();
        task.setUserId(userId);
        task.setScene(scene);
        task.setTemplateCode(templateCode);
        task.setPayloadJson(Json.write(payload));
        task.setBizKey(bizKey);
        task.setStatus(MsgTask.STATUS_PENDING);
        task.setRetryCount(0);
        task.setScheduledAt(LocalDateTime.now());
        Entities.forInsert(task);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 并发重复事件：唯一键兜底
            return null;
        }
        return task;
    }

    /**
     * 投递任务：未授权 → SKIPPED（不算失败，也不重试）；通道失败 → FAILED 并安排重试。
     */
    @Transactional
    public MsgTask deliver(MsgTask task) {
        if (!consumeSubscription(task.getUserId(), task.getTemplateCode())) {
            markTask(task, MsgTask.STATUS_SKIPPED, "用户未授权订阅该模板");
            return task;
        }

        Map<String, Object> payload = Json.read(task.getPayloadJson(), Map.class);
        MessageChannel.SendOutcome outcome = channel.send(task.getUserId(), task.getTemplateCode(),
                payload == null ? Map.of() : payload);
        writeLog(task, outcome);

        if (outcome.success()) {
            task.setSentAt(LocalDateTime.now());
            markTask(task, MsgTask.STATUS_SENT, null);
        } else {
            int retry = task.getRetryCount() == null ? 0 : task.getRetryCount();
            MsgTask update = new MsgTask();
            update.setId(task.getId());
            update.setRetryCount(retry + 1);
            update.setErrorMsg(truncate(outcome.error()));
            if (retry + 1 >= MAX_RETRY) {
                update.setStatus(MsgTask.STATUS_FAILED);
            } else {
                update.setStatus(MsgTask.STATUS_PENDING);
                update.setNextRetryAt(LocalDateTime.now().plusMinutes(RETRY_BACKOFF_MINUTES));
            }
            taskMapper.updateById(update);
            task.setStatus(update.getStatus());
            task.setRetryCount(update.getRetryCount());
            task.setErrorMsg(update.getErrorMsg());
        }
        return task;
    }

    /** 重试到期的失败任务，由定时任务驱动。 */
    @Transactional
    public int retryDue(int limit) {
        List<MsgTask> tasks = taskMapper.selectList(Wrappers.<MsgTask>lambdaQuery()
                .eq(MsgTask::getStatus, MsgTask.STATUS_PENDING)
                .isNotNull(MsgTask::getNextRetryAt)
                .lt(MsgTask::getNextRetryAt, LocalDateTime.now())
                .last("LIMIT " + Math.max(1, limit)));
        int count = 0;
        for (MsgTask task : tasks) {
            try {
                deliver(task);
                count++;
            } catch (Exception e) {
                log.warn("消息重试失败 taskId={}", task.getId(), e);
            }
        }
        return count;
    }

    /** 管理端手动重发。 */
    @Transactional
    public MsgTask resend(Long taskId) {
        MsgTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw BizException.notFound("消息任务");
        }
        if (MsgTask.STATUS_SENT.equals(task.getStatus())) {
            throw new BizException("MESSAGE_ALREADY_SENT", "该消息已发送成功，无需重发");
        }
        task.setRetryCount(0);
        task.setNextRetryAt(null);
        return deliver(task);
    }

    public PageResult<MsgTask> pageMy(Long userId, PageQuery pageQuery) {
        Page<MsgTask> page = taskMapper.selectPage(pageQuery.toPage(), Wrappers.<MsgTask>lambdaQuery()
                .eq(MsgTask::getUserId, userId)
                .orderByDesc(MsgTask::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public PageResult<MsgTask> pageAll(String status, String scene, PageQuery pageQuery) {
        Page<MsgTask> page = taskMapper.selectPage(pageQuery.toPage(), Wrappers.<MsgTask>lambdaQuery()
                .eq(status != null && !status.isBlank(), MsgTask::getStatus, status)
                .eq(scene != null && !scene.isBlank(), MsgTask::getScene, scene)
                .orderByDesc(MsgTask::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public List<MsgSendLog> logs(Long taskId) {
        return sendLogMapper.selectList(Wrappers.<MsgSendLog>lambdaQuery()
                .eq(MsgSendLog::getTaskId, taskId)
                .orderByDesc(MsgSendLog::getId));
    }

    /** 扣减一次订阅授权；不足则返回 false（任务置为 SKIPPED）。 */
    private boolean consumeSubscription(Long userId, String templateCode) {
        MsgSubscription subscription = subscriptionMapper.selectOne(Wrappers.<MsgSubscription>lambdaQuery()
                .eq(MsgSubscription::getUserId, userId)
                .eq(MsgSubscription::getTemplateCode, templateCode));
        if (subscription == null || subscription.getRemaining() == null || subscription.getRemaining() <= 0) {
            return false;
        }
        subscription.setRemaining(subscription.getRemaining() - 1);
        subscriptionMapper.updateById(subscription);
        return true;
    }

    private void markTask(MsgTask task, String status, String errorMsg) {
        MsgTask update = new MsgTask();
        update.setId(task.getId());
        update.setStatus(status);
        update.setErrorMsg(truncate(errorMsg));
        if (MsgTask.STATUS_SENT.equals(status)) {
            update.setSentAt(task.getSentAt());
        }
        taskMapper.updateById(update);
        task.setStatus(status);
        task.setErrorMsg(update.getErrorMsg());
    }

    private void writeLog(MsgTask task, MessageChannel.SendOutcome outcome) {
        MsgSendLog log = new MsgSendLog();
        log.setTaskId(task.getId());
        log.setUserId(task.getUserId());
        log.setChannel(channel.name());
        log.setTemplateCode(task.getTemplateCode());
        log.setRequestJson(truncate(outcome.request()));
        log.setResponseJson(truncate(outcome.response()));
        log.setSuccess(outcome.success() ? 1 : 0);
        log.setErrorMsg(truncate(outcome.error()));
        Entities.forInsert(log);
        sendLogMapper.insert(log);
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 480 ? value : value.substring(0, 480);
    }
}
