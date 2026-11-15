package com.exam.platform.modules.system.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link Audited} 的切面实现。
 *
 * <p>两个刻意的行为选择：</p>
 * <ol>
 *   <li><b>只在方法正常返回后记录</b>：异常表示操作失败，记「已执行」会误导排查；</li>
 *   <li><b>审计失败绝不影响主流程</b>：表达式求值出错只降级为记录未解析的原值，
 *       审计是旁路能力，不能因为它的问题回滚业务事务。</li>
 * </ol>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditService auditService;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();
    /** SpEL 解析开销不小，按方法缓存编译后的表达式。 */
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    @Around("@annotation(audited)")
    public Object record(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        Object result = joinPoint.proceed();
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            Object[] args = joinPoint.getArgs();
            MethodBasedEvaluationContext context =
                    new MethodBasedEvaluationContext(null, method, args, parameterNames);
            context.setVariable("result", result);

            String target = evaluate(audited.target(), context, method, "target");
            String detail = evaluate(audited.detail(), context, method, "detail");
            auditService.record(audited.action(), target, detail);
        } catch (Exception e) {
            // 审计是旁路：宁可少一条记录，也不能让业务失败
            log.warn("审计记录失败 action={}", audited.action(), e);
        }
        return result;
    }

    private String evaluate(String expression, MethodBasedEvaluationContext context,
                            Method method, String field) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            Expression compiled = expressionCache.computeIfAbsent(
                    method.toGenericString() + "#" + field + "#" + expression,
                    key -> parser.parseExpression(expression));
            Object value = compiled.getValue(context);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            log.warn("审计表达式求值失败 method={} {}={}", method.getName(), field, expression, e);
            return null;
        }
    }
}
