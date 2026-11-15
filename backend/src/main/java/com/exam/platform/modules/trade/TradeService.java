package com.exam.platform.modules.trade;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.BizNo;
import com.exam.platform.common.util.Entities;
import com.exam.platform.common.util.Json;
import com.exam.platform.modules.entitlement.EntitlementService;
import com.exam.platform.modules.message.MessageService;
import com.exam.platform.modules.message.MessageTemplates;
import com.exam.platform.modules.message.entity.MsgTask;
import com.exam.platform.modules.trade.dto.CreateOrderRequest;
import com.exam.platform.modules.trade.dto.NotifyResult;
import com.exam.platform.modules.trade.dto.PaymentCreateResponse;
import com.exam.platform.modules.trade.dto.WechatNotifyRequest;
import com.exam.platform.modules.trade.entity.MallProduct;
import com.exam.platform.modules.trade.entity.TradeOrder;
import com.exam.platform.modules.trade.entity.TradePayment;
import com.exam.platform.modules.trade.entity.TradePaymentNotify;
import com.exam.platform.modules.trade.mapper.MallProductMapper;
import com.exam.platform.modules.trade.mapper.TradeOrderMapper;
import com.exam.platform.modules.trade.mapper.TradePaymentMapper;
import com.exam.platform.modules.trade.mapper.TradePaymentNotifyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交易与支付。
 *
 * <p>关键约束：订单金额只按服务端商品价格计算；支付成功只认回调（验签 + 金额 + 币种 + 状态），
 * 且回调按 transaction_id 幂等，重复通知不重复发放权益。</p>
 */
@Slf4j
@Service
public class TradeService {

    private static final String CHANNEL_WECHAT = "WECHAT";
    private static final String CURRENCY_CNY = "CNY";
    private static final String TRADE_SUCCESS = "SUCCESS";
    private static final int ORDER_EXPIRE_MINUTES = 30;

    private final MallProductMapper productMapper;
    private final TradeOrderMapper orderMapper;
    private final TradePaymentMapper paymentMapper;
    private final TradePaymentNotifyMapper notifyMapper;
    private final EntitlementService entitlementService;
    private final MessageService messageService;

    private final String appSecret;
    private final boolean wechatMock;

