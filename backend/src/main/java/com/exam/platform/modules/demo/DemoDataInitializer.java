package com.exam.platform.modules.demo;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.platform.common.util.Json;
import com.exam.platform.modules.content.entity.ExamProject;
import com.exam.platform.modules.content.entity.ExamSubject;
import com.exam.platform.modules.content.mapper.ExamProjectMapper;
import com.exam.platform.modules.content.mapper.ExamSubjectMapper;
import com.exam.platform.modules.course.entity.CourseInfo;
import com.exam.platform.modules.course.entity.CourseLesson;
import com.exam.platform.modules.course.mapper.CourseInfoMapper;
import com.exam.platform.modules.course.mapper.CourseLessonMapper;
import com.exam.platform.modules.exam.ExamSessionService;
import com.exam.platform.modules.exam.dto.SessionSaveRequest;
import com.exam.platform.modules.paper.entity.ExamPaper;
import com.exam.platform.modules.paper.mapper.ExamPaperMapper;
import com.exam.platform.modules.paper.model.PaperQuestionItem;
import com.exam.platform.modules.question.entity.QbQuestion;
import com.exam.platform.modules.question.mapper.QbQuestionMapper;
import com.exam.platform.modules.question.model.OptionItem;
import com.exam.platform.modules.system.entity.SysTenant;
import com.exam.platform.modules.system.entity.SysUser;
import com.exam.platform.modules.system.mapper.SysTenantMapper;
import com.exam.platform.modules.system.mapper.SysUserMapper;
import com.exam.platform.modules.trade.entity.MallProduct;
import com.exam.platform.modules.trade.mapper.MallProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 演示数据初始化：幂等，仅在库中没有对应数据时写入。
 * 生产环境通过 {@code app.demo-data.enabled=false} 关闭。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.demo-data.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataInitializer implements ApplicationRunner {

    private final SysTenantMapper tenantMapper;
    private final SysUserMapper userMapper;
    private final ExamProjectMapper projectMapper;
    private final ExamSubjectMapper subjectMapper;
    private final QbQuestionMapper questionMapper;
    private final ExamPaperMapper paperMapper;
    private final MallProductMapper productMapper;
    private final CourseInfoMapper courseMapper;
    private final CourseLessonMapper lessonMapper;
    private final ExamSessionService sessionService;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    /** 扩展演示数据（订单/成绩/机构等）开关：仅当配置显式开启时写入，测试上下文默认关闭。 */
    @org.springframework.beans.factory.annotation.Value("${app.demo-data.extra.enabled:false}")
    private boolean extraDemoEnabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedTenant();
        seedUsers();
        Long projectId = seedContent();
        if (projectId != null) {
            seedProducts(projectId);
            seedCourses(projectId);
            seedExtraData();
        }
        log.info("演示数据初始化完成");
    }

    private void seedTenant() {
        if (tenantMapper.selectCount(Wrappers.<SysTenant>lambdaQuery()
                .eq(SysTenant::getCode, "PLATFORM")) > 0) {
            return;
        }
        SysTenant tenant = new SysTenant();
        tenant.setCode("PLATFORM");
        tenant.setName("平台运营方");
        tenant.setStatus("ENABLED");
        tenantMapper.insert(tenant);
    }

    private void seedUsers() {
        if (userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, "admin")) == 0) {
            SysUser admin = new SysUser();
            admin.setTenantId(1L);
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setNickname("平台管理员");
            admin.setRoleCode("ADMIN");
            admin.setStatus("ENABLED");
            userMapper.insert(admin);
            log.info("已创建管理员账号 admin / admin123");
        }
        if (userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, "student")) == 0) {
            SysUser student = new SysUser();
            student.setTenantId(1L);
            student.setUsername("student");
            student.setPassword(passwordEncoder.encode("123456"));
            student.setNickname("体验考生");
            student.setRoleCode("STUDENT");
            student.setStatus("ENABLED");
            userMapper.insert(student);
        }
    }

    private Long seedContent() {
        ExamProject project = projectMapper.selectOne(Wrappers.<ExamProject>lambdaQuery()
                .eq(ExamProject::getCode, "PMP").last("LIMIT 1"));
        if (project == null) {
            project = new ExamProject();
            project.setTenantId(1L);
            project.setCode("PMP");
            project.setName("PMP 项目管理认证");
            project.setIntro("覆盖项目管理基础、敏捷与混合方法论的综合认证考试。");
            project.setTargetAudience("项目管理人员、PMO、想转岗项目管理的工程师");
            project.setStatus("PUBLISHED");
            project.setSortNo(1);
            // 演示封面：位于 admin-web/public/covers，生产环境替换为 CDN 地址即可
            project.setCoverUrl("http://localhost:5173/covers/pmp.jpg");
            projectMapper.insert(project);
        }

        ExamSubject subject = subjectMapper.selectOne(Wrappers.<ExamSubject>lambdaQuery()
                .eq(ExamSubject::getProjectId, project.getId())
                .eq(ExamSubject::getCode, "PMP-BASE").last("LIMIT 1"));
        if (subject == null) {
            subject = new ExamSubject();
            subject.setTenantId(1L);
            subject.setProjectId(project.getId());
            subject.setCode("PMP-BASE");
            subject.setName("项目管理基础");
            subject.setIntro("项目管理五大过程组与十大知识领域");
            subject.setStatus("ENABLED");
            subject.setSortNo(1);
            subjectMapper.insert(subject);
        }

        if (questionMapper.selectCount(Wrappers.<QbQuestion>lambdaQuery()
                .eq(QbQuestion::getSubjectId, subject.getId())) == 0) {
            seedQuestions(subject.getId());
        }

        Long projectId = project.getId();
        Long subjectId = subject.getId();
        ExamPaper paper = paperMapper.selectOne(Wrappers.<ExamPaper>lambdaQuery()
                .eq(ExamPaper::getProjectId, projectId).last("LIMIT 1"));
        if (paper == null) {
            List<QbQuestion> questions = questionMapper.selectList(Wrappers.<QbQuestion>lambdaQuery()
                    .eq(QbQuestion::getSubjectId, subjectId)
                    .orderByAsc(QbQuestion::getId));
            List<PaperQuestionItem> items = questions.stream()
                    .map(q -> new PaperQuestionItem(q.getId(), 10))
                    .toList();
            paper = new ExamPaper();
            paper.setTenantId(1L);
            paper.setProjectId(projectId);
            paper.setSubjectId(subjectId);
            paper.setName("PMP 模拟试卷 A");
            paper.setMode(ExamPaper.MODE_FIXED);
            paper.setQuestionJson(Json.write(items));
            paper.setTotalScore(items.size() * 10);
            paper.setStatus(ExamPaper.STATUS_DRAFT);
            paper.setVersion(1);
            paperMapper.insert(paper);
        }

        if (sessionService.listParticipable(projectId).isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            SessionSaveRequest request = new SessionSaveRequest(projectId, paper.getId(),
                    "PMP 模拟考试（体验场）",
                    now.minusDays(1), now.plusHours(2),
                    now, now.plusHours(3),
                    30, 60, 0, null, 100, "v1",
                    "1. 开考后 30 分钟内可进入；2. 交卷后立即出成绩；3. 请勿刷新页面导致错过交卷。");
            var session = sessionService.save(request);
            sessionService.publish(session.getId());
        }
        return projectId;
    }

    private void seedQuestions(Long subjectId) {
        insertQuestion(subjectId, QbQuestion.TYPE_SINGLE, "项目管理的三大基本要素不包括以下哪一项？",
                List.of(new OptionItem("A", "范围"), new OptionItem("B", "时间"),
                        new OptionItem("C", "成本"), new OptionItem("D", "股权")),
                List.of("D"), "项目管理铁三角是范围、时间、成本（质量），不包含股权。", "EASY");
        insertQuestion(subjectId, QbQuestion.TYPE_SINGLE, "WBS 的主要作用是？",
                List.of(new OptionItem("A", "定义项目范围并分解可交付成果"),
                        new OptionItem("B", "记录项目风险"),
                        new OptionItem("C", "编制财务报表"),
                        new OptionItem("D", "进行绩效评估")),
                List.of("A"), "WBS（工作分解结构）用于把项目范围逐层分解为可管理的工作包。", "NORMAL");
        insertQuestion(subjectId, QbQuestion.TYPE_MULTIPLE, "以下属于敏捷宣言价值观的是？",
                List.of(new OptionItem("A", "个体与互动高于流程和工具"),
                        new OptionItem("B", "可工作的软件高于详尽的文档"),
                        new OptionItem("C", "完善的合同高于客户合作"),
                        new OptionItem("D", "响应变化高于遵循计划")),
                List.of("A", "B", "D"), "敏捷宣言四项价值观：个体与互动、可工作的软件、客户合作、响应变化。",
                "NORMAL");
        insertQuestion(subjectId, QbQuestion.TYPE_MULTIPLE, "项目风险应对的常见策略包括？",
                List.of(new OptionItem("A", "规避"), new OptionItem("B", "转移"),
                        new OptionItem("C", "减轻"), new OptionItem("D", "接受")),
                List.of("A", "B", "C", "D"), "消极风险应对策略：规避、转移、减轻、接受。", "EASY");
        insertQuestion(subjectId, QbQuestion.TYPE_JUDGE, "项目章程由项目经理签发。",
                List.of(new OptionItem("A", "正确"), new OptionItem("B", "错误")),
                List.of("B"), "项目章程通常由项目发起人或高级管理层签发，项目经理负责编写。", "EASY");
        insertQuestion(subjectId, QbQuestion.TYPE_JUDGE, "关键路径上的活动总浮动时间为零。",
                List.of(new OptionItem("A", "正确"), new OptionItem("B", "错误")),
                List.of("A"), "关键路径是项目最长路径，其上活动总浮动时间为零。", "NORMAL");
    }

    private void insertQuestion(Long subjectId, String type, String stem, List<OptionItem> options,
                                List<String> answer, String analysis, String difficulty) {
        QbQuestion question = new QbQuestion();
        question.setTenantId(1L);
        question.setSubjectId(subjectId);
        question.setType(type);
        question.setStem(stem);
        question.setOptionsJson(Json.write(options));
        question.setAnswerJson(Json.write(answer));
        question.setAnalysis(analysis);
        question.setDifficulty(difficulty);
        question.setStatus("ENABLED");
        question.setVersion(1);
        questionMapper.insert(question);
    }

    private void seedProducts(Long projectId) {
        if (productMapper.selectCount(Wrappers.<MallProduct>lambdaQuery()
                .eq(MallProduct::getCode, "QB-PMP")) == 0) {
            MallProduct bank = new MallProduct();
            bank.setTenantId(1L);
            bank.setCode("QB-PMP");
            bank.setName("PMP 题库包（30 天）");
            bank.setType(MallProduct.TYPE_QUESTION_BANK);
            bank.setDescription("解锁 PMP 项目全部练习题与解析，有效期 30 天。");
            bank.setPriceFen(9900L);
            bank.setOriginalPriceFen(19900L);
            bank.setValidityDays(30);
            bank.setResourceType("PROJECT");
            bank.setResourceId(projectId);
            bank.setStatus(MallProduct.STATUS_ON_SALE);
            bank.setSortNo(1);
            productMapper.insert(bank);
        }
        if (productMapper.selectCount(Wrappers.<MallProduct>lambdaQuery()
                .eq(MallProduct::getCode, "VIP-30")) == 0) {
            MallProduct member = new MallProduct();
            member.setTenantId(1L);
            member.setCode("VIP-30");
            member.setName("平台会员 30 天");
            member.setType(MallProduct.TYPE_MEMBER);
            member.setDescription("会员期内可使用会员范围内的题库、课程与模拟考试。");
            member.setPriceFen(19900L);
            member.setOriginalPriceFen(29900L);
            member.setValidityDays(30);
            member.setStatus(MallProduct.STATUS_ON_SALE);
            member.setSortNo(2);
            productMapper.insert(member);
        }
    }

    /**
     * 演示课程：一门免费课（直接可看）+ 一门付费课（需购买或持有 COURSE 权益）。
     * 付费课同时上架一个 COURSE 类型商品，购买成功后权益 resourceId 指向该课程。
     */
    private void seedCourses(Long projectId) {
        CourseInfo free = courseMapper.selectOne(Wrappers.<CourseInfo>lambdaQuery()
                .eq(CourseInfo::getCode, "COURSE-PMP-FREE").last("LIMIT 1"));
        if (free == null) {
            free = new CourseInfo();
            free.setTenantId(1L);
            free.setProjectId(projectId);
            free.setCode("COURSE-PMP-FREE");
            free.setTitle("项目管理入门（免费）");
            free.setSummary("用 3 节课快速建立项目管理的整体认知，适合零基础考生。");
            free.setLecturer("平台教研组");
            free.setPriceFen(0L);
            free.setAccessType(CourseInfo.ACCESS_FREE);
            free.setStatus(CourseInfo.STATUS_PUBLISHED);
            free.setSortNo(1);
            courseMapper.insert(free);
            seedLessons(free.getId(), List.of(
                    new DemoLesson("什么是项目管理", "从启动到收尾，项目生命周期全貌。", false),
                    new DemoLesson("项目铁三角", "范围、时间、成本三者如何相互制约。", false),
                    new DemoLesson("项目经理的角色", "项目经理到底该做什么、不该做什么。", false)));
        }

        CourseInfo paid = courseMapper.selectOne(Wrappers.<CourseInfo>lambdaQuery()
                .eq(CourseInfo::getCode, "COURSE-PMP-PRO").last("LIMIT 1"));
        if (paid == null) {
            paid = new CourseInfo();
            paid.setTenantId(1L);
            paid.setProjectId(projectId);
            paid.setCode("COURSE-PMP-PRO");
            paid.setTitle("PMP 冲刺精讲（付费）");
            paid.setSummary("按考试大纲逐章精讲，含真题拆解与易错点串讲。");
            paid.setLecturer("资深 PMP 讲师");
            paid.setPriceFen(29900L);
            paid.setAccessType(CourseInfo.ACCESS_ENTITLED);
            paid.setStatus(CourseInfo.STATUS_PUBLISHED);
            paid.setSortNo(2);
            courseMapper.insert(paid);
            seedLessons(paid.getId(), List.of(
                    new DemoLesson("课程导学（试看）", "整门课的学习路径与备考建议。", true),
                    new DemoLesson("项目启动过程组", "章程、干系人登记册与启动会。", false),
                    new DemoLesson("项目规划过程组", "范围、进度、成本三大基准的编制。", false),
                    new DemoLesson("项目执行与监控", "变更控制、绩效测量与纠偏。", false)));

            if (productMapper.selectCount(Wrappers.<MallProduct>lambdaQuery()
                    .eq(MallProduct::getCode, "COURSE-PMP-PRO")) == 0) {
                MallProduct product = new MallProduct();
                product.setTenantId(1L);
                product.setCode("COURSE-PMP-PRO");
                product.setName("PMP 冲刺精讲课程");
                product.setType(MallProduct.TYPE_COURSE);
                product.setDescription("购买后解锁全部课时，有效期 180 天。");
                product.setPriceFen(29900L);
                product.setOriginalPriceFen(49900L);
                product.setValidityDays(180);
                product.setResourceType("COURSE");
                product.setResourceId(paid.getId());
                product.setStatus(MallProduct.STATUS_ON_SALE);
                product.setSortNo(3);
                productMapper.insert(product);
            }
        }
    }

    /** 演示课时：标题 + 内容 + 是否可试看。 */
    private record DemoLesson(String title, String content, boolean freePreview) {
    }

    private void seedLessons(Long courseId, List<DemoLesson> lessons) {
        int sortNo = 0;
        for (DemoLesson item : lessons) {
            CourseLesson lesson = new CourseLesson();
            lesson.setTenantId(1L);
            lesson.setCourseId(courseId);
            lesson.setTitle(item.title());
            lesson.setContent(item.content());
            lesson.setDurationSeconds(15 * 60);
            lesson.setFreePreview(item.freePreview() ? 1 : 0);
            lesson.setSortNo(sortNo++);
            lesson.setStatus("ENABLED");
            lessonMapper.insert(lesson);
        }
        CourseInfo update = new CourseInfo();
        update.setId(courseId);
        update.setLessonCount(lessons.size());
        courseMapper.updateById(update);
    }

    // =================== 各功能演示数据（幂等：已有数据则跳过） ===================

    private void seedExtraData() {
        if (!extraDemoEnabled) {
            return;
        }
        Long adminId = userId("admin");
        Long studentId = userId("student");
        Long subjectId = jdbcTemplate.queryForObject(
                "SELECT id FROM exam_subject WHERE tenant_id = 1 ORDER BY id LIMIT 1", Long.class);
        List<Long> questionIds = jdbcTemplate.queryForList(
                "SELECT id FROM qb_question WHERE tenant_id = 1 ORDER BY id", Long.class);
        LocalDateTime now = LocalDateTime.now();

        seedNotices(adminId, now);
        seedCoupons(now);
        seedOrders(studentId, now);
        seedOrgs(now);
        seedRoles(now);
        seedMessageTemplates(studentId, now);
        seedAuditLogs(adminId, now);
        seedStudyTraces(studentId, subjectId, questionIds, now);
        seedExamRecords(studentId, now);
    }

    private void seedNotices(Long adminId, LocalDateTime now) {
        if (countOf("cms_notice") > 0) {
            return;
        }
        jdbcTemplate.update("INSERT INTO cms_notice (tenant_id, title, content, type, status, pinned, "
                        + "publish_at, publisher_id, created_at, updated_at, deleted) "
                        + "VALUES (1,?,?,?,?,?,?,?,?,?,0)",
                "平台正式上线公告", "综合考试平台正式开放报名，支持网页与微信小程序参加考试。",
                "NOTICE", "PUBLISHED", 1, now.minusDays(3), adminId, now.minusDays(3), now.minusDays(3));
        jdbcTemplate.update("INSERT INTO cms_notice (tenant_id, title, content, type, status, pinned, "
                        + "publish_at, expire_at, publisher_id, created_at, updated_at, deleted) "
                        + "VALUES (1,?,?,?,?,?,?,?,?,?,?,0)",
                "双十一备考季活动", "活动期间报名立减 20 元，PMP 题库包限时 8 折。",
                "ACTIVITY", "PUBLISHED", 0, now.minusHours(6), now.plusDays(30),
                adminId, now.minusHours(6), now.minusHours(6));
    }

    private void seedCoupons(LocalDateTime now) {
        if (countOf("marketing_coupon") > 0) {
            return;
        }
        jdbcTemplate.update("INSERT INTO marketing_coupon (tenant_id, code, name, type, threshold_fen, value_fen, "
                        + "discount_percent, total_quantity, per_user_limit, scope_type, valid_from, valid_to, "
                        + "status, created_at, updated_at, deleted) VALUES (1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                "C-100-20", "满 100 减 20", "AMOUNT", 10000L, 2000L, 0, 1000, 1, "ALL",
                now.minusDays(7), now.plusDays(30), "ACTIVE", now.minusDays(7), now.minusDays(7));
        jdbcTemplate.update("INSERT INTO marketing_coupon (tenant_id, code, name, type, threshold_fen, value_fen, "
                        + "discount_percent, total_quantity, per_user_limit, scope_type, valid_from, valid_to, "
                        + "status, created_at, updated_at, deleted) VALUES (1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                "D-95", "全场 95 折券", "DISCOUNT", 0L, 0L, 95, 500, 1, "ALL",
                now.minusDays(3), now.plusDays(15), "STOPPED", now.minusDays(3), now.minusDays(3));
    }

    private void seedOrders(Long studentId, LocalDateTime now) {
        if (countOf("trade_order") > 0) {
            return;
        }
        jdbcTemplate.update("INSERT INTO trade_order (tenant_id, order_no, user_id, product_id, product_name, "
                        + "quantity, total_amount_fen, discount_amount_fen, pay_amount_fen, status, "
                        + "created_at, updated_at, deleted) VALUES (1,?,?,?,?,1,?,?,?,?,?,?,0)",
                "T2026111500001", studentId, productId("QB-PMP"), "PMP 题库包（30 天）",
                9900L, 2000L, 7900L, "PAID", now.minusDays(4), now.minusDays(4));
        jdbcTemplate.update("INSERT INTO trade_order (tenant_id, order_no, user_id, product_id, product_name, "
                        + "quantity, total_amount_fen, discount_amount_fen, pay_amount_fen, status, "
                        + "created_at, updated_at, deleted) VALUES (1,?,?,?,?,1,?,?,?,?,?,?,0)",
                "T2026111700002", studentId, productId("VIP-30"), "平台会员 30 天",
                19900L, 0L, 19900L, "COMPLETED", now.minusDays(2), now.minusDays(2));
        jdbcTemplate.update("INSERT INTO trade_order (tenant_id, order_no, user_id, product_id, product_name, "
                        + "quantity, total_amount_fen, discount_amount_fen, pay_amount_fen, status, "
                        + "created_at, updated_at, deleted) VALUES (1,?,?,?,?,1,?,?,?,?,?,?,0)",
                "T2026111800003", studentId, productId("COURSE-PMP-PRO"), "PMP 冲刺精讲课程",
                29900L, 0L, 29900L, "REFUNDED", now.minusDays(1), now.minusDays(1));

        jdbcTemplate.update("INSERT INTO trade_payment (tenant_id, order_id, order_no, pay_no, channel, "
                        + "amount_fen, status, created_at, updated_at, deleted) VALUES (1,?,?,?,?,?,?,?,?,0)",
                orderId("T2026111500001"), "T2026111500001", "P2026111500001", "WECHAT", 7900L, "PAID",
                now.minusDays(4), now.minusDays(4));
        jdbcTemplate.update("INSERT INTO trade_refund (tenant_id, refund_no, order_id, order_no, user_id, "
                        + "amount_fen, status, created_at, updated_at, deleted) VALUES (1,?,?,?,?,?,?,?,?,0)",
                "R2026111800001", orderId("T2026111800003"), "T2026111800003", studentId, 29900L, "APPLIED",
                now.minusHours(20), now.minusHours(20));
    }

    private void seedOrgs(LocalDateTime now) {
        if (countOf("sys_org") > 0) {
            return;
        }
        jdbcTemplate.update("INSERT INTO sys_org (tenant_id, code, name, type, contact_name, contact_phone, "
                        + "status, remark, created_at, updated_at, deleted) VALUES (1,?,?,?,?,?,?,?,?,?,0)",
                "ORG-LANHAI", "蓝海培训学院", "INSTITUTION", "王老师", "13800001111",
                "ENABLED", "年度合作协议机构", now.minusDays(30), now.minusDays(30));
        jdbcTemplate.update("INSERT INTO sys_org (tenant_id, code, name, type, contact_name, contact_phone, "
                        + "status, remark, created_at, updated_at, deleted) VALUES (1,?,?,?,?,?,?,?,?,?,0)",
                "ORG-TECH", "云帆科技有限公司", "ENTERPRISE", "李经理", "13900002222",
                "ENABLED", "员工内训客户", now.minusDays(20), now.minusDays(20));
    }

    private void seedRoles(LocalDateTime now) {
        Long exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_role WHERE tenant_id = 1 AND code IN ('ORG_TEACHER','FINANCE')", Long.class);
        if (exists != null && exists > 0) {
            return;
        }
        jdbcTemplate.update("INSERT INTO sys_role (tenant_id, code, name, description, built_in, status, "
                        + "created_at, updated_at, deleted) VALUES (1,?,?,?,0,?,?,?,0)",
                "ORG_TEACHER", "机构教务", "管理本机构考生、批次与报名审批", "ENABLED",
                now.minusDays(10), now.minusDays(10));
        jdbcTemplate.update("INSERT INTO sys_role (tenant_id, code, name, description, built_in, status, "
                        + "created_at, updated_at, deleted) VALUES (1,?,?,?,0,?,?,?,0)",
                "FINANCE", "财务审核", "订单退款复核与对账确认", "ENABLED",
                now.minusDays(10), now.minusDays(10));
    }

    private void seedMessageTemplates(Long studentId, LocalDateTime now) {
        if (countOf("msg_template") == 0) {
            jdbcTemplate.update("INSERT INTO msg_template (tenant_id, template_code, title, content, enabled, "
                            + "created_at, updated_at, deleted) VALUES (1,?,?,?,1,?,?,0)",
                    "EXAM_REGISTER", "报名成功通知",
                    "您已成功报名「{sessionName}」，考试时间 {startAt}，请提前 20 分钟入场。",
                    now.minusDays(5), now.minusDays(5));
            jdbcTemplate.update("INSERT INTO msg_template (tenant_id, template_code, title, content, enabled, "
                            + "created_at, updated_at, deleted) VALUES (1,?,?,?,1,?,?,0)",
                    "PAYMENT_SUCCESS", "支付成功通知",
                    "您已支付 {amount} 元，订单号 {orderNo}，权益已发放。",
                    now.minusDays(5), now.minusDays(5));
            jdbcTemplate.update("INSERT INTO msg_template (tenant_id, template_code, title, content, enabled, "
                            + "created_at, updated_at, deleted) VALUES (1,?,?,?,1,?,?,0)",
                    "RESULT_PUBLISHED", "成绩发布通知",
                    "「{sessionName}」成绩已发布，您得分 {score} 分，快去看看吧。",
                    now.minusDays(5), now.minusDays(5));
        }
        if (countOf("msg_task") == 0) {
            jdbcTemplate.update("INSERT INTO msg_task (tenant_id, user_id, scene, template_code, payload_json, "
                            + "status, retry_count, sent_at, created_at, updated_at, deleted) "
                            + "VALUES (1,?,?,?,?,?,0,?,?,?,0)",
                    studentId, "EXAM_REGISTER", "EXAM_REGISTER",
                    "{\"sessionName\":\"PMP 模拟考试（体验场）\"}", "SENT",
                    now.minusDays(1), now.minusDays(1), now.minusDays(1));
            jdbcTemplate.update("INSERT INTO msg_task (tenant_id, user_id, scene, template_code, payload_json, "
                            + "status, retry_count, created_at, updated_at, deleted) VALUES (1,?,?,?,?,?,0,?,?,0)",
                    studentId, "RESULT_PUBLISHED", "RESULT_PUBLISHED",
                    "{\"sessionName\":\"PMP 模拟考试（体验场）\",\"score\":40}", "PENDING",
                    now.minusHours(19), now.minusHours(19));
        }
    }

    private void seedAuditLogs(Long adminId, LocalDateTime now) {
        if (countOf("sys_audit_log") > 0) {
            return;
        }
        jdbcTemplate.update("INSERT INTO sys_audit_log (tenant_id, user_id, username, action, target, detail, "
                        + "ip, created_at, updated_at, deleted) VALUES (1,?,?,?,?,?,?,?,?,0)",
                adminId, "admin", "PAPER_SNAPSHOT_CREATE", "paper_snapshot:1",
                "发布场次时生成不可变试卷快照", "127.0.0.1", now.minusDays(1), now.minusDays(1));
        jdbcTemplate.update("INSERT INTO sys_audit_log (tenant_id, user_id, username, action, target, detail, "
                        + "ip, created_at, updated_at, deleted) VALUES (1,?,?,?,?,?,?,?,?,0)",
                adminId, "admin", "NOTICE_CREATE", "notice:2",
                "创建活动公告「双十一备考季活动」", "127.0.0.1", now.minusHours(6), now.minusHours(6));
        jdbcTemplate.update("INSERT INTO sys_audit_log (tenant_id, user_id, username, action, target, detail, "
                        + "ip, created_at, updated_at, deleted) VALUES (1,?,?,?,?,?,?,?,?,0)",
                adminId, "admin", "COUPON_CREATE", "marketing_coupon:1",
                "创建满减券「满 100 减 20」", "127.0.0.1", now.minusDays(7), now.minusDays(7));
    }

    private void seedStudyTraces(Long studentId, Long subjectId, List<Long> questionIds, LocalDateTime now) {
        if (countOf("exam_wrong_question") == 0 && questionIds.size() >= 2) {
            jdbcTemplate.update("INSERT INTO exam_wrong_question (tenant_id, user_id, question_id, wrong_count, "
                            + "last_wrong_at, mastered, created_at, updated_at, deleted) VALUES (1,?,?,?,?,0,?,?,0)",
                    studentId, questionIds.get(1), 2, now.minusDays(2), now.minusDays(2), now.minusDays(2));
            jdbcTemplate.update("INSERT INTO exam_wrong_question (tenant_id, user_id, question_id, wrong_count, "
                            + "last_wrong_at, mastered, created_at, updated_at, deleted) VALUES (1,?,?,?,?,0,?,?,0)",
                    studentId, questionIds.get(2), 1, now.minusDays(1), now.minusDays(1), now.minusDays(1));
        }
        if (countOf("user_favorite") == 0 && questionIds.size() >= 3) {
            jdbcTemplate.update("INSERT INTO user_favorite (tenant_id, user_id, question_id, created_at, "
                            + "updated_at, deleted) VALUES (1,?,?,?,?,0)",
                    studentId, questionIds.get(0), now.minusDays(3), now.minusDays(3));
            jdbcTemplate.update("INSERT INTO user_favorite (tenant_id, user_id, question_id, created_at, "
                            + "updated_at, deleted) VALUES (1,?,?,?,?,0)",
                    studentId, questionIds.get(4), now.minusDays(1), now.minusDays(1));
        }
        if (countOf("learn_practice") == 0) {
            jdbcTemplate.update("INSERT INTO learn_practice (tenant_id, user_id, subject_id, mode, total_count, "
                            + "answered_count, correct_count, status, created_at, updated_at, deleted) "
                            + "VALUES (1,?,?,?,?,?,?,?,?,?,0)",
                    studentId, subjectId, "SEQUENCE", 6, 6, 4, "FINISHED", now.minusDays(2), now.minusDays(2));
            jdbcTemplate.update("INSERT INTO learn_practice (tenant_id, user_id, subject_id, mode, total_count, "
                            + "answered_count, correct_count, status, created_at, updated_at, deleted) "
                            + "VALUES (1,?,?,?,?,?,?,?,?,?,0)",
                    studentId, subjectId, "RANDOM", 6, 3, 2, "IN_PROGRESS", now.minusHours(3), now.minusHours(3));
        }
    }

    private void seedExamRecords(Long studentId, LocalDateTime now) {
        if (countOf("exam_registration") > 0) {
            return;
        }
        List<Long> sessionIds = jdbcTemplate.queryForList(
                "SELECT id FROM exam_session WHERE tenant_id = 1 AND snapshot_id IS NOT NULL "
                        + "ORDER BY id DESC", Long.class);
        if (sessionIds.isEmpty()) {
            return;
        }
        Long sessionId = sessionIds.get(0);
        Long snapshotId = jdbcTemplate.queryForObject(
                "SELECT snapshot_id FROM exam_session WHERE id = ?", Long.class, sessionId);
        Long projectId = jdbcTemplate.queryForObject(
                "SELECT project_id FROM exam_session WHERE id = ?", Long.class, sessionId);
        jdbcTemplate.update("INSERT INTO exam_registration (tenant_id, session_id, project_id, user_id, status, "
                        + "ticket_no, created_at, updated_at, deleted) VALUES (1,?,?,?,?,?,?,?,0)",
                sessionId, projectId, studentId, "CONFIRMED",
                "TK" + sessionId + "0001", now.minusDays(1), now.minusDays(1));
        Long registrationId = jdbcTemplate.queryForObject(
                "SELECT id FROM exam_registration WHERE session_id = ? AND user_id = ?", Long.class,
                sessionId, studentId);
        jdbcTemplate.update("INSERT INTO exam_attempt (tenant_id, session_id, registration_id, user_id, "
                        + "paper_snapshot_id, attempt_no, status, scoring_version, answer_version, "
                        + "created_at, updated_at, deleted) VALUES (1,?,?,?,?,1,?,1,2,?,?,0)",
                sessionId, registrationId, studentId, snapshotId, "SUBMITTED",
                now.minusHours(21), now.minusHours(20));
        Long attemptId = jdbcTemplate.queryForObject(
                "SELECT id FROM exam_attempt WHERE registration_id = ?", Long.class, registrationId);
        Integer questionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM qb_question WHERE tenant_id = 1", Integer.class);
        int total = questionCount == null ? 0 : questionCount;
        jdbcTemplate.update("INSERT INTO exam_result (tenant_id, attempt_id, session_id, user_id, total_score, "
                        + "full_score, correct_count, question_count, scoring_version, status, revision, "
                        + "created_at, updated_at, deleted) VALUES (1,?,?,?,?,?,?,?,1,?,1,?,?,0)",
                attemptId, sessionId, studentId, new java.math.BigDecimal("40.00"),
                total * 10, 4, total, "GRADED", now.minusHours(20), now.minusHours(20));
    }

    private long countOf(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    private Long userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private Long productId(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM mall_product WHERE code = ?", Long.class, code);
    }

    private Long orderId(String orderNo) {
        return jdbcTemplate.queryForObject("SELECT id FROM trade_order WHERE order_no = ?", Long.class, orderNo);
    }
}
