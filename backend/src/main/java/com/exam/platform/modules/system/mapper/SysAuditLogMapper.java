package com.exam.platform.modules.system.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.platform.modules.system.entity.SysAuditLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface SysAuditLogMapper extends BaseMapper<SysAuditLog> {

    /**
     * 查询早于给定时刻的审计记录，跨租户。
     *
     * <p>租户插件会给所有 SQL 追加 {@code tenant_id} 条件，而归档任务跑在无登录态的系统线程里
     * （默认平台租户）——不显式忽略的话，其它租户的旧记录永远清不掉，归档等于只对平台租户生效。</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM sys_audit_log WHERE created_at < #{before} ORDER BY id ASC LIMIT #{batchSize}")
    List<SysAuditLog> selectExpiredCrossTenant(@Param("before") LocalDateTime before,
                                               @Param("batchSize") int batchSize);

    /**
     * 物理删除一批审计记录，跨租户。
     *
     * <p>用原生 DELETE 而不是 {@code deleteById}：后者走逻辑删除，会留下 {@code deleted=1} 的占位行，
     * 表照样膨胀，而审计的语义是「这条记录真实存在过」，不是「被标记为删除」。</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    @Delete("<script>DELETE FROM sys_audit_log WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int deleteByIdsCrossTenant(@Param("ids") List<Long> ids);
}
