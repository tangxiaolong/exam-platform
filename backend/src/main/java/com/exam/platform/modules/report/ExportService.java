package com.exam.platform.modules.report;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.LoginUser;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.common.util.BizNo;
import com.exam.platform.common.util.Entities;
import com.exam.platform.common.util.Json;
import com.exam.platform.modules.exam.entity.ExamAttempt;
import com.exam.platform.modules.exam.entity.ExamRegistration;
import com.exam.platform.modules.exam.entity.ExamResult;
import com.exam.platform.modules.exam.mapper.ExamAttemptMapper;
import com.exam.platform.modules.exam.mapper.ExamRegistrationMapper;
import com.exam.platform.modules.exam.mapper.ExamResultMapper;
import com.exam.platform.modules.report.dto.ExportRequest;
import com.exam.platform.modules.report.dto.ExportTaskView;
import com.exam.platform.modules.report.entity.ExportTask;
import com.exam.platform.modules.report.mapper.ExportTaskMapper;
import com.exam.platform.modules.trade.entity.TradeOrder;
import com.exam.platform.modules.trade.mapper.TradeOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 报表导出。
 *
 * <p>遵循文档第 9 节：批量导入导出使用异步任务并提供任务状态。</p>
 *
 * <p>关键点：{@code UserContext} 是 ThreadLocal，异步线程里租户上下文为空，
 * 会被 MyBatis 租户插件过滤成空结果。因此任务提交时先记录 tenantId，
 * 执行前用 {@link UserContext#set} 回填，执行后清理。</p>
 */
@Slf4j
@Service
public class ExportService {

    private static final Set<String> TYPES = Set.of(
            ExportTask.TYPE_ORDER, ExportTask.TYPE_RESULT, ExportTask.TYPE_REGISTRATION,
            ExportTask.TYPE_SESSION_ANALYTICS);
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_ROWS = 50_000;

    private final ExportTaskMapper taskMapper;
    private final TradeOrderMapper orderMapper;
    private final ExamAttemptMapper attemptMapper;
    private final ExamRegistrationMapper registrationMapper;
    private final ExamResultMapper resultMapper;
    private final com.exam.platform.modules.exam.ExamReportService reportService;
    private final Executor executor;
    private final String exportDir;

    public ExportService(ExportTaskMapper taskMapper,
                         TradeOrderMapper orderMapper,
                         ExamAttemptMapper attemptMapper,
                         ExamRegistrationMapper registrationMapper,
                         ExamResultMapper resultMapper,
                         com.exam.platform.modules.exam.ExamReportService reportService,
                         @Qualifier("exportExecutor") Executor executor,
                         @Value("${app.export.dir:./data/exports}") String exportDir) {
        this.taskMapper = taskMapper;
        this.orderMapper = orderMapper;
        this.attemptMapper = attemptMapper;
        this.registrationMapper = registrationMapper;
        this.resultMapper = resultMapper;
        this.reportService = reportService;
        this.executor = executor;
        this.exportDir = exportDir;
    }

    /**
     * 提交导出任务：落库后异步执行，立即返回任务 ID 供前端轮询。
     *
     * <p>两个容易踩的坑都已处理：</p>
     * <ol>
     *   <li>用注入的线程池直接提交，而不是同 Bean 内调用 {@code @Async} 方法（自调用不走代理，会退化成同步执行）；</li>
     *   <li>任务在<b>事务提交之后</b>才提交给线程池，否则异步线程查不到未提交的任务行。</li>
     * </ol>
     */
    @Transactional
    public ExportTaskView submit(ExportRequest request) {
        validate(request);
        LoginUser current = UserContext.requireLogin();

        ExportTask task = new ExportTask();
        task.setTaskNo(BizNo.exportNo());
        task.setType(request.type());
        task.setParamsJson(Json.write(request));
        task.setStatus(ExportTask.STATUS_PENDING);
        task.setOperatorId(current.getUserId());
        task.setRowCount(0);
        Entities.forInsert(task);
        taskMapper.insert(task);

        // 异步线程拿不到 ThreadLocal，显式传递租户
        long tenantId = current.getTenantId() == null ? UserContext.PLATFORM_TENANT_ID : current.getTenantId();
        Long taskId = task.getId();
        dispatch(() -> runInTenant(taskId, tenantId));
        return toView(task);
    }

    private void dispatch(Runnable work) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(work);
                }
            });
        } else {
            executor.execute(work);
        }
    }

    /** 在后台线程里回填租户上下文后执行导出，执行完清理，避免线程复用污染。 */
    void runInTenant(Long taskId, long tenantId) {
        LoginUser context = new LoginUser();
        context.setTenantId(tenantId);
        UserContext.set(context);
        try {
            execute(taskId);
        } catch (Exception e) {
            log.error("导出任务执行失败 taskId={}", taskId, e);
            markFailed(taskId, e.getMessage());
        } finally {
            UserContext.clear();
        }
    }

    private void execute(Long taskId) {
        ExportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("导出任务不存在 taskId={}", taskId);
            return;
        }
        ExportTask running = new ExportTask();
        running.setId(taskId);
        running.setStatus(ExportTask.STATUS_RUNNING);
        running.setStartedAt(LocalDateTime.now());
        taskMapper.updateById(running);

        ExportRequest request = Json.read(task.getParamsJson(), ExportRequest.class);
        ExportPayload payload = switch (task.getType()) {
            case ExportTask.TYPE_ORDER -> buildOrderCsv(request);
            case ExportTask.TYPE_RESULT -> buildResultCsv(request);
            case ExportTask.TYPE_REGISTRATION -> buildRegistrationCsv(request);
            case ExportTask.TYPE_SESSION_ANALYTICS -> buildSessionAnalyticsCsv(request);
            default -> throw new BizException("EXPORT_TYPE_UNSUPPORTED", "不支持的报表类型：" + task.getType());
        };

        String fileName = task.getType().toLowerCase() + "-" + LocalDateTime.now().format(FILE_TS) + ".csv";
        Path dir = Paths.get(exportDir);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            Files.writeString(file, payload.csv(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写导出文件失败", e);
        }

        ExportTask success = new ExportTask();
        success.setId(taskId);
        success.setStatus(ExportTask.STATUS_SUCCESS);
        success.setFileName(fileName);
        success.setFilePath(dir.resolve(fileName).toAbsolutePath().toString());
        success.setRowCount(payload.rowCount());
        success.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(success);
        log.info("导出任务完成 taskId={} rows={} file={}", taskId, payload.rowCount(), fileName);
    }

    private void markFailed(Long taskId, String message) {
        ExportTask failed = new ExportTask();
        failed.setId(taskId);
        failed.setStatus(ExportTask.STATUS_FAILED);
        failed.setErrorMsg(message == null ? "未知错误" : message.substring(0, Math.min(message.length(), 480)));
        failed.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(failed);
    }

    public ExportTaskView get(Long taskId) {
        ExportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw BizException.notFound("导出任务");
        }
        return toView(task);
    }

    public PageResult<ExportTaskView> pageMy(PageQuery pageQuery) {
        Long userId = UserContext.userId();
        Page<ExportTask> page = taskMapper.selectPage(pageQuery.toPage(), Wrappers.<ExportTask>lambdaQuery()
                .eq(ExportTask::getOperatorId, userId)
                .orderByDesc(ExportTask::getId));
        return PageResult.of(page.getRecords().stream().map(this::toView).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 读取任务产物（下载用），任务未成功时拒绝。 */
    public ExportFile download(Long taskId) {
        ExportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw BizException.notFound("导出任务");
        }
        if (!ExportTask.STATUS_SUCCESS.equals(task.getStatus())) {
            throw new BizException("EXPORT_NOT_READY", "导出任务尚未完成：" + task.getStatus());
        }
        Path file = Paths.get(task.getFilePath());
        if (!Files.exists(file)) {
            throw new BizException("EXPORT_FILE_MISSING", "导出文件已被清理，请重新导出");
        }
        try {
            return new ExportFile(task.getFileName(), Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IllegalStateException("读取导出文件失败", e);
        }
    }

    // ------------------------------------------------------------------ 报表内容

    private ExportPayload buildOrderCsv(ExportRequest request) {
        List<TradeOrder> orders = orderMapper.selectList(Wrappers.<TradeOrder>lambdaQuery()
                .eq(request.status() != null && !request.status().isBlank(), TradeOrder::getStatus, request.status())
                .orderByDesc(TradeOrder::getId)
                .last("LIMIT " + MAX_ROWS));
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"订单号", "商品名称", "数量", "应付金额(元)", "状态", "下单时间", "支付时间"});
        for (TradeOrder order : orders) {
            rows.add(new String[]{
                    order.getOrderNo(),
                    order.getProductName(),
                    String.valueOf(order.getQuantity()),
                    fen2yuan(order.getPayAmountFen()),
                    order.getStatus(),
                    str(order.getCreatedAt()),
                    str(order.getPaidAt())
            });
        }
        return new ExportPayload(csv(rows), orders.size());
    }

    private ExportPayload buildResultCsv(ExportRequest request) {
        List<ExamResult> results = resultMapper.selectList(Wrappers.<ExamResult>lambdaQuery()
                .eq(request.sessionId() != null, ExamResult::getSessionId, request.sessionId())
                .orderByDesc(ExamResult::getId)
                .last("LIMIT " + MAX_ROWS));
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"作答ID", "场次ID", "用户ID", "总分", "满分", "答对题数", "题目数",
                "状态", "判分版本", "发布时间"});
        for (ExamResult result : results) {
            rows.add(new String[]{
                    String.valueOf(result.getAttemptId()),
                    String.valueOf(result.getSessionId()),
                    String.valueOf(result.getUserId()),
                    result.getTotalScore() == null ? "" : result.getTotalScore().toPlainString(),
                    String.valueOf(result.getFullScore()),
                    String.valueOf(result.getCorrectCount()),
                    String.valueOf(result.getQuestionCount()),
                    result.getStatus(),
                    String.valueOf(result.getScoringVersion()),
                    str(result.getPublishedAt())
            });
        }
        return new ExportPayload(csv(rows), results.size());
    }

    private ExportPayload buildRegistrationCsv(ExportRequest request) {
        List<ExamRegistration> registrations = registrationMapper.selectList(
                Wrappers.<ExamRegistration>lambdaQuery()
                        .eq(request.sessionId() != null, ExamRegistration::getSessionId, request.sessionId())
                        .eq(request.status() != null && !request.status().isBlank(),
                                ExamRegistration::getStatus, request.status())
                        .orderByDesc(ExamRegistration::getId)
                        .last("LIMIT " + MAX_ROWS));
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"报名ID", "场次ID", "项目ID", "用户ID", "准考证号", "状态", "报名时间"});
        for (ExamRegistration registration : registrations) {
            rows.add(new String[]{
                    String.valueOf(registration.getId()),
                    String.valueOf(registration.getSessionId()),
                    String.valueOf(registration.getProjectId()),
                    String.valueOf(registration.getUserId()),
                    registration.getTicketNo(),
                    registration.getStatus(),
                    str(registration.getCreatedAt())
            });
        }
        return new ExportPayload(csv(rows), registrations.size());
    }

    /** 场次成绩分析：概览 + 分数段分布 + 逐题正确率，单文件三段，便于直接发给讲评人。 */
    private ExportPayload buildSessionAnalyticsCsv(ExportRequest request) {
        var analytics = reportService.analytics(request.sessionId());
        var accuracy = reportService.questionAccuracy(request.sessionId());
        List<String[]> rows = new ArrayList<>();

        rows.add(new String[]{"概览"});
        rows.add(new String[]{"指标", "值"});
        rows.add(new String[]{"参考人数", String.valueOf(analytics.participantCount())});
        rows.add(new String[]{"满分", String.valueOf(analytics.fullScore())});
        rows.add(new String[]{"平均分", analytics.avgScore().toPlainString()});
        rows.add(new String[]{"最高分", analytics.maxScore().toPlainString()});
        rows.add(new String[]{"最低分", analytics.minScore().toPlainString()});
        rows.add(new String[]{"及格率(≥" + analytics.passPercent() + "%)",
                analytics.passRate().toPlainString()});

        rows.add(new String[]{""});
        rows.add(new String[]{"分数段分布"});
        rows.add(new String[]{"分数段", "人数"});
        for (com.exam.platform.modules.exam.ExamReportView.Band band : analytics.distribution()) {
            rows.add(new String[]{band.label(), String.valueOf(band.count())});
        }

        rows.add(new String[]{""});
        rows.add(new String[]{"逐题正确率"});
        rows.add(new String[]{"题目ID", "题干", "作答次数", "答对次数", "正确率%"});
        for (com.exam.platform.modules.exam.ExamReportView.QuestionAccuracy item : accuracy) {
            rows.add(new String[]{String.valueOf(item.questionId()), item.stem(),
                    String.valueOf(item.answered()), String.valueOf(item.correct()),
                    item.accuracy().toPlainString()});
        }

        rows.add(new String[]{""});
        rows.add(new String[]{"成绩排行（前 20）"});
        rows.add(new String[]{"名次", "考生", "总分"});
        for (com.exam.platform.modules.exam.ExamReportView.LeaderboardEntry entry
                : reportService.leaderboard(request.sessionId(), 20)) {
            rows.add(new String[]{String.valueOf(entry.rank()), entry.userName(),
                    entry.totalScore().toPlainString()});
        }
        return new ExportPayload(csv(rows), analytics.participantCount());
    }

    // ------------------------------------------------------------------ 工具

    private ExportTaskView toView(ExportTask task) {
        String url = ExportTask.STATUS_SUCCESS.equals(task.getStatus())
                ? "/api/v1/admin/exports/" + task.getId() + "/download" : null;
        return new ExportTaskView(task.getId(), task.getTaskNo(), task.getType(), task.getStatus(),
                task.getFileName(), url, task.getRowCount(), task.getErrorMsg(),
                task.getStartedAt(), task.getFinishedAt(), task.getCreatedAt());
    }

    private String csv(List<String[]> rows) {
        StringBuilder sb = new StringBuilder("\uFEFF"); // BOM，避免 Excel 打开中文乱码
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(escape(row[i]));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String str(LocalDateTime time) {
        return time == null ? "" : time.toString().replace('T', ' ');
    }

    private String fen2yuan(Long fen) {
        return String.format("%.2f", (fen == null ? 0 : fen) / 100.0);
    }

    private void validate(ExportRequest request) {
        if (request.type() == null || !TYPES.contains(request.type())) {
            throw BizException.badRequest("报表类型不合法，支持 ORDER/RESULT/REGISTRATION/SESSION_ANALYTICS");
        }
        if (ExportTask.TYPE_SESSION_ANALYTICS.equals(request.type()) && request.sessionId() == null) {
            throw BizException.badRequest("成绩分析导出需要指定场次");
        }
    }

    /** 导出产物。 */
    private record ExportPayload(String csv, int rowCount) {
    }

    /** 下载文件载体。 */
    public record ExportFile(String fileName, byte[] content) {
    }
}
