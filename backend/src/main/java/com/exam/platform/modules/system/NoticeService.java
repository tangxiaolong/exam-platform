package com.exam.platform.modules.system;

import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公告与系统配置。
 *
 * <p>公告不做「定时改状态」：状态是运营意图，是否对外可见由
 * {@code publish_at / expire_at} 时间窗在查询时决定。这样不会出现
 * 「定时任务漏跑导致公告该下线还在线」——时间判断天然幂等。</p>
 *
 * <p>配置用本地缓存 + 写时失效。单实例下立即生效；多实例部署时需要换成
 * 集中式失效（如 Redis pub/sub），这一点在文档里写明，避免有人误以为已经支持集群。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    private static final int MAX_PAGE_SIZE = 100;
    private final ConcurrentHashMap<String, String> configCache = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------- 公告

    @Transactional
    public long create(String title, String content, Boolean pinned,
                       LocalDateTime publishAt, LocalDateTime expireAt, String type) {
        assertTitle(title);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO cms_notice (tenant_id, title, content, type, status, pinned, publish_at, "
                        + "expire_at, publisher_id, created_at, updated_at, deleted) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                UserContext.tenantId(), title.trim(), content,
                type == null || type.isBlank() ? "NOTICE" : type.trim().toUpperCase(),
                STATUS_DRAFT,
                Boolean.TRUE.equals(pinned) ? 1 : 0, publishAt, expireAt,
                UserContext.userId(), now, now);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM cms_notice WHERE tenant_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, UserContext.tenantId());
        log.info("创建公告 id={} title={}", id, title);
        return id == null ? 0L : id;
    }

    @Transactional
    public void update(Long id, String title, String content, Boolean pinned,
                       LocalDateTime publishAt, LocalDateTime expireAt, String type) {
        requireNotice(id);
        assertTitle(title);
        // type 允许为空：编辑时空值表示「不改类型」，用 COALESCE 保留原值，
        // 避免旧客户端只传标题/正文时把活动公告意外降级为普通公告
        String normalizedType = type == null || type.isBlank() ? null : type.trim().toUpperCase();
        jdbcTemplate.update(
                "UPDATE cms_notice SET title = ?, content = ?, type = COALESCE(?, type), pinned = ?, "
                        + "publish_at = ?, expire_at = ?, updated_at = ? "
                        + "WHERE tenant_id = ? AND id = ? AND deleted = 0",
                title.trim(), content, normalizedType, Boolean.TRUE.equals(pinned) ? 1 : 0,
                publishAt, expireAt, LocalDateTime.now(), UserContext.tenantId(), id);
    }

    /** 发布：未设置生效时间时按「立即生效」处理，避免发布后立刻可见却排在列表底部。 */
    @Transactional
    public void publish(Long id) {
        Map<String, Object> notice = requireNotice(id);
        LocalDateTime publishAt = (LocalDateTime) notice.get("publish_at");
        if (publishAt == null) {
            publishAt = LocalDateTime.now();
        }
        jdbcTemplate.update(
                "UPDATE cms_notice SET status = ?, publish_at = ?, updated_at = ? "
                        + "WHERE tenant_id = ? AND id = ? AND deleted = 0",
                STATUS_PUBLISHED, publishAt, LocalDateTime.now(), UserContext.tenantId(), id);
        log.info("发布公告 id={}", id);
    }

    /**
     * 定时发布：把「已到生效时间」的草稿转为已发布。
     *
     * <p>跨租户执行——调度是系统级动作，没有请求上下文。条件里带
     * {@code status = DRAFT}：已发布、已归档的公告不会被重复触碰；
     * 多实例同时扫也安全，先到者改掉状态，后到者的 UPDATE 影响行数为 0。</p>
     */
    @Transactional
    public int publishDue() {
        LocalDateTime now = LocalDateTime.now();
        int affected = jdbcTemplate.update(
                "UPDATE cms_notice SET status = ?, updated_at = ? "
                        + "WHERE status = ? AND publish_at IS NOT NULL AND publish_at <= ? "
                        + "AND deleted = 0",
                STATUS_PUBLISHED, now, STATUS_DRAFT, now);
        if (affected > 0) {
            log.info("定时发布公告 {} 条", affected);
        }
        return affected;
    }

    @Transactional
    public void archive(Long id) {
        requireNotice(id);
        jdbcTemplate.update(
                "UPDATE cms_notice SET status = ?, updated_at = ? "
                        + "WHERE tenant_id = ? AND id = ? AND deleted = 0",
                STATUS_ARCHIVED, LocalDateTime.now(), UserContext.tenantId(), id);
    }

    @Transactional
    public void remove(Long id) {
        jdbcTemplate.update(
                "UPDATE cms_notice SET deleted = 1, updated_at = ? "
                        + "WHERE tenant_id = ? AND id = ? AND deleted = 0",
                LocalDateTime.now(), UserContext.tenantId(), id);
    }

    /** 后台列表：能看到草稿与已归档，按创建时间倒序。 */
    public List<Map<String, Object>> pageAdmin(String status, int pageNo, int pageSize) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, title, content, type, status, pinned, publish_at, expire_at, created_at, updated_at "
                        + "FROM cms_notice WHERE tenant_id = ? AND deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(UserContext.tenantId());
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
        sql.append(" ORDER BY pinned DESC, created_at DESC LIMIT ? OFFSET ?");
        int size = normalizePageSize(pageSize);
        args.add(size);
        args.add((Math.max(1, pageNo) - 1) * size);
        return jdbcTemplate.query(sql.toString(), (rs, i) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id"));
            item.put("title", rs.getString("title"));
            // 正文一并返回：管理端编辑要能回显原文，否则一保存就把草稿正文清空了
            item.put("content", rs.getString("content"));
            item.put("status", rs.getString("status"));
            item.put("type", rs.getString("type"));
            item.put("pinned", rs.getInt("pinned"));
            item.put("publishAt", rs.getTimestamp("publish_at"));
            item.put("expireAt", rs.getTimestamp("expire_at"));
            item.put("createdAt", rs.getTimestamp("created_at"));
            item.put("updatedAt", rs.getTimestamp("updated_at"));
            return item;
        }, args.toArray());
    }

    /** 考生端列表：只返回已发布且处于生效时间窗内的公告，置顶优先。 */
    public List<Map<String, Object>> pagePublished(int pageNo, int pageSize) {
        return pagePublished(pageNo, pageSize, null);
    }

    /** 重载：type 非空时按公告类型过滤（首页活动 = type=ACTIVITY）；null 不过滤，保持全量口径。 */
    public List<Map<String, Object>> pagePublished(int pageNo, int pageSize, String type) {
        int size = normalizePageSize(pageSize);
        LocalDateTime now = LocalDateTime.now();
        return jdbcTemplate.query(
                "SELECT id, title, pinned, publish_at, type FROM cms_notice "
                        + "WHERE tenant_id = ? AND deleted = 0 AND status = ? "
                        + "AND (type = COALESCE(?, type)) "
                        + "AND (publish_at IS NULL OR publish_at <= ?) "
                        + "AND (expire_at IS NULL OR expire_at > ?) "
                        + "ORDER BY pinned DESC, publish_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, i) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("title", rs.getString("title"));
                    item.put("pinned", rs.getInt("pinned"));
                    item.put("publishAt", rs.getTimestamp("publish_at"));
                    item.put("type", rs.getString("type"));
                    return item;
                },
                UserContext.tenantId(), STATUS_PUBLISHED, type, now, now, size,
                (Math.max(1, pageNo) - 1) * size);
    }

    /** 考生端详情：未发布 / 未到生效时间 / 已过期一律视为不存在。 */
    public Map<String, Object> publishedDetail(Long id) {
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT id, title, content, pinned, publish_at, expire_at "
                        + "FROM cms_notice WHERE tenant_id = ? AND id = ? AND deleted = 0 "
                        + "AND status = ? AND (publish_at IS NULL OR publish_at <= ?) "
                        + "AND (expire_at IS NULL OR expire_at > ?)",
                (rs, i) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("title", rs.getString("title"));
                    item.put("content", rs.getString("content"));
                    item.put("pinned", rs.getInt("pinned"));
                    item.put("publishAt", rs.getTimestamp("publish_at"));
                    item.put("expireAt", rs.getTimestamp("expire_at"));
                    return item;
                },
                UserContext.tenantId(), id, STATUS_PUBLISHED, now, now);
        if (rows.isEmpty()) {
            // 刻意不区分「不存在」与「不可见」：区分开等于给未发布公告做存在性探测
            throw BizException.notFound("公告不存在或已下线");
        }
        return rows.get(0);
    }

    // ---------------------------------------------------------------- 配置

    public List<Map<String, Object>> listConfigs() {
        return jdbcTemplate.query(
                "SELECT config_key, config_value, remark, updated_at FROM sys_config "
                        + "WHERE tenant_id = ? AND deleted = 0 ORDER BY config_key",
                (rs, i) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("configKey", rs.getString("config_key"));
                    item.put("configValue", rs.getString("config_value"));
                    item.put("remark", rs.getString("remark"));
                    item.put("updatedAt", rs.getTimestamp("updated_at"));
                    return item;
                },
                UserContext.tenantId());
    }

    /** 读取配置，缺失时返回默认值。 */
    public String getConfig(String key, String defaultValue) {
        String cached = configCache.get(cacheKey(key));
        if (cached != null) {
            return cached;
        }
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT config_value FROM sys_config "
                        + "WHERE tenant_id = ? AND config_key = ? AND deleted = 0",
                String.class, UserContext.tenantId(), key);
        String value = rows.isEmpty() ? defaultValue : rows.get(0);
        if (value != null) {
            configCache.put(cacheKey(key), value);
        }
        return value;
    }

    public int getConfigInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(getConfig(key, String.valueOf(defaultValue)).trim());
        } catch (RuntimeException e) {
            // 配置被填成非法值时退回默认值：一个错字不该让整个列表接口 500
            log.warn("配置值非法 key={}", key);
            return defaultValue;
        }
    }

    @Transactional
    public void saveConfig(String key, String value, String remark) {
        if (key == null || key.isBlank()) {
            throw BizException.badRequest("配置键不能为空");
        }
        String trimmedKey = key.trim();
        long tenantId = UserContext.tenantId();
        int updated = jdbcTemplate.update(
                "UPDATE sys_config SET config_value = ?, remark = ?, updated_at = ? "
                        + "WHERE tenant_id = ? AND config_key = ? AND deleted = 0",
                value, remark, LocalDateTime.now(), tenantId, trimmedKey);
        if (updated == 0) {
            LocalDateTime now = LocalDateTime.now();
            jdbcTemplate.update(
                    "INSERT INTO sys_config (tenant_id, config_key, config_value, remark, "
                            + "created_at, updated_at, deleted) VALUES (?, ?, ?, ?, ?, ?, 0)",
                    tenantId, trimmedKey, value, remark, now, now);
        }
        // 写完立刻失效：配置的意义就是「改了马上生效」，缓存过期时间靠不住
        configCache.remove(cacheKey(trimmedKey));
        log.info("更新配置 key={} value={}", trimmedKey, value);
    }

    private String cacheKey(String key) {
        return UserContext.tenantId() + ":" + key;
    }

    private Map<String, Object> requireNotice(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, status, publish_at FROM cms_notice "
                        + "WHERE tenant_id = ? AND id = ? AND deleted = 0",
                UserContext.tenantId(), id);
        if (rows.isEmpty()) {
            throw BizException.notFound("公告不存在");
        }
        return rows.get(0);
    }

    private void assertTitle(String title) {
        if (title == null || title.isBlank()) {
            throw BizException.badRequest("公告标题不能为空");
        }
        if (title.trim().length() > 200) {
            throw BizException.badRequest("公告标题不能超过 200 字");
        }
    }

    private int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
