package com.exam.platform.modules.org;

import com.exam.platform.common.api.Result;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.LoginUser;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.org.dto.OrgGrantRequest;
import com.exam.platform.modules.org.dto.OrgGrantResult;
import com.exam.platform.modules.org.dto.OrgPortalView;
import com.exam.platform.modules.org.entity.SysOrgMember;
import com.exam.platform.modules.org.entity.SysOrgQuota;
import com.exam.platform.modules.org.entity.SysOrgQuotaRecharge;
import com.exam.platform.modules.system.support.Audited;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 机构侧门户：机构管理员自助查看本机构、给自己的人发权益。
 *
 * <p>与 {@code /v1/admin/orgs} 的分工：平台管理「机构本身」（建、停、删、平台级代发），
 * 机构管理「自己的人和自己的额度」。两边不共用角色，也不共用接口。</p>
 *
 * <p>路径里显式带 {@code orgId} 而不是靠「当前用户唯一所属机构」隐式推导：
 * 一个人可以属于多个机构，隐式推导在那种场景下会静默操作到错误的那一家。</p>
 */
@RestController
@RequestMapping("/v1/org")
@RequiredArgsConstructor
public class OrgPortalController {

    private final OrgPortalService orgPortalService;

    /** 我所属的机构。机构侧前端登录后先调这里，拿到 orgId 再进具体机构页。 */
    @GetMapping("/my-orgs")
    public Result<List<OrgPortalView>> myOrgs() {
        return Result.ok(orgPortalService.myOrgs(requireUserId()));
    }

    @GetMapping("/orgs/{orgId}/profile")
    public Result<OrgPortalView> profile(@PathVariable Long orgId) {
        return Result.ok(orgPortalService.profile(orgId, requireUserId()));
    }

    @GetMapping("/orgs/{orgId}/members")
    public Result<List<SysOrgMember>> members(@PathVariable Long orgId) {
        return Result.ok(orgPortalService.members(orgId, requireUserId()));
    }

    /** 自助批量发放：动作码与平台侧 {@code ORG_GRANT} 分开，事后能直接看出是谁发的。 */
    @PostMapping("/orgs/{orgId}/grants")
    @Audited(action = "ORG_PORTAL_GRANT", target = "'sys_org:' + #orgId",
            detail = "'batch=' + #result.data.batchNo() + ' granted=' + #result.data.grantedCount()")
    public Result<OrgGrantResult> grant(@PathVariable Long orgId, @RequestBody OrgGrantRequest request) {
        return Result.ok(orgPortalService.grant(orgId, requireUserId(), request));
    }

    /** 机构侧看自己的额度账单：买了多少、发了多少、还剩多少。 */
    @GetMapping("/orgs/{orgId}/quotas")
    public Result<List<SysOrgQuota>> quotas(@PathVariable Long orgId) {
        return Result.ok(orgPortalService.quotas(orgId, requireUserId()));
    }

    /** 机构侧看充值流水：自己买的额度什么时候充的、充了多少、期到哪天。 */
    @GetMapping("/orgs/{orgId}/quota-recharges")
    public Result<List<SysOrgQuotaRecharge>> quotaRecharges(@PathVariable Long orgId) {
        return Result.ok(orgPortalService.quotaRecharges(orgId, requireUserId()));
    }

    private Long requireUserId() {
        LoginUser user = UserContext.get();
        if (user == null || user.getUserId() == null) {
            throw new BizException("AUTH_UNAUTHORIZED", "请先登录");
        }
        return user.getUserId();
    }
}
