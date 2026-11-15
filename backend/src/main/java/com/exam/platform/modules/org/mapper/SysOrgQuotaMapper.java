package com.exam.platform.modules.org.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.platform.modules.org.entity.SysOrgQuota;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface SysOrgQuotaMapper extends BaseMapper<SysOrgQuota> {

    /**
     * 全部额度账户（跨租户）。
     *
     * <p>对账任务跑在无登录态的系统线程里（默认平台租户），走租户插件只能看到平台租户的账户，
     * 其它租户的余额偏差就永远查不出来。</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM sys_org_quota WHERE deleted = 0 ORDER BY id ASC")
    List<SysOrgQuota> selectAllCrossTenant();

    /**
     * 累加充值，并「只往后」推有效期。
     *
     * <p>有效期用 CASE 取较晚的那个：续充一份长期额度不该把原有期限缩短，
     * 反之也不该因为新充值的期限更早而提前失效。传 null（不设期限）时保留原值——
     * 「这次忘了填期限」不能悄悄变成「永久有效」。</p>
     */
    @Update("UPDATE sys_org_quota SET total_amount = total_amount + #{amount}, "
            + "remark = #{remark}, updated_at = #{now}, "
            + "expire_at = CASE "
            + " WHEN #{expireAt} IS NULL THEN expire_at "
            + " WHEN expire_at IS NULL THEN #{expireAt} "
            + " WHEN #{expireAt} > expire_at THEN #{expireAt} "
            + " ELSE expire_at END "
            + "WHERE deleted = 0 AND id = #{id}")
    int recharge(@Param("id") Long id, @Param("amount") long amount,
                 @Param("remark") String remark, @Param("expireAt") LocalDateTime expireAt,
                 @Param("now") LocalDateTime now);

    /**
     * 原子扣减：余额不足或已过期时影响行数为 0。
     *
     * <p>余额与有效期的判断都写在 UPDATE 的 WHERE 里，而不是「先 SELECT 再判断、再 UPDATE」——
     * 后者在并发发放时两个请求会各自读到同一个余额，都能通过校验，最后双花；
     * 过期判断同理，先查后判会在临界点放过一笔本该失败的扣减。</p>
     */
    @Update("UPDATE sys_org_quota SET used_amount = used_amount + #{amount}, updated_at = #{now} "
            + "WHERE deleted = 0 AND id = #{id} AND total_amount - used_amount >= #{amount} "
            + "AND (expire_at IS NULL OR expire_at > #{now})")
    int consume(@Param("id") Long id, @Param("amount") long amount, @Param("now") LocalDateTime now);

    /** 退还额度：发放过程中被跳过的成员，其额度要退回去，否则机构的白花钱。 */
    @Update("UPDATE sys_org_quota SET used_amount = used_amount - #{amount}, updated_at = #{now} "
            + "WHERE deleted = 0 AND id = #{id} AND used_amount >= #{amount}")
    int refund(@Param("id") Long id, @Param("amount") long amount, @Param("now") LocalDateTime now);
}
