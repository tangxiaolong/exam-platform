package com.exam.platform;

import com.exam.platform.common.security.LoginUser;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.trade.CouponSaveRequest;
import com.exam.platform.modules.trade.CouponService;
import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 优惠券到期失效：过期券的 UNUSED 持券记录翻为 EXPIRED，有效期内的券不受影响。
 *
 * <p>直接调用服务方法验证状态翻转；券表查询受租户插件约束，
 * 测试线程没有登录态，调用前显式切到租户 1 的上下文。</p>
 */
@DisplayName("优惠券到期失效")
class CouponExpireTest extends ApiTestSupport {

    @Autowired
    private CouponService couponService;

    @Test
    @DisplayName("只翻转过期券的未使用记录")
    void expireShouldFlipOnlyOverdueCoupons() {
        LocalDateTime now = LocalDateTime.now();
        long[] ids = withTenant(() -> {
            var overdue = couponService.adminCreate(new CouponSaveRequest(
                    "EXP" + System.nanoTime() % 1000000, "过期测试券", "AMOUNT", 0L, 100L, 0, null,
                    1, 1, "ALL", null, now.minusDays(3), now.minusDays(1)));
            var valid = couponService.adminCreate(new CouponSaveRequest(
                    "VAL" + System.nanoTime() % 1000000, "有效测试券", "AMOUNT", 0L, 100L, 0, null,
                    1, 1, "ALL", null, now.minusDays(1), now.plusDays(3)));
            return new long[]{overdue.getId(), valid.getId()};
        });

        insertGrant(ids[0], 888801L);
        insertGrant(ids[1], 888802L);

        Integer expired = withTenant(() -> couponService.expireOverdue());
        assertThat(expired).as("至少翻转本用例造的那张过期券").isGreaterThanOrEqualTo(1);

        assertThat(grantStatus(ids[0], 888801L)).as("过期券翻为 EXPIRED").isEqualTo("EXPIRED");
        assertThat(grantStatus(ids[1], 888802L)).as("有效券保持 UNUSED").isEqualTo("UNUSED");
    }

    private void insertGrant(long couponId, long userId) {
        jdbcTemplate.update(
                "INSERT INTO marketing_coupon_grant (tenant_id, coupon_id, user_id, status, "
                        + "granted_at, created_at, updated_at, deleted) "
                        + "VALUES (1, ?, ?, 'UNUSED', ?, NOW(), NOW(), 0)",
                couponId, userId, Timestamp.valueOf(LocalDateTime.now()));
    }

    private String grantStatus(long couponId, long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM marketing_coupon_grant WHERE coupon_id = ? AND user_id = ? AND deleted = 0",
                String.class, couponId, userId);
    }

    private <T> T withTenant(Supplier<T> action) {
        UserContext.set(new LoginUser(1L, "admin", "测试", 1L, LoginUser.ROLE_ADMIN));
        try {
            return action.get();
        } finally {
            UserContext.clear();
        }
    }
}
