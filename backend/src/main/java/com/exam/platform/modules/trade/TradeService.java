package com.exam.platform.modules.trade;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.BizNo;
import com.exam.platform.common.util.Entities;
import com.exam.platform.common.util.Json;
import com.exam.platform.common.wechat.WechatPayGateway;
import com.exam.platform.modules.entitlement.EntitlementService;
import com.exam.platform.modules.message.MessageService;
import com.exam.platform.modules.message.MessageTemplates;
import com.exam.platform.modules.message.entity.MsgTask;
import com.exam.platform.modules.system.entity.SysUserIdentity;
import com.exam.platform.modules.system.mapper.SysUserIdentityMapper;
import com.exam.platform.modules.trade.dto.CreateOrderRequest;
import com.exam.platform.modules.trade.dto.NotifyResult;
import com.exam.platform.modules.trade.dto.PaymentCreateResponse;
import com.exam.platform.modules.trade.dto.WechatNotifyRequest;
import com.exam.platform.modules.trade.entity.MallProduct;
import com.exam.platform.modules.trade.entity.MallSku;
import com.exam.platform.modules.trade.entity.TradeOrder;
import com.exam.platform.modules.trade.entity.TradeOrderItem;
import com.exam.platform.modules.trade.entity.TradePayment;
import com.exam.platform.modules.trade.entity.TradePaymentNotify;
import com.exam.platform.modules.trade.mapper.MallProductMapper;
import com.exam.platform.modules.trade.mapper.MallSkuMapper;
import com.exam.platform.modules.trade.mapper.TradeOrderItemMapper;
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
import java.util.List;
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
    private final WechatPayGateway payGateway;
    private final SysUserIdentityMapper identityMapper;
    private final MallSkuMapper skuMapper;
    private final TradeOrderItemMapper orderItemMapper;
    private final CouponService couponService;

    private final String appSecret;

    public TradeService(MallProductMapper productMapper,
                        TradeOrderMapper orderMapper,
                        TradePaymentMapper paymentMapper,
                        TradePaymentNotifyMapper notifyMapper,
                        EntitlementService entitlementService,
                        MessageService messageService,
                        WechatPayGateway payGateway,
                        SysUserIdentityMapper identityMapper,
                        MallSkuMapper skuMapper,
                        TradeOrderItemMapper orderItemMapper,
                        CouponService couponService,
                        @Value("${app.wechat.app-secret:wx-demo-secret}") String appSecret) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.paymentMapper = paymentMapper;
        this.notifyMapper = notifyMapper;
        this.entitlementService = entitlementService;
        this.messageService = messageService;
        this.payGateway = payGateway;
        this.identityMapper = identityMapper;
        this.skuMapper = skuMapper;
        this.orderItemMapper = orderItemMapper;
        this.couponService = couponService;
        this.appSecret = appSecret;
    }

    // ---------------------------------------------------------------- 商品

    public PageResult<MallProduct> pageProducts(String keyword, boolean onlyOnSale, PageQuery pageQuery) {
        return pageProducts(keyword, onlyOnSale ? MallProduct.STATUS_ON_SALE : null, null, pageQuery);
    }

    /**
     * 商品列表。
     *
     * <p>{@code status} / {@code type} 为可选的精确筛选；关键词同时匹配编码与名称——
     * 运营通常拿着编码来核对，只按名称 like 会让「编码明明存在却查不到」。</p>
     */
    public PageResult<MallProduct> pageProducts(String keyword, String status, String type, PageQuery pageQuery) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        String kw = hasKeyword ? keyword.trim() : null;
        Page<MallProduct> page = productMapper.selectPage(pageQuery.toPage(), Wrappers.<MallProduct>lambdaQuery()
                .eq(status != null && !status.isBlank(), MallProduct::getStatus, status)
                .eq(type != null && !type.isBlank(), MallProduct::getType, type)
                .and(hasKeyword, w -> w.like(MallProduct::getCode, kw).or().like(MallProduct::getName, kw))
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
        ensureDefaultSku(product);
        return product;
    }

    /**
     * 商品没有 SKU 时补一个默认 SKU。
     *
     * <p>没有 SKU 的后果不是「少个规格」，而是订单明细写不出 sku_id、部分退款无从归因。
     * 所以新建商品必须带默认 SKU，老商品由 V11 迁移回填。</p>
     */
    private void ensureDefaultSku(MallProduct product) {
        createDefaultSku(product);
    }

    /**
     * 建默认 SKU 并返回。
     *
     * <p>并发下两个请求可能同时建，撞唯一索引后重查一次即可：这里的目标是
     * 「一定有一个可用 SKU」，而不是「只建一次」。建不出来才是事故——
     * 下单会失败，订单明细与退款都无从归因。</p>
     */
    private MallSku createDefaultSku(MallProduct product) {
        MallSku existing = skuMapper.selectOne(Wrappers.<MallSku>lambdaQuery()
                .eq(MallSku::getProductId, product.getId())
                .eq(MallSku::getCode, "DEFAULT")
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        MallSku sku = new MallSku();
        sku.setProductId(product.getId());
        sku.setCode("DEFAULT");
        sku.setName(product.getName());
        sku.setPriceFen(product.getPriceFen());
        sku.setOriginalPriceFen(product.getOriginalPriceFen());
        sku.setDurationDays(product.getValidityDays());
        sku.setStatus(MallSku.STATUS_ON_SALE);
        sku.setSortNo(0);
        Entities.forInsert(sku);
        try {
            skuMapper.insert(sku);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return skuMapper.selectOne(Wrappers.<MallSku>lambdaQuery()
                    .eq(MallSku::getProductId, product.getId())
                    .eq(MallSku::getCode, "DEFAULT")
                    .last("LIMIT 1"));
        }
        return sku;
    }

    @Transactional
    public MallProduct updateProduct(Long id, MallProduct product) {
        getProduct(id);
        Entities.forUpdate(product, id);
        productMapper.updateById(product);
        syncDefaultSku(id);
        return getProduct(id);
    }

    /** 默认 SKU 是商品「无规格」的镜像：商品改价后它必须跟着走，否则老前端看到的还是旧价。 */
    private void syncDefaultSku(Long productId) {
        MallSku sku = skuMapper.selectOne(Wrappers.<MallSku>lambdaQuery()
                .eq(MallSku::getProductId, productId)
                .eq(MallSku::getCode, "DEFAULT")
                .last("LIMIT 1"));
        if (sku == null) {
            return;
        }
        MallProduct product = productMapper.selectById(productId);
        if (product == null) {
            return;
        }
        sku.setName(product.getName());
        sku.setPriceFen(product.getPriceFen());
        sku.setOriginalPriceFen(product.getOriginalPriceFen());
        sku.setDurationDays(product.getValidityDays());
        skuMapper.updateById(sku);
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
        MallSku sku = resolveSku(product, request.skuId());
        long unitPrice = sku.getPriceFen() == null ? product.getPriceFen() : sku.getPriceFen();
        long totalAmount = unitPrice * quantity;

        long discount = 0L;
        if (request.couponGrantId() != null) {
            // 抵扣额只由服务端按券规则计算，客户端只表达「用哪张」
            discount = couponService.evaluate(userId, request.couponGrantId(), product.getId(), totalAmount);
        }
        long payAmount = totalAmount - discount;
        if (payAmount < 1) {
            throw new BizException("ORDER_AMOUNT_TOO_SMALL", "优惠后金额不足 1 分，无法支付");
        }

        TradeOrder order = new TradeOrder();
        order.setOrderNo(BizNo.orderNo());
        order.setUserId(userId);
        order.setProductId(product.getId());
        order.setProductName(product.getName());
        order.setQuantity(quantity);
        order.setTotalAmountFen(totalAmount);
        order.setDiscountAmountFen(discount);
        order.setPayAmountFen(payAmount);
        order.setStatus(TradeOrder.STATUS_PENDING_PAYMENT);
        order.setExpireAt(LocalDateTime.now().plusMinutes(ORDER_EXPIRE_MINUTES));
        order.setRemark(request.remark());
        order.setCouponGrantId(request.couponGrantId());
        order.setItemCount(1);
        Entities.forInsert(order);
        orderMapper.insert(order);

        // 锁券必须在订单落库之后：锁券要回写 orderId，而 orderId 只有插入后才有
        if (request.couponGrantId() != null) {
            couponService.lock(request.couponGrantId(), order.getId(), discount);
        }

        TradeOrderItem item = new TradeOrderItem();
        item.setOrderId(order.getId());
        item.setProductId(product.getId());
        item.setSkuId(sku.getId());
        item.setProductName(product.getName());
        item.setSkuName(sku.getName());
        item.setUnitPriceFen(unitPrice);
        item.setQuantity(quantity);
        item.setAmountFen(totalAmount);
        item.setRefundedAmountFen(0L);
        item.setDurationDays(sku.getDurationDays() != null ? sku.getDurationDays() : product.getValidityDays());
        Entities.forInsert(item);
        orderItemMapper.insert(item);
        return order;
    }

    /**
     * SKU 解析：指定则校验归属与在售；未指定回退默认 SKU。
     *
     * <p>回退是必要的兼容层——老前端与老接口只传 productId，如果这时直接报错，
     * 升级当晚所有下单都会失败。</p>
     */
    MallSku resolveSku(MallProduct product, Long skuId) {
        if (skuId != null) {
            MallSku sku = skuMapper.selectById(skuId);
            if (sku == null || !product.getId().equals(sku.getProductId())) {
                throw new BizException("SKU_NOT_FOUND", "规格不存在或不属于该商品");
            }
            if (!MallSku.STATUS_ON_SALE.equals(sku.getStatus())) {
                throw new BizException("SKU_OFF_SALE", "该规格已下架");
            }
            return sku;
        }
        MallSku fallback = skuMapper.selectOne(Wrappers.<MallSku>lambdaQuery()
                .eq(MallSku::getProductId, product.getId())
                .eq(MallSku::getCode, "DEFAULT")
                .last("LIMIT 1"));
        if (fallback == null) {
            fallback = skuMapper.selectOne(Wrappers.<MallSku>lambdaQuery()
                    .eq(MallSku::getProductId, product.getId())
                    .eq(MallSku::getStatus, MallSku.STATUS_ON_SALE)
                    .orderByAsc(MallSku::getSortNo)
                    .last("LIMIT 1"));
        }
        if (fallback == null) {
            // 迁移回填只覆盖「迁移时刻已存在」的商品；演示数据与运行期新建的老商品
            // 仍然可能没有 SKU，这里就地补一个，否则下单直接被拒。
            fallback = createDefaultSku(product);
        }
        return fallback;
    }

    /** 商品下的 SKU 列表（前端选规格用）。 */
    public List<MallSku> listSkus(Long productId, boolean onlyOnSale) {
        return skuMapper.selectList(Wrappers.<MallSku>lambdaQuery()
                .eq(MallSku::getProductId, productId)
                .eq(onlyOnSale, MallSku::getStatus, MallSku.STATUS_ON_SALE)
                .orderByAsc(MallSku::getSortNo)
                .orderByAsc(MallSku::getId));
    }

    /** 订单明细（退款按行退，前端也要能看到退了哪一行）。 */
    public List<TradeOrderItem> listOrderItems(Long orderId) {
        return orderItemMapper.selectList(Wrappers.<TradeOrderItem>lambdaQuery()
                .eq(TradeOrderItem::getOrderId, orderId)
                .orderByAsc(TradeOrderItem::getId));
    }

    public PageResult<TradeOrder> pageMyOrders(Long userId, String status, PageQuery pageQuery) {
        Page<TradeOrder> page = orderMapper.selectPage(pageQuery.toPage(), Wrappers.<TradeOrder>lambdaQuery()
                .eq(TradeOrder::getUserId, userId)
                .eq(status != null && !status.isBlank(), TradeOrder::getStatus, status)
                .orderByDesc(TradeOrder::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public PageResult<TradeOrder> pageAllOrders(String status, String keyword, PageQuery pageQuery) {
        // status 支持逗号分隔的多个取值：页面上「退款」这一个页签同时包含已退款与部分退款，
        // 而它们是两个枚举——只认单个值会让页签计数与列表条数对不上
        List<String> statuses = status == null || status.isBlank()
                ? List.of()
                : java.util.Arrays.stream(status.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
        Page<TradeOrder> page = orderMapper.selectPage(pageQuery.toPage(), Wrappers.<TradeOrder>lambdaQuery()
                .in(!statuses.isEmpty(), TradeOrder::getStatus, statuses)
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
            paymentMapper.insert(payment);
        }

        // 每次都向渠道重新要一次调度参数：微信 prepay_id 只有 2 小时有效期，
        // 复用落库里的旧参数会让用户点了「支付」却调不起收银台，而日志里一切正常
        String openId = resolveOpenId(order.getUserId());
        if (!payGateway.mock() && (openId == null || openId.isBlank())) {
            throw new BizException("PAY_OPENID_MISSING", "请先通过小程序登录后再支付");
        }
        WechatPayGateway.PrepayResult prepay = payGateway.prepay(new WechatPayGateway.PrepayCommand(
                payment.getPayNo(), order.getProductName(), payment.getAmountFen(), openId));

        TradePayment withPrepay = new TradePayment();
        withPrepay.setId(payment.getId());
        withPrepay.setPrepayJson(Json.write(prepay.payParams()));
        paymentMapper.updateById(withPrepay);
        payment.setPrepayJson(withPrepay.getPrepayJson());

        String mockNotifyUrl = payGateway.mock() ? "/api/v1/dev/payments/" + payment.getPayNo() + "/mock-notify" : null;
        return new PaymentCreateResponse(payment.getPayNo(), order.getOrderNo(), payment.getAmountFen(),
                CHANNEL_WECHAT, prepay.prepayId(), mockNotifyUrl, prepay.payParams());
    }

    /** JSAPI 下单必须带付款人的 openid；mock 模式下可能没有（直接用密码账号登录的用户）。 */
    private String resolveOpenId(Long userId) {
        SysUserIdentity identity = identityMapper.selectOne(Wrappers.<SysUserIdentity>lambdaQuery()
                .eq(SysUserIdentity::getUserId, userId)
                .eq(SysUserIdentity::getProvider, "WECHAT")
                .last("LIMIT 1"));
        return identity == null ? null : identity.getOpenId();
    }

    /**
     * 处理 mock 支付回调（本地签名校验）。不抛业务异常，统一通过 {@link NotifyResult} 返回，
     * 控制器据此应答支付平台。
     */
    @Transactional
    public NotifyResult handleNotify(WechatNotifyRequest request, String rawBody) {
        TradePaymentNotify record = startNotifyRecord(request.outTradeNo(), rawBody);

        // 真实环境不要走这条路：v3 的签名必须在控制器层用平台证书验，
        // 这里的本地密钥签名只服务于 app.wechat.mock=true
        String expectedSign = sign(request.outTradeNo(), request.amountFen());
        if (request.sign() == null || !expectedSign.equalsIgnoreCase(request.sign())) {
            return reject(record, "SIGN_INVALID", "PAY_SIGN_INVALID", "支付通知验签失败");
        }
        if (!TRADE_SUCCESS.equals(request.tradeState())) {
            return reject(record, "TRADE_STATE_" + request.tradeState(), "PAY_TRADE_STATE_INVALID", "交易状态非成功");
        }
        return settle(record, request.outTradeNo(), request.transactionId(),
                request.amountFen(), request.currency(), "NOTIFY");
    }

    /**
     * 处理已验签解密的 v3 回调。验签与解密在控制器完成，这里只做业务判定与落账，
     * 两条入口（mock / 真实）共用同一段落账逻辑，避免「线上和本地走的不是同一段代码」。
     */
    @Transactional
    public NotifyResult handleVerifiedNotify(String payNo, String transactionId, Long amountFen,
                                             String currency, String tradeState, String rawBody) {
        TradePaymentNotify record = startNotifyRecord(payNo, rawBody);
        if (!TRADE_SUCCESS.equalsIgnoreCase(tradeState)) {
            return reject(record, "TRADE_STATE_" + tradeState, "PAY_TRADE_STATE_INVALID", "交易状态非成功");
        }
        return settle(record, payNo, transactionId, amountFen, currency, "NOTIFY_V3");
    }

    /**
     * 统一落账：币种 → 支付单 → 金额 → 幂等 → 置 PAID → 完成订单 → 发权益 → 发通知。
     *
     * <p>金额核对放在最后一步之前而不是信任回调：回调里的金额是外部输入，
     * 只认「与本地支付单一致」，否则等于让支付平台决定扣多少钱。</p>
     */
    private NotifyResult settle(TradePaymentNotify record, String payNo, String transactionId,
                                Long amountFen, String currency, String source) {
        if (!CURRENCY_CNY.equals(currency)) {
            return reject(record, "CURRENCY_MISMATCH", "PAY_CURRENCY_MISMATCH", "币种不匹配");
        }
        TradePayment payment = paymentMapper.selectOne(Wrappers.<TradePayment>lambdaQuery()
                .eq(TradePayment::getPayNo, payNo));
        if (payment == null) {
            return reject(record, "PAYMENT_NOT_FOUND", "PAY_PAYMENT_NOT_FOUND", "支付单不存在");
        }
        if (amountFen == null || !payment.getAmountFen().equals(amountFen)) {
            return reject(record, "AMOUNT_MISMATCH", "PAY_AMOUNT_MISMATCH", "支付金额与订单金额不一致");
        }
        if (TradePayment.STATUS_PAID.equals(payment.getStatus())) {
            record.setVerifyResult("DUPLICATE_" + source);
            record.setProcessed(1);
            notifyMapper.insert(record);
            return NotifyResult.ok();
        }
        if (TradePayment.STATUS_CLOSED.equals(payment.getStatus())) {
            // 关单与支付赛跑：钱已经收了但本地已关单，只能人工退款，绝不能当作成功静默吞掉
            record.setVerifyResult("PAID_AFTER_CLOSED");
            notifyMapper.insert(record);
            log.error("支付单已关闭却收到支付成功，需人工退款 payNo={} txn={} source={}",
                    payNo, transactionId, source);
            return NotifyResult.fail("PAY_PAID_AFTER_CLOSED", "支付单已关闭，需人工退款处理");
        }

        markPaid(payment, transactionId, source);
        record.setVerifyResult("PASS_" + source);
        record.setProcessed(1);
        notifyMapper.insert(record);
        return NotifyResult.ok();
    }

    private void markPaid(TradePayment payment, String transactionId, String source) {
        LocalDateTime now = LocalDateTime.now();
        TradePayment paid = new TradePayment();
        paid.setId(payment.getId());
        paid.setStatus(TradePayment.STATUS_PAID);
        paid.setTransactionId(transactionId);
        paid.setPaidAt(now);
        paymentMapper.updateById(paid);

        TradeOrder order = orderMapper.selectById(payment.getOrderId());
        if (order == null) {
            log.error("支付成功但订单不存在，权益无法发放 payNo={} orderId={}",
                    payment.getPayNo(), payment.getOrderId());
            return;
        }
        TradeOrder paidOrder = new TradeOrder();
        paidOrder.setId(order.getId());
        paidOrder.setStatus(TradeOrder.STATUS_COMPLETED);
        paidOrder.setPaidAt(now);
        orderMapper.updateById(paidOrder);

        grantEntitlement(order, transactionId, now);
        // 券在支付成功这一刻才核销：下单只锁定，避免「付了一半又取消」把券吃掉
        couponService.confirm(order.getId());

        messageService.publish(MsgTask.SCENE_PAYMENT, "order-" + order.getId(), order.getUserId(),
                MessageTemplates.PAYMENT_SUCCESS, Map.of(
                        "orderNo", order.getOrderNo(),
                        "productName", order.getProductName(),
                        "amountFen", order.getPayAmountFen()));

        log.info("支付已落账 orderNo={} payNo={} txn={} source={}",
                order.getOrderNo(), payment.getPayNo(), transactionId, source);
    }

    private TradePaymentNotify startNotifyRecord(String payNo, String rawBody) {
        TradePaymentNotify record = new TradePaymentNotify();
        record.setPayNo(payNo);
        record.setChannel(CHANNEL_WECHAT);
        record.setRawBody(rawBody);
        record.setProcessed(0);
        Entities.forInsert(record);
        return record;
    }

    private NotifyResult reject(TradePaymentNotify record, String verifyResult, String code, String message) {
        record.setVerifyResult(verifyResult);
        notifyMapper.insert(record);
        log.warn("支付回调被拒 code={} payNo={} reason={}", code, record.getPayNo(), verifyResult);
        return NotifyResult.fail(code, message);
    }

    // ---------------------------------------------------------------- 补偿：超时关单 / 主动查单

    /** 找「创建后迟迟未支付」的支付单，供补偿任务主动查单。 */
    public List<TradePayment> findStaleCreatedPayments(LocalDateTime createdBefore, int limit) {
        return paymentMapper.selectList(Wrappers.<TradePayment>lambdaQuery()
                .eq(TradePayment::getStatus, TradePayment.STATUS_CREATED)
                .lt(TradePayment::getCreatedAt, createdBefore)
                .orderByAsc(TradePayment::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /**
     * 主动查单补偿。
     *
     * <p>回调可能永远不来（网络抖动、部署重启丢掉请求、验签配置错误被拒），
     * 只等回调等于「用户付了钱但没到账」。查到 SUCCESS 就走与回调完全相同的落账路径。</p>
     *
     * @return 本次是否真的完成了落账（已支付过返回 false）
     */
    @Transactional
    public boolean settleByQuery(TradePayment payment, WechatPayGateway.Transaction tx) {
        if (!tx.success()) {
            return false;
        }
        TradePaymentNotify record = startNotifyRecord(payment.getPayNo(), "{\"source\":\"QUERY_COMPENSATION\"}");
        String currency = tx.currency() == null ? CURRENCY_CNY : tx.currency();
        return settle(record, payment.getPayNo(), tx.transactionId(), tx.amountFen(), currency, "QUERY").success();
    }

    /** 关闭未支付支付单，并把仍待支付的订单一起关掉（否则订单会挂在「待支付」直到过期）。 */
    @Transactional
    public void closeUnpaid(String payNo, String reason) {
        TradePayment payment = getPaymentByPayNo(payNo);
        if (payment == null || TradePayment.STATUS_PAID.equals(payment.getStatus())) {
            return;
        }
        TradePayment closed = new TradePayment();
        closed.setId(payment.getId());
        closed.setStatus(TradePayment.STATUS_CLOSED);
        paymentMapper.updateById(closed);

        TradeOrder order = orderMapper.selectById(payment.getOrderId());
        if (order != null && TradeOrder.STATUS_PENDING_PAYMENT.equals(order.getStatus())) {
            TradeOrder closedOrder = new TradeOrder();
            closedOrder.setId(order.getId());
            closedOrder.setStatus(TradeOrder.STATUS_CLOSED);
            orderMapper.updateById(closedOrder);
        }
        // 订单关闭必须把券还回去，否则用户下单后没付款就白丢一张券
        couponService.release(payment.getOrderId(), reason);
        log.info("支付单已关闭 payNo={} reason={}", payNo, reason);
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
        Integer durationDays = resolveDurationDays(order, product);
        LocalDateTime expireAt = durationDays != null && durationDays > 0
                ? now.plusDays(durationDays) : null;
        entitlementService.grant(order.getUserId(), entitlementType, product.getResourceType(),
                product.getResourceId(), order.getQuantity(), expireAt, order.getId(),
                "PAY-" + transactionId);
    }

    /**
     * 权益时长以订单明细为准：同一商品的不同 SKU 可能对应月卡 / 年卡，
     * 若统一按商品的 validityDays 发放，买年卡的用户只会拿到月卡时长。
     */
    private Integer resolveDurationDays(TradeOrder order, MallProduct product) {
        TradeOrderItem item = orderItemMapper.selectOne(Wrappers.<TradeOrderItem>lambdaQuery()
                .eq(TradeOrderItem::getOrderId, order.getId())
                .orderByAsc(TradeOrderItem::getId)
                .last("LIMIT 1"));
        if (item != null && item.getDurationDays() != null) {
            return item.getDurationDays();
        }
        return product.getValidityDays();
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
        return payGateway.mock();
    }

    public TradePayment getPaymentByPayNo(String payNo) {
        return paymentMapper.selectOne(Wrappers.<TradePayment>lambdaQuery()
                .eq(TradePayment::getPayNo, payNo));
    }

    public long sumPaidAmountFen() {
        return orderMapper.sumPaidAmountFen(com.exam.platform.common.security.UserContext.tenantId());
    }
}
