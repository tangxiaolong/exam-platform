package com.exam.platform.modules.dashboard;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.platform.common.api.Result;
import com.exam.platform.modules.content.entity.ExamProject;
import com.exam.platform.modules.content.mapper.ExamProjectMapper;
import com.exam.platform.modules.exam.entity.ExamAttempt;
import com.exam.platform.modules.exam.entity.ExamRegistration;
import com.exam.platform.modules.exam.entity.ExamSession;
import com.exam.platform.modules.exam.mapper.ExamAttemptMapper;
import com.exam.platform.modules.exam.mapper.ExamRegistrationMapper;
import com.exam.platform.modules.exam.mapper.ExamSessionMapper;
import com.exam.platform.modules.question.entity.QbQuestion;
import com.exam.platform.modules.question.mapper.QbQuestionMapper;
import com.exam.platform.modules.system.entity.SysUser;
import com.exam.platform.modules.system.mapper.SysUserMapper;
import com.exam.platform.modules.trade.TradeService;
import com.exam.platform.modules.trade.entity.TradeOrder;
import com.exam.platform.modules.trade.mapper.TradeOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 运营后台工作台数据概览。 */
@RestController
@RequestMapping("/v1/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final SysUserMapper userMapper;
    private final ExamProjectMapper projectMapper;
    private final QbQuestionMapper questionMapper;
    private final ExamSessionMapper sessionMapper;
    private final ExamRegistrationMapper registrationMapper;
    private final ExamAttemptMapper attemptMapper;
    private final TradeOrderMapper orderMapper;
    private final TradeService tradeService;

    @GetMapping("/overview")
    public Result<Overview> overview() {
        Overview overview = new Overview(
                userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()),
                projectMapper.selectCount(Wrappers.<ExamProject>lambdaQuery()),
                questionMapper.selectCount(Wrappers.<QbQuestion>lambdaQuery()),
                sessionMapper.selectCount(Wrappers.<ExamSession>lambdaQuery()),
                registrationMapper.selectCount(Wrappers.<ExamRegistration>lambdaQuery()),
                attemptMapper.selectCount(Wrappers.<ExamAttempt>lambdaQuery()),
                orderMapper.selectCount(Wrappers.<TradeOrder>lambdaQuery()),
                orderMapper.selectCount(Wrappers.<TradeOrder>lambdaQuery()
                        .in(TradeOrder::getStatus, TradeOrder.STATUS_PAID, TradeOrder.STATUS_COMPLETED)),
                tradeService.sumPaidAmountFen()
        );
        return Result.ok(overview);
    }

    /**
     * 近 N 日趋势（含今天）：每日报名数、答卷数、已付订单数、收入（分）。
     * 默认 7 天、最多 30 天——工作台趋势卡是「最近的手感」，不是报表工具（精确统计去报表导出）。
     */
    @GetMapping("/trends")
    public Result<List<TrendPoint>> trends(@RequestParam(defaultValue = "7") int days) {
        int span = Math.min(Math.max(days, 1), 30);
        LocalDate start = LocalDate.now().minusDays(span - 1);

        QueryWrapper<ExamRegistration> registrationQuery = new QueryWrapper<>();
        registrationQuery.select("DATE(created_at) AS d", "COUNT(*) AS c")
                .ge("created_at", start.atStartOfDay())
                .groupBy("DATE(created_at)");
        Map<LocalDate, Long> registrations = rowsToDayMap(registrationMapper.selectMaps(registrationQuery));

        QueryWrapper<ExamAttempt> attemptQuery = new QueryWrapper<>();
        attemptQuery.select("DATE(created_at) AS d", "COUNT(*) AS c")
                .ge("created_at", start.atStartOfDay())
                .groupBy("DATE(created_at)");
        Map<LocalDate, Long> attempts = rowsToDayMap(attemptMapper.selectMaps(attemptQuery));

        QueryWrapper<TradeOrder> orderQuery = new QueryWrapper<>();
        orderQuery.select("DATE(paid_at) AS d", "COUNT(*) AS c", "COALESCE(SUM(pay_amount_fen), 0) AS amt")
                .isNotNull("paid_at")
                .ge("paid_at", start.atStartOfDay())
                .groupBy("DATE(paid_at)");
        Map<LocalDate, Long> paidOrders = new HashMap<>();
        Map<LocalDate, Long> revenue = new HashMap<>();
        for (Map<String, Object> row : orderMapper.selectMaps(orderQuery)) {
            LocalDate day = toDay(cell(row, "d"));
            if (day == null) {
                continue;
            }
            Object count = cell(row, "c");
            Object amount = cell(row, "amt");
            paidOrders.put(day, count instanceof Number n ? n.longValue() : 0L);
            revenue.put(day, amount instanceof Number n ? n.longValue() : 0L);
        }

        List<TrendPoint> points = new ArrayList<>();
        for (int i = 0; i < span; i++) {
            LocalDate day = start.plusDays(i);
            points.add(new TrendPoint(day.toString(),
                    registrations.getOrDefault(day, 0L),
                    attempts.getOrDefault(day, 0L),
                    paidOrders.getOrDefault(day, 0L),
                    revenue.getOrDefault(day, 0L)));
        }
        return Result.ok(points);
    }

    /** 单日指标：收入单位为分，与全站金额口径一致。 */
    public record TrendPoint(String date, long registrations, long attempts,
                             long paidOrders, long revenueFen) {
    }

    private Map<LocalDate, Long> rowsToDayMap(List<Map<String, Object>> rows) {
        Map<LocalDate, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            LocalDate day = toDay(cell(row, "d"));
            Object count = cell(row, "c");
            if (day != null && count instanceof Number number) {
                result.put(day, number.longValue());
            }
        }
        return result;
    }

    /** H2 返回 java.sql.Date、MySQL 可能返回 LocalDate——两种都接受，列别名大小写也不敏感。 */
    private LocalDate toDay(Object value) {
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate();
        }
        return null;
    }

    private Object cell(Map<String, Object> row, String name) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 概览指标（金额单位为分，与接口规范一致）。 */
    public record Overview(
            long userCount,
            long projectCount,
            long questionCount,
            long sessionCount,
            long registrationCount,
            long attemptCount,
            long orderCount,
            long paidOrderCount,
            long paidAmountFen
    ) {
    }
}
