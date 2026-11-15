package com.exam.platform.modules.org;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.Entities;
import com.exam.platform.modules.org.dto.OrgSaveRequest;
import com.exam.platform.modules.org.entity.SysOrg;
import com.exam.platform.modules.org.entity.SysOrgMember;
import com.exam.platform.modules.org.mapper.SysOrgMapper;
import com.exam.platform.modules.org.mapper.SysOrgMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 组织（机构 / 企业）与成员管理。 */
@Service
@RequiredArgsConstructor
public class OrgService {

    private final SysOrgMapper orgMapper;
    private final SysOrgMemberMapper memberMapper;

    public PageResult<SysOrg> page(String keyword, String type, String status, PageQuery pageQuery) {
        Page<SysOrg> page = orgMapper.selectPage(pageQuery.toPage(), Wrappers.<SysOrg>lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), SysOrg::getName, keyword)
                .eq(type != null && !type.isBlank(), SysOrg::getType, type)
                .eq(status != null && !status.isBlank(), SysOrg::getStatus, status)
                .orderByDesc(SysOrg::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public SysOrg getOrg(Long id) {
        SysOrg org = orgMapper.selectById(id);
        if (org == null) {
            throw BizException.notFound("组织");
        }
        return org;
    }

    @Transactional
    public SysOrg save(OrgSaveRequest request) {
        validate(request);
        SysOrg org = new SysOrg();
        apply(org, request);
        if (org.getStatus() == null) {
            org.setStatus(SysOrg.STATUS_ENABLED);
        }
        Entities.forInsert(org);
        try {
            orgMapper.insert(org);
        } catch (DuplicateKeyException e) {
            throw new BizException("ORG_CODE_DUPLICATE", "组织编码已存在：" + request.code());
        }
        return org;
    }

    @Transactional
    public SysOrg update(Long id, OrgSaveRequest request) {
        validate(request);
        getOrg(id);
        SysOrg update = new SysOrg();
        apply(update, request);
        Entities.forUpdate(update, id);
        orgMapper.updateById(update);
        return getOrg(id);
    }

    @Transactional
    public void delete(Long id) {
        getOrg(id);
        orgMapper.deleteById(id);
        memberMapper.delete(Wrappers.<SysOrgMember>lambdaQuery().eq(SysOrgMember::getOrgId, id));
    }

    public List<SysOrgMember> listMembers(Long orgId) {
        getOrg(orgId);
        return memberMapper.selectList(Wrappers.<SysOrgMember>lambdaQuery()
                .eq(SysOrgMember::getOrgId, orgId)
                .orderByDesc(SysOrgMember::getId));
    }

    /** 组织有效成员（批量授权的目标集合）。 */
    public List<SysOrgMember> listActiveMembers(Long orgId) {
        return memberMapper.selectList(Wrappers.<SysOrgMember>lambdaQuery()
                .eq(SysOrgMember::getOrgId, orgId)
                .eq(SysOrgMember::getStatus, SysOrgMember.STATUS_ACTIVE));
    }

    @Transactional
    public SysOrgMember addMember(Long orgId, Long userId, String role) {
        getOrg(orgId);
        if (userId == null) {
            throw BizException.badRequest("用户不能为空");
        }
        SysOrgMember exist = memberMapper.selectOne(Wrappers.<SysOrgMember>lambdaQuery()
                .eq(SysOrgMember::getOrgId, orgId)
                .eq(SysOrgMember::getUserId, userId));
        if (exist != null) {
            if (SysOrgMember.STATUS_ACTIVE.equals(exist.getStatus())) {
                return exist;
            }
            exist.setStatus(SysOrgMember.STATUS_ACTIVE);
            exist.setRole(role == null ? SysOrgMember.ROLE_MEMBER : role);
            memberMapper.updateById(exist);
            return exist;
        }
        SysOrgMember member = new SysOrgMember();
        member.setOrgId(orgId);
        member.setUserId(userId);
        member.setRole(role == null ? SysOrgMember.ROLE_MEMBER : role);
        member.setStatus(SysOrgMember.STATUS_ACTIVE);
        Entities.forInsert(member);
        memberMapper.insert(member);
        return member;
    }

    @Transactional
    public void removeMember(Long orgId, Long userId) {
        SysOrgMember member = memberMapper.selectOne(Wrappers.<SysOrgMember>lambdaQuery()
                .eq(SysOrgMember::getOrgId, orgId)
                .eq(SysOrgMember::getUserId, userId));
        if (member == null) {
            throw BizException.notFound("组织成员");
        }
        SysOrgMember update = new SysOrgMember();
        update.setId(member.getId());
        update.setStatus(SysOrgMember.STATUS_REMOVED);
        memberMapper.updateById(update);
    }

    private void apply(SysOrg org, OrgSaveRequest request) {
        org.setCode(request.code());
        org.setName(request.name());
        org.setType(request.type() == null ? SysOrg.TYPE_INSTITUTION : request.type());
        org.setContactName(request.contactName());
        org.setContactPhone(request.contactPhone());
        org.setStatus(request.status());
        org.setRemark(request.remark());
    }

    private void validate(OrgSaveRequest request) {
        if (request.code() == null || request.code().isBlank()) {
            throw BizException.badRequest("组织编码不能为空");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw BizException.badRequest("组织名称不能为空");
        }
        if (request.type() != null && !SysOrg.TYPE_INSTITUTION.equals(request.type())
                && !SysOrg.TYPE_ENTERPRISE.equals(request.type())) {
            throw BizException.badRequest("组织类型不合法，支持 INSTITUTION/ENTERPRISE");
        }
    }
}
