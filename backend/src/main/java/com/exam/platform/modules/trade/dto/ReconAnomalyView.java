package com.exam.platform.modules.trade.dto;

import java.time.LocalDateTime;

/**
 * 对账异常视图。只暴露排查所需字段，不返回原始报文。
 *
 * <p>带上处置信息，是因为对账的价值不在于「检出」，而在于「每条都有结论」：
 * 运营看列表时最需要区分的就是「还没人管」和「已确认处理 / 确认误报」。</p>
 */
public record ReconAnomalyView(
        Long id,
        String anomalyType,
        String bizType,
        Long bizId,
        String bizNo,
        String expectedValue,
        String actualValue,
        String detail,
        String handleStatus,
        String handleRemark,
        Long handledBy,
        LocalDateTime handledAt
) {
}
