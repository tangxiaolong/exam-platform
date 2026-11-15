package com.exam.platform.modules.dashboard;

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
import org.springframework.web.bind.annotation.RestController;

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
