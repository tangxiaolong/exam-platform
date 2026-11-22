package com.exam.platform.common.wechat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地联调用的支付网关。行为与真实网关保持同一套接口语义：
 * 下单返回可用的 prepay_id，查单返回「未支付」，关单总是成功。
 *
 * <p>刻意不让它返回 SUCCESS——真实环境支付成功只可能来自回调或主动查单，
 * mock 里伪造成功会让「回调丢了怎么办」这条路径在本地永远测不到。</p>
 */
public class MockWechatPayGateway implements WechatPayGateway {

    @Override
    public boolean mock() {
        return true;
    }

    @Override
    public PrepayResult prepay(PrepayCommand command) {
        String prepayId = "mock-prepay-" + command.outTradeNo();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("mock", "true");
        params.put("prepayId", prepayId);
        params.put("payNo", command.outTradeNo());
        params.put("amountFen", String.valueOf(command.amountFen()));
        return new PrepayResult(prepayId, params);
    }

    @Override
    public Transaction query(String outTradeNo) {
        return new Transaction(outTradeNo, null, "NOTPAY", null, "CNY", null);
    }

    @Override
    public void close(String outTradeNo) {
        // mock 下无需真的关单
    }
}
