package com.exam.platform.common.exception;

import com.exam.platform.common.api.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理。统一转成 {@link Result}，HTTP 状态保持 200（业务错误码在 code 字段），
 * 便于小程序与管理端用同一套解析逻辑。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常 code={} message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 数据库超时与连接类异常单独归类。
     *
     * <p>这三类异常在集中开考时是「预期内的过载信号」，不是代码 bug：
     * 走通用分支会打 error 级日志并返回「服务开小差」，
     * 既淹没有效告警，也让客户端不知道该不该重试。这里统一降为 warn 并给出可重试的语义。</p>
     */
    @ExceptionHandler({QueryTimeoutException.class, CannotAcquireLockException.class})
    public Result<Void> handleDbTimeout(Exception e) {
        log.warn("数据库执行超时/锁等待超时：{}", e.getMessage());
        return Result.fail("COMMON_TIMEOUT", "服务繁忙，请稍后重试");
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, CannotCreateTransactionException.class})
    public Result<Void> handleDbUnavailable(Exception e) {
        log.warn("数据库连接不可用：{}", e.getMessage());
        return Result.fail("COMMON_BUSY", "服务繁忙，请稍后重试");
    }

    /**
     * 唯一键冲突。
     *
     * <p>项目 / 科目 / 课程的编码都是「租户内唯一」，重复时数据库抛
     * {@link DuplicateKeyException}。不单独处理会落到兜底分支，前端看到的是
     * 「服务开小差了」——运营只会以为系统坏了，而实际要做的是换一个编码。
     * 这类冲突是用户输入问题，不是服务故障。</p>
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一键冲突：{}", e.getMessage());
        return Result.fail("COMMON_CONFLICT", "编码已存在，请更换后重试（同一租户内编码必须唯一）");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleValidation(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return Result.fail("COMMON_VALIDATION_FAILED", message.isEmpty() ? "参数校验失败" : message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.fail("COMMON_VALIDATION_FAILED", "缺少参数：" + e.getParameterName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        return Result.fail("COMMON_BAD_REQUEST", "请求体格式不正确");
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandler(NoHandlerFoundException e) {
        return Result.fail("COMMON_NOT_FOUND", "接口不存在：" + e.getRequestURL());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethod(HttpRequestMethodNotSupportedException e) {
        return Result.fail("COMMON_METHOD_NOT_ALLOWED", "不支持的请求方法：" + e.getMethod());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("未预期异常", e);
        return Result.fail("COMMON_SERVER_ERROR", "服务开小差了，请稍后重试");
    }
}