    public TradeService(MallProductMapper productMapper,
                        TradeOrderMapper orderMapper,
                        TradePaymentMapper paymentMapper,
                        TradePaymentNotifyMapper notifyMapper,
                        EntitlementService entitlementService,
                        MessageService messageService,
                        @Value("${app.wechat.app-secret:wx-demo-secret}") String appSecret,
                        @Value("${app.wechat.mock:true}") boolean wechatMock) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.paymentMapper = paymentMapper;
        this.notifyMapper = notifyMapper;
        this.entitlementService = entitlementService;
        this.messageService = messageService;
        this.appSecret = appSecret;
        this.wechatMock = wechatMock;
    }

    // ---------------------------------------------------------------- 商品

    public PageResult<MallProduct> pageProducts(String keyword, boolean onlyOnSale, PageQuery pageQuery) {
        Page<MallProduct> page = productMapper.selectPage(pageQuery.toPage(), Wrappers.<MallProduct>lambdaQuery()
                .eq(onlyOnSale, MallProduct::getStatus, MallProduct.STATUS_ON_SALE)
                .like(keyword != null && !keyword.isBlank(), MallProduct::getName, keyword)
                .orderByAsc(MallProduct::getSortNo)
                .orderByDesc(MallProduct::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public MallProduct getProduct(Long id) {
        MallProduct product = productMapper.selectById(id);
        if (product == null) {
            throw BizException.notFound("商品");
        }
        return product;
    }

    @Transactional
    public MallProduct saveProduct(MallProduct product) {
        if (product.getPriceFen() == null || product.getPriceFen() < 0) {
            throw BizException.badRequest("商品价格必须为非负整数（单位：分）");
        }
        Entities.forInsert(product);
        if (product.getStatus() == null) {
            product.setStatus(MallProduct.STATUS_ON_SALE);
        }
        productMapper.insert(product);
        return product;
    }

    @Transactional
    public MallProduct updateProduct(Long id, MallProduct product) {
        getProduct(id);
        Entities.forUpdate(product, id);
        productMapper.updateById(product);
        return getProduct(id);
    }

    @Transactional
    public void deleteProduct(Long id) {
        getProduct(id);
        productMapper.deleteById(id);
    }

    // ---------------------------------------------------------------- 订单

    @Transactional
    public TradeOrder createOrder(Long userId, CreateOrderRequest request) {
        MallProduct product = getProduct(request.productId());
        if (!MallProduct.STATUS_ON_SALE.equals(product.getStatus())) {
            throw new BizException("ORDER_PRODUCT_OFF_SALE", "商品已下架");
        }
        int quantity = request.quantity() == null ? 1 : request.quantity();
        if (quantity < 1 || quantity > 10) {
            throw BizException.badRequest("购买数量需在 1~10 之间");
        }
        long totalAmount = product.getPriceFen() * quantity;

        TradeOrder order = new TradeOrder();
        order.setOrderNo(BizNo.orderNo());
        order.setUserId(userId);
        order.setProductId(product.getId());
        order.setProductName(product.getName());
        order.setQuantity(quantity);
        order.setTotalAmountFen(totalAmount);
        order.setDiscountAmountFen(0L);
        order.setPayAmountFen(totalAmount);
        order.setStatus(TradeOrder.STATUS_PENDING_PAYMENT);
        order.setExpireAt(LocalDateTime.now().plusMinutes(ORDER_EXPIRE_MINUTES));
        order.setRemark(request.remark());
        Entities.forInsert(order);
        orderMapper.insert(order);
        return order;
    }

    public PageResult<TradeOrder> pageMyOrders(Long userId, String status, PageQuery pageQuery) {
        Page<TradeOrder> page = orderMapper.selectPage(pageQuery.toPage(), Wrappers.<TradeOrder>lambdaQuery()
                .eq(TradeOrder::getUserId, userId)
                .eq(status != null && !status.isBlank(), TradeOrder::getStatus, status)
                .orderByDesc(TradeOrder::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public PageResult<TradeOrder> pageAllOrders(String status, String keyword, PageQuery pageQuery) {
        Page<TradeOrder> page = orderMapper.selectPage(pageQuery.toPage(), Wrappers.<TradeOrder>lambdaQuery()
                .eq(status != null && !status.isBlank(), TradeOrder::getStatus, status)
                .and(keyword != null && !keyword.isBlank(),
                        w -> w.like(TradeOrder::getOrderNo, keyword).or().like(TradeOrder::getProductName, keyword))
                .orderByDesc(TradeOrder::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public TradeOrder getOwnedOrder(Long orderId, Long userId) {
        TradeOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BizException.notFound("订单");
        }
        if (!order.getUserId().equals(userId)) {
            throw BizException.forbidden("不能访问他人订单");
        }
        return order;
    }

    // ---------------------------------------------------------------- 支付

    @Transactional
    public PaymentCreateResponse createPayment(Long orderId, Long userId) {
        TradeOrder order = getOwnedOrder(orderId, userId);
        if (TradeOrder.STATUS_PAID.equals(order.getStatus()) || TradeOrder.STATUS_COMPLETED.equals(order.getStatus())) {
            throw new BizException("ORDER_ALREADY_PAID", "订单已支付");
        }
        if (!TradeOrder.STATUS_PENDING_PAYMENT.equals(order.getStatus())) {
            throw new BizException("ORDER_STATUS_INVALID", "当前订单状态不可支付：" + order.getStatus());
        }
        LocalDateTime now = LocalDateTime.now();
        if (order.getExpireAt() != null && now.isAfter(order.getExpireAt())) {
            TradeOrder closed = new TradeOrder();
            closed.setId(order.getId());
            closed.setStatus(TradeOrder.STATUS_CLOSED);
            orderMapper.updateById(closed);
            throw new BizException("ORDER_EXPIRED", "订单已超时关闭，请重新下单");
        }

        TradePayment payment = paymentMapper.selectOne(Wrappers.<TradePayment>lambdaQuery()
                .eq(TradePayment::getOrderId, order.getId())
                .eq(TradePayment::getStatus, TradePayment.STATUS_CREATED)
                .orderByDesc(TradePayment::getId)
                .last("LIMIT 1"));
        if (payment == null) {
            payment = new TradePayment();
            payment.setOrderId(order.getId());
            payment.setOrderNo(order.getOrderNo());
            payment.setPayNo(BizNo.payNo());
            payment.setChannel(CHANNEL_WECHAT);
            payment.setAmountFen(order.getPayAmountFen());
            payment.setStatus(TradePayment.STATUS_CREATED);
            Entities.forInsert(payment);
            Map<String, Object> prepay = new LinkedHashMap<>();
            prepay.put("mock", wechatMock);
            prepay.put("payNo", payment.getPayNo());
            prepay.put("amountFen", payment.getAmountFen());
            prepay.put("orderNo", payment.getOrderNo());
            payment.setPrepayJson(Json.write(prepay));
            paymentMapper.insert(payment);
        }

        String mockNotifyUrl = wechatMock ? "/api/v1/dev/payments/" + payment.getPayNo() + "/mock-notify" : null;
        return new PaymentCreateResponse(payment.getPayNo(), order.getOrderNo(), payment.getAmountFen(),
                CHANNEL_WECHAT, payment.getPrepayJson(), mockNotifyUrl);
    }

    /**
     * 处理支付回调。为避免失败原因丢失，本方法不抛业务异常，
     * 统一通过 {@link NotifyResult} 返回，控制器据此应答支付平台。
     */
    @Transactional
    public NotifyResult handleNotify(WechatNotifyRequest request, String rawBody) {
        TradePaymentNotify record = new TradePaymentNotify();
        record.setPayNo(request.outTradeNo());
        record.setChannel(CHANNEL_WECHAT);
        record.setRawBody(rawBody);
        record.setProcessed(0);
        Entities.forInsert(record);

        String expectedSign = sign(request.outTradeNo(), request.amountFen());
        if (request.sign() == null || !expectedSign.equalsIgnoreCase(request.sign())) {
            record.setVerifyResult("SIGN_INVALID");
            notifyMapper.insert(record);
            return NotifyResult.fail("PAY_SIGN_INVALID", "支付通知验签失败");
        }
        if (!TRADE_SUCCESS.equals(request.tradeState())) {
            record.setVerifyResult("TRADE_STATE_" + request.tradeState());
            notifyMapper.insert(record);
            return NotifyResult.fail("PAY_TRADE_STATE_INVALID", "交易状态非成功");
        }
        if (!CURRENCY_CNY.equals(request.currency())) {
            record.setVerifyResult("CURRENCY_MISMATCH");
            notifyMapper.insert(record);
            return NotifyResult.fail("PAY_CURRENCY_MISMATCH", "币种不匹配");
        }

        TradePayment payment = paymentMapper.selectOne(Wrappers.<TradePayment>lambdaQuery()
                .eq(TradePayment::getPayNo, request.outTradeNo()));
        if (payment == null) {
            record.setVerifyResult("PAYMENT_NOT_FOUND");
            notifyMapper.insert(record);
            return NotifyResult.fail("PAY_PAYMENT_NOT_FOUND", "支付单不存在");
        }
        if (!payment.getAmountFen().equals(request.amountFen())) {
            record.setVerifyResult("AMOUNT_MISMATCH");
            notifyMapper.insert(record);
            return NotifyResult.fail("PAY_AMOUNT_MISMATCH", "支付金额与订单金额不一致");
        }
        if (TradePayment.STATUS_PAID.equals(payment.getStatus())) {
            record.setVerifyResult("DUPLICATE_NOTIFY");
            record.setProcessed(1);
            notifyMapper.insert(record);
            return NotifyResult.ok();
        }

        LocalDateTime now = LocalDateTime.now();
        TradePayment paid = new TradePayment();
        paid.setId(payment.getId());
        paid.setStatus(TradePayment.STATUS_PAID);
        paid.setTransactionId(request.transactionId());
        paid.setPaidAt(now);
        paymentMapper.updateById(paid);

        TradeOrder order = orderMapper.selectById(payment.getOrderId());
        TradeOrder paidOrder = new TradeOrder();
        paidOrder.setId(order.getId());
        paidOrder.setStatus(TradeOrder.STATUS_COMPLETED);
        paidOrder.setPaidAt(now);
        orderMapper.updateById(paidOrder);

        grantEntitlement(order, request.transactionId(), now);

        // 支付成功通知
        messageService.publish(MsgTask.SCENE_PAYMENT, "order-" + order.getId(), order.getUserId(),
                MessageTemplates.PAYMENT_SUCCESS, Map.of(
                        "orderNo", order.getOrderNo(),
                        "productName", order.getProductName(),
                        "amountFen", order.getPayAmountFen()));

        record.setVerifyResult("PASS");
        record.setProcessed(1);
        notifyMapper.insert(record);
        return NotifyResult.ok();
    }

    /** 支付成功 → 发放权益（按 transactionId 幂等）。 */
    private void grantEntitlement(TradeOrder order, String transactionId, LocalDateTime now) {
        MallProduct product = productMapper.selectById(order.getProductId());
        if (product == null) {
            log.warn("支付成功但商品不存在 orderId={}", order.getId());
            return;
        }
        String entitlementType = switch (product.getType()) {
            case MallProduct.TYPE_MEMBER -> "MEMBER";
            case MallProduct.TYPE_COURSE -> "COURSE";
            default -> "QUESTION_BANK";
        };
        LocalDateTime expireAt = product.getValidityDays() != null && product.getValidityDays() > 0
                ? now.plusDays(product.getValidityDays()) : null;
        entitlementService.grant(order.getUserId(), entitlementType, product.getResourceType(),
                product.getResourceId(), order.getQuantity(), expireAt, order.getId(),
                "PAY-" + transactionId);
    }

    /** 回调签名：sha256(payNo|amountFen|appSecret)，真实接入时替换为微信 V3 验签。 */
    public String sign(String payNo, Long amountFen) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = payNo + "|" + amountFen + "|" + appSecret;
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    public boolean isWechatMock() {
        return wechatMock;
    }

    public TradePayment getPaymentByPayNo(String payNo) {
        return paymentMapper.selectOne(Wrappers.<TradePayment>lambdaQuery()
                .eq(TradePayment::getPayNo, payNo));
    }

    public long sumPaidAmountFen() {
        return orderMapper.sumPaidAmountFen(com.exam.platform.common.security.UserContext.tenantId());
    }
}
