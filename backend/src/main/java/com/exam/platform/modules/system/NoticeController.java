package com.exam.platform.modules.system;

import com.exam.platform.common.api.Result;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.modules.system.support.Audited;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 公告与系统配置。
 *
 * <p>考生端与后台接口放在同一个类里，但路径与方法级分开：两者共享同一份校验与状态机，
 * 拆成两个类反而要重复维护「什么算可见」的规则。</p>
 */
@RestController
@RequiredArgsConstructor
public class NoticeController {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    /** 时间控件/数据库输出常用空格分隔；与 T 分隔等价，两种都接受。 */
    private static final DateTimeFormatter TIME_FORMAT_SPACE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NoticeService noticeService;

    // ---------------------------------------------------------------- 考生端

    @GetMapping("/v1/notices")
    public Result<List<Map<String, Object>>> listPublished(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String type) {
        return Result.ok(noticeService.pagePublished(pageNo, pageSize, type));
    }

    @GetMapping("/v1/notices/{id}")
    public Result<Map<String, Object>> publishedDetail(@PathVariable Long id) {
        return Result.ok(noticeService.publishedDetail(id));
    }

    // ---------------------------------------------------------------- 后台公告

    @GetMapping("/v1/admin/notices")
    public Result<List<Map<String, Object>>> listAdmin(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(noticeService.pageAdmin(status, pageNo, pageSize));
    }

    @PostMapping("/v1/admin/notices")
    @Audited(action = "NOTICE_CREATE", target = "'cms_notice:' + #result.data",
            detail = "#p0['title']")
    public Result<Long> create(@RequestBody Map<String, Object> body) {
        long id = noticeService.create(text(body, "title"), text(body, "content"),
                bool(body, "pinned"), time(body, "publishAt"), time(body, "expireAt"),
                text(body, "type"));
        return Result.ok(id);
    }

    @PutMapping("/v1/admin/notices/{id}")
    @Audited(action = "NOTICE_UPDATE", target = "'cms_notice:' + #id", detail = "#p0['title']")
    public Result<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        noticeService.update(id, text(body, "title"), text(body, "content"),
                bool(body, "pinned"), time(body, "publishAt"), time(body, "expireAt"),
                text(body, "type"));
        return Result.ok(null);
    }

    @PostMapping("/v1/admin/notices/{id}/publish")
    @Audited(action = "NOTICE_PUBLISH", target = "'cms_notice:' + #id")
    public Result<Void> publish(@PathVariable Long id) {
        noticeService.publish(id);
        return Result.ok(null);
    }

    @PostMapping("/v1/admin/notices/{id}/archive")
    @Audited(action = "NOTICE_ARCHIVE", target = "'cms_notice:' + #id")
    public Result<Void> archive(@PathVariable Long id) {
        noticeService.archive(id);
        return Result.ok(null);
    }

    @DeleteMapping("/v1/admin/notices/{id}")
    @Audited(action = "NOTICE_DELETE", target = "'cms_notice:' + #id")
    public Result<Void> remove(@PathVariable Long id) {
        noticeService.remove(id);
        return Result.ok(null);
    }

    // ---------------------------------------------------------------- 后台配置

    @GetMapping("/v1/admin/configs")
    public Result<List<Map<String, Object>>> listConfigs() {
        return Result.ok(noticeService.listConfigs());
    }

    @PutMapping("/v1/admin/configs/{key}")
    @Audited(action = "CONFIG_UPDATE", target = "'sys_config:' + #key", detail = "#p0['value']")
    public Result<Void> saveConfig(@PathVariable String key, @RequestBody Map<String, Object> body) {
        noticeService.saveConfig(key, text(body, "value"), text(body, "remark"));
        return Result.ok(null);
    }

    // ---------------------------------------------------------------- 入参解析

    private String text(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private Boolean bool(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value instanceof Boolean b) {
            return b;
        }
        return value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    /**
     * 接受 ISO 形式的时间串；空串按「不设置」处理，避免前端传 "" 直接 500。
     *
     * <p>T 分隔（{@code 2026-11-19T08:23:23}）与空格分隔（{@code 2026-11-19 08:23:23}）
     * 都接受：前端编辑回显的正是数据库输出的空格格式，只认 T 会在「编辑但没动时间」时直接 400。</p>
     */
    private LocalDateTime time(Map<String, Object> body, String field) {
        String raw = text(body, field);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        for (DateTimeFormatter formatter : new DateTimeFormatter[]{TIME_FORMAT, TIME_FORMAT_SPACE}) {
            try {
                return LocalDateTime.parse(value, formatter);
            } catch (RuntimeException ignored) {
                // 换下一种格式继续尝试
            }
        }
        try {
            // 兼容带毫秒的 ISO-8601 写法
            return LocalDateTime.parse(value);
        } catch (RuntimeException e) {
            throw BizException.badRequest("时间格式应为 yyyy-MM-dd HH:mm:ss：" + raw);
        }
    }
}
