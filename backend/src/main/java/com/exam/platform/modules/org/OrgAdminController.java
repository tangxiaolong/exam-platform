package com.exam.platform.modules.org;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.modules.org.dto.ExpiredQuotaView;
import com.exam.platform.modules.org.dto.OrgGrantRequest;
import com.exam.platform.modules.org.dto.OrgGrantResult;
import com.exam.platform.modules.org.dto.OrgMemberRequest;
import com.exam.platform.modules.org.dto.OrgQuotaRechargeRequest;
import com.exam.platform.modules.org.dto.OrgSaveRequest;
import com.exam.platform.modules.org.dto.QuotaReconReport;
import com.exam.platform.modules.org.entity.SysOrg;
import com.exam.platform.modules.org.entity.SysOrgMember;
import com.exam.platform.modules.org.entity.SysOrgQuota;
import com.exam.platform.modules.org.entity.SysOrgQuotaRecharge;
import com.exam.platform.modules.system.support.Audited;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** 运营后台：机构 / 企业与批量授权。 */
@RestController
@RequestMapping("/v1/admin/orgs")
@RequiredArgsConstructor
public class OrgAdminController {

    private final OrgService orgService;
    private final OrgGrantService orgGrantService;
    private final OrgQuotaService orgQuotaService;
    private final OrgQuotaReconService orgQuotaReconService;

    /**
     * 与定时任务共用同一份窗口配置。
     *
     * <p>运维手点对账时用的窗口必须和凌晨自动核对的一致，否则「自动说没问题、手动说有差异」
     * 这种自相矛盾的结果会直接毁掉对账的可信度。</p>
     */
    @Value("${app.org-quota.recon-lookback-days:7}")
    private int reconLookbackDays;

    @GetMapping
    public Result<PageResult<SysOrg>> page(@RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String type,
                                           @RequestParam(required = false) String status,
                                           PageQuery pageQuery) {
        return Result.ok(orgService.page(keyword, type, status, pageQuery));
    }

    @GetMapping("/{id}")
    public Result<SysOrg> detail(@PathVariable Long id) {
        return Result.ok(orgService.getOrg(id));
    }

    @PostMapping
    @Audited(action = "ORG_CREATE", target = "'sys_org:' + #result.data.id", detail = "#result.data.name")
    public Result<SysOrg> create(@RequestBody OrgSaveRequest request) {
        return Result.ok(orgService.save(request));
    }

    @PutMapping("/{id}")
    @Audited(action = "ORG_UPDATE", target = "'sys_org:' + #id", detail = "#result.data.name")
    public Result<SysOrg> update(@PathVariable Long id, @RequestBody OrgSaveRequest request) {
        return Result.ok(orgService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Audited(action = "ORG_DELETE", target = "'sys_org:' + #id")
    public Result<Void> delete(@PathVariable Long id) {
        orgService.delete(id);
        return Result.ok();
    }

    @GetMapping("/{id}/members")
    public Result<List<SysOrgMember>> members(@PathVariable Long id) {
        return Result.ok(orgService.listMembers(id));
    }

    @PostMapping("/{id}/members")
    @Audited(action = "ORG_MEMBER_ADD", target = "'sys_org:' + #id", detail = "'userId=' + #request.userId()")
    public Result<SysOrgMember> addMember(@PathVariable Long id, @RequestBody OrgMemberRequest request) {
        if (request.userId() == null) {
            throw BizException.badRequest("userId 不能为空");
        }
        return Result.ok(orgService.addMember(id, request.userId(), request.role()));
    }

    @DeleteMapping("/{id}/members/{userId}")
    @Audited(action = "ORG_MEMBER_REMOVE", target = "'sys_org:' + #id", detail = "'userId=' + #userId")
    public Result<Void> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        orgService.removeMember(id, userId);
        return Result.ok();
    }

    /** 批量授权：把一个权益额度发放给组织成员（重复提交不重复发放）。 */
    @PostMapping("/{id}/grants")
    @Audited(action = "ORG_GRANT", target = "'sys_org:' + #id",
            detail = "'batch=' + #result.data.batchNo() + ' granted=' + #result.data.grantedCount()")
    public Result<OrgGrantResult> grant(@PathVariable Long id, @RequestBody OrgGrantRequest request) {
        OrgGrantRequest normalized = new OrgGrantRequest(id, request.entitlementType(),
                request.resourceType(), request.resourceId(), request.amount(), request.expireAt(),
                request.userIds(), request.remark());
        return Result.ok(orgGrantService.grant(normalized));
    }

    /** 机构额度账户（买了多少、发了多少、还剩多少）。 */
    @GetMapping("/{id}/quotas")
    public Result<List<SysOrgQuota>> quotas(@PathVariable Long id) {
        return Result.ok(orgQuotaService.list(id));
    }

    /** 充值流水：拿采购单/合同来对账时看的就是这张表。 */
    @GetMapping("/{id}/quota-recharges")
    public Result<List<SysOrgQuotaRecharge>> quotaRecharges(@PathVariable Long id) {
        return Result.ok(orgQuotaService.listRecharges(id));
    }

    /**
     * 已过期的额度账户（含未用余额）。
     *
     * <p>过期只影响「还能不能消耗」，不删除账户——删掉账户会让机构退化成「没有额度账户」，
     * 而那在当前实现里等于不限额。所以这里只做「列出来让运营看见作废了多少」。</p>
     */
    @GetMapping("/expired-quotas")
    public Result<List<ExpiredQuotaView>> expiredQuotas() {
        return Result.ok(orgQuotaService.listExpired(LocalDateTime.now()));
    }

    /** 给机构充值额度：同 (机构, 权益类型, 资源) 累加到同一账户。 */
    @PostMapping("/{id}/quotas")
    @Audited(action = "ORG_QUOTA_RECHARGE", target = "'sys_org:' + #id",
            detail = "'type=' + #request.entitlementType() + ' resource=' + #request.resourceId()"
                    + " + ' amount=' + #request.amount()")
    public Result<SysOrgQuota> recharge(@PathVariable Long id,
                                        @RequestBody OrgQuotaRechargeRequest request) {
        return Result.ok(orgQuotaService.recharge(id, request.entitlementType(), request.resourceId(),
                request.amount(), request.remark(), request.expireAt()));
    }

    /**
     * 立刻对一次额度账（不用等凌晨的定时任务）。
     *
     * <p>典型用途：机构投诉「我的额度怎么少了」时当场核一遍，而不是承诺「明天凌晨的对账会告诉你」。</p>
     *
     * @param fullScan true 表示不限时间窗、开工至今全核一遍。
     *                 只在怀疑历史遗留问题时用：台账只增不删，全量扫可能撞上语句超时，
     *                 而「对账超时」比「对账报错」更糟——它会让人以为核过了没问题。
     */
    @GetMapping("/quota-recon")
    @Audited(action = "ORG_QUOTA_RECON", target = "'sys_org_quota'",
            detail = "'fullScan=' + #fullScan")
    public Result<QuotaReconReport> quotaRecon(
            @RequestParam(defaultValue = "false") boolean fullScan) {
        return Result.ok(orgQuotaReconService.recon(reconLookbackDays, fullScan));
    }
}
