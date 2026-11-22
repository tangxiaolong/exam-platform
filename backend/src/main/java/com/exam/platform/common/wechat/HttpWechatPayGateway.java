package com.exam.platform.common.wechat;

import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 微信支付 v3 的真实实现：JSAPI 下单 / 主动查单 / 关单。
 *
 * <p>接口地址与报文格式按官方 v3 文档。三处容易出错的地方在这里统一处理：</p>
 * <ul>
 *   <li>下单返回的 {@code prepay_id} 不能直接给小程序，还要用商户私钥做<b>二次签名</b>
 *       生成 {@code paySign}，否则 wx.requestPayment 一律报「签名错误」；</li>
 *   <li>查单的 query 参数必须带 {@code mchid}，且这段 query 会参与请求签名，顺序不能改；</li>
 *   <li>关单成功返回 204 无 body，不能按 JSON 解析。</li>
 * </ul>
 */
@Slf4j
public class HttpWechatPayGateway implements WechatPayGateway {

    private final WechatPayHttpClient client;
    private final WechatPaySigner signer;
    private final WechatPayProperties properties;

    public HttpWechatPayGateway(WechatPayHttpClient client, WechatPaySigner signer, WechatPayProperties properties) {
        this.client = client;
        this.signer = signer;
        this.properties = properties;
    }

    @Override
    public boolean mock() {
        return false;
    }

    @Override
    public PrepayResult prepay(PrepayCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", properties.getAppId());
        body.put("mchid", properties.getMchId());
        body.put("description", command.description());
        body.put("out_trade_no", command.outTradeNo());
        body.put("notify_url", properties.getNotifyUrl());
        body.put("amount", Map.of("total", command.amountFen(), "currency", "CNY"));
        if (command.openId() != null && !command.openId().isBlank()) {
            body.put("payer", Map.of("openid", command.openId()));
        }

        String response = client.post("/v3/pay/transactions/jsapi", Json.write(body));
        Map<String, Object> parsed = Json.read(response, new TypeReference<Map<String, Object>>() {
        });
        String prepayId = parsed == null ? null : (String) parsed.get("prepay_id");
        if (prepayId == null || prepayId.isBlank()) {
            throw new BizException("PAY_PREPAY_FAILED", "微信下单未返回 prepay_id：" + response);
        }
        return new PrepayResult(prepayId, buildPayParams(prepayId));
    }

    /** 小程序调起支付需要的参数，paySign 用商户私钥二次签名。 */
    private Map<String, String> buildPayParams(String prepayId) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String pkg = "prepay_id=" + prepayId;
        String message = properties.getAppId() + "\n" + timestamp + "\n" + nonce + "\n" + pkg + "\n";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("appId", properties.getAppId());
        params.put("timeStamp", timestamp);
        params.put("nonceStr", nonce);
        params.put("package", pkg);
        params.put("signType", "RSA");
        params.put("paySign", signer.sign(message));
        return params;
    }

    @Override
    public Transaction query(String outTradeNo) {
        String path = "/v3/pay/transactions/out-trade-no/" + outTradeNo + "?mchid=" + properties.getMchId();
        String response = client.get(path);
        Map<String, Object> parsed = Json.read(response, new TypeReference<Map<String, Object>>() {
        });
        if (parsed == null) {
            throw new BizException("PAY_QUERY_EMPTY", "查单返回为空：" + outTradeNo);
        }
        Long amountFen = null;
        Object amount = parsed.get("amount");
        if (amount instanceof Map<?, ?> amountMap && amountMap.get("total") instanceof Number total) {
            amountFen = total.longValue();
        }
        return new Transaction(outTradeNo,
                (String) parsed.get("transaction_id"),
                (String) parsed.get("trade_state"),
                amountFen,
                (String) parsed.get("currency"),
                (String) parsed.get("success_time"));
    }

    @Override
    public void close(String outTradeNo) {
        String path = "/v3/pay/transactions/out-trade-no/" + outTradeNo + "/close";
        try {
            client.post(path, Json.write(Map.of("mchid", properties.getMchId())));
        } catch (BizException e) {
            // 订单在微信侧已不存在/已关闭：对调用方而言目标已达成，不算失败
            if (e.getMessage() != null && (e.getMessage().contains("ORDER_NOT_EXIST")
                    || e.getMessage().contains("ORDER_CLOSED")
                    || e.getMessage().contains(" 404 "))) {
                log.info("关单时微信侧订单已不存在，视为已关闭 outTradeNo={}", outTradeNo);
                return;
            }
            throw e;
        }
    }
}
