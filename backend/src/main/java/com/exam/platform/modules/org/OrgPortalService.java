package com.exam.platform.modules.org;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.modules.org.dto.OrgGrantRequest;
import com.exam.platform.modules.org.dto.OrgGrantResult;
import com.exam.platform.modules.org.dto.OrgPortalView;
import com.exam.platform.modules.org.entity.SysOrg;
import com.exam.platform.modules.org.entity.SysOrgMember;
import com.exam.platform.modules.org.entity.SysOrgQuota;
import com.exam.platform.modules.org.entity.SysOrgQuotaRecharge;
import com.exam.platform.modules.org.mapper.SysOrgMapper;
import com.exam.platform.modules.org.mapper.SysOrgMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 机构侧自助服务（B 端门户）。
 *
 * <p>存在的意义：机构已付费、已签约，却连「我的人有没有拿到权益」都要找平台运营查，
 * 每次加人、每次开课都要走一遍人工。这里把「查看本机构 + 给自己的人发权益」交给机构自己，
 * 平台侧只保留对机构本身的管理（{@code /v1/admin/orgs}）。</p>
 *
 * <p>三条边界，缺一条这套自助就变成漏洞：</p>
 * <ol>
 *   <li><b>角色只是门票</b>：{@code AuthInterceptor} 保证他是 ORG_ADMIN，
 *       但「他是不是这个机构的成员」必须每次重新校验 {@code sys_org_member}——
 *       角色一旦授予就长期有效，成员关系却可能随时被移除；</li>
 *   <li><b>机构 ID 只认路径</b>：请求体里的 {@code orgId} 一律丢弃并用路径值覆盖，
 *       否则一个机构管理员把 body 改成别家机构就能替别家发权益；</li>
 *   <li><b>停用机构拒绝服务</b>：机构停用后成员关系还在，但业务上不该再发权益。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgPortalService {

    private final SysOrgMapper orgMapper;
    private final SysOrgMemberMapper memberMapper;
    private final OrgService orgService;
    private final OrgGrantService orgGrantService;
    private final OrgQuotaService orgQuotaService;

    /** 我所属的机构列表（前端据此渲染下拉，也用于自检「我到底属于哪几家」）。 */
    public List<OrgPortalView> myOrgs(Long userId) {
        List<SysOrgMember> memberships = memberMapper.selectList(Wrappers.<SysOrgMember>lambdaQuery()
                .eq(SysOrgMember::getUserId, userId)
                .eq(SysOrgMember::getStatus, SysOrgMember.STATUS_ACTIVE));
        List<OrgPortalView> result = new ArrayList<>();
        for (SysOrgMember membership : memberships) {
            SysOrg org = orgMapper.selectById(membership.getOrgId());
            // 机构被删除后成员关系可能残留：跳过而不是整单失败，否则一个脏数据让门户直接打不开
            if (org == null) {
                log.warn("机构已不存在，忽略成员关系 orgId={} userId={}", membership.getOrgId(), userId);
                continue;
            }
            result.add(toView(org, membership.getRole()));
        }
        return result;
    }

    public OrgPortalView profile(Long orgId, Long userId) {
        SysOrgMember membership = requireMember(orgId, userId);
        return toView(orgService.getOrg(orgId), membership.getRole());
    }

    public List<SysOrgMember> members(Long orgId, Long userId) {
        requireMember(orgId, userId);
        return orgService.listMembers(orgId);
    }

    public OrgGrantResult grant(Long orgId, Long userId, OrgGrantRequest request) {
        requireMember(orgId, userId);
        // 用路径里的 orgId 覆盖请求体：调用方改 body 也换不了机构
        OrgGrantRequest normalized = new OrgGrantRequest(orgId, request.entitlementType(),
                request.resourceType(), request.resourceId(), request.amount(), request.expireAt(),
                request.userIds(), request.remark());
        return orgGrantService.grant(normalized);
    }

    /** 机构侧查看自己的额度账单（买了多少、发了多少、还剩多少）。 */
    public List<SysOrgQuota> quotas(Long orgId, Long userId) {
        requireMember(orgId, userId);
        return orgQuotaService.list(orgId);
    }

    /** 机构侧查看充值流水：自己买的额度什么时候充的、充了多少、期到哪天。 */
    public List<SysOrgQuotaRecharge> quotaRecharges(Long orgId, Long userId) {
        requireMember(orgId, userId);
        return orgQuotaService.listRecharges(orgId);
    }

    /** 校验当前用户是该机构的有效成员，且机构处于启用状态。 */
    private SysOrgMember requireMember(Long orgId, Long userId) {
        SysOrgMember membership = memberMapper.selectOne(Wrappers.<SysOrgMember>lambdaQuery()
                .eq(SysOrgMember::getOrgId, orgId)
                .eq(SysOrgMember::getUserId, userId)
                .eq(SysOrgMember::getStatus, SysOrgMember.STATUS_ACTIVE));
        if (membership == null) {
            throw new BizException("ORG_NOT_MEMBER", "你不是该机构的有效成员");
        }
        SysOrg org = orgService.getOrg(orgId);
        if (!SysOrg.STATUS_ENABLED.equals(org.getStatus())) {
            throw new BizException("ORG_DISABLED", "机构已停用，不能自助发放权益");
        }
        return membership;
    }

    private OrgPortalView toView(SysOrg org, String myRole) {
        return new OrgPortalView(org.getId(), org.getCode(), org.getName(), org.getType(),
                org.getStatus(), myRole);
    }
}
