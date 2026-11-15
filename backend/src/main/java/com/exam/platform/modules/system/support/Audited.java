package com.exam.platform.modules.system.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式审计标记。
 *
 * <p>动机很直接：原来审计靠每个方法手写 {@code auditService.record(...)}，
 * 新增接口时天然会漏——实测管理端就漏了 5 个写接口。
 * 改成注解后，「有没有审计」变成可以静态检查和测试断言的状态，不再依赖人记得。</p>
 *
 * <p>约定：<b>只有方法正常返回才记录审计</b>。抛异常意味着操作没成功，
 * 记一条「已执行」的审计反而误导排查。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audited {

    /** 动作码，如 {@code SUBJECT_UPDATE}，与既有 sys_audit_log.action 保持一致。 */
    String action();

    /**
     * 操作对象，SpEL 表达式，结果形如 {@code exam_subject:12}。
     *
     * <p>可用变量：方法参数名（如 {@code #id}）、{@code #p0} 形式的序号、返回值的 {@code #result}。</p>
     */
    String target() default "";

    /** 附加明细，SpEL 表达式，可引用 {@code #result}；留空则记 null。 */
    String detail() default "";
}
