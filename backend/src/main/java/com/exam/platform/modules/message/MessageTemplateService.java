package com.exam.platform.modules.message;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.common.util.Entities;
import com.exam.platform.modules.message.entity.MsgTemplate;
import com.exam.platform.modules.message.mapper.MsgTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息模板：运营可改文案，发送时实时渲染。
 *
 * <p>{@code render} 的取值顺序是「库里配的 → 代码兜底 → 放弃渲染」：
 * 前两级保证「文案可运营」，最后一级保证「模板都没配也不能让消息发不出去」。</p>
 *
 * <p>缓存的是<b>模板原文</b>而不是渲染结果——渲染依赖每条消息的 payload，缓存渲染结果
 * 会让所有消息都变成同一个人的内容。缓存按租户隔离（不同租户可以有各自的措辞），
 * 运营改文案时主动失效当前租户该编码的缓存，所以「改完立刻生效」；
 * 另留 60 秒 TTL 兜底多实例部署（B 实例上的缓存最迟一分钟内跟上）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageTemplateService {

    private static final long CACHE_TTL_MS = 60_000L;

    private final MsgTemplateMapper templateMapper;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 新增或覆盖某编码的文案（同编码只保留一条，改文案永远是 UPDATE）。 */
    @Transactional
    public MsgTemplate upsert(String templateCode, String title, String content, Boolean enabled) {
        if (templateCode == null || templateCode.isBlank()) {
            throw BizException.badRequest("模板编码不能为空");
        }
        if (title == null || title.isBlank()) {
            throw BizException.badRequest("消息标题不能为空");
        }
        if (content == null || content.isBlank()) {
            throw BizException.badRequest("消息正文不能为空");
        }
        int enabledFlag = enabled == null || enabled ? MsgTemplate.ENABLED : MsgTemplate.DISABLED;

        // 先失效再判存在：否则「这条模板到底有没有」会读到上一版缓存
        invalidate(templateCode);
        MsgTemplate existing = find(templateCode);
        if (existing != null) {
            MsgTemplate update = new MsgTemplate();
            update.setTitle(title);
            update.setContent(content);
            update.setEnabled(enabledFlag);
            Entities.forUpdate(update, existing.getId());
            templateMapper.updateById(update);
            invalidate(templateCode);
            return find(templateCode);
        }

        MsgTemplate template = new MsgTemplate();
        template.setTemplateCode(templateCode);
        template.setTitle(title);
        template.setContent(content);
        template.setEnabled(enabledFlag);
        Entities.forInsert(template);
        templateMapper.insert(template);
        invalidate(templateCode);
        return template;
    }

    public List<MsgTemplate> list() {
        return templateMapper.selectList(Wrappers.<MsgTemplate>lambdaQuery()
                .orderByAsc(MsgTemplate::getTemplateCode));
    }

    public MsgTemplate find(String templateCode) {
        return load(templateCode).orElse(null);
    }

    private Optional<MsgTemplate> load(String templateCode) {
        String key = tenantKey() + "|" + templateCode;
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(key);
        if (entry != null && now - entry.at() <= CACHE_TTL_MS) {
            return entry.value();
        }
        Optional<MsgTemplate> loaded = Optional.ofNullable(templateMapper.selectOne(
                Wrappers.<MsgTemplate>lambdaQuery()
                        .eq(MsgTemplate::getTemplateCode, templateCode)));
        cache.put(key, new CacheEntry(loaded, now));
        return loaded;
    }

    private void invalidate(String templateCode) {
        cache.remove(tenantKey() + "|" + templateCode);
    }

    /**
     * 清空模板缓存。
     *
     * <p>两个用途：运维手工改过 {@code msg_template} 表之后强制刷新；
     * 集成测试在每个用例重建库之后调用（库重建了，缓存里还留着上一轮的数据，
     * 会出现「库里明明是这条、渲染出来却是那条」的错乱）。</p>
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * 缓存按租户隔离，取值必须与租户插件一致（插件用的就是 {@code UserContext.tenantId()}，
     * 无登录态时回落到平台租户）。
     *
     * <p>这里若自己另算一套（例如回落到 0），会出现「运营在后台改了文案、失效了平台租户那份缓存，
     * 而定时任务线程按 0 去读另一份旧缓存」——改文案看起来不生效，且只有在发送任务里才暴露。</p>
     */
    private long tenantKey() {
        return UserContext.tenantId();
    }

    private record CacheEntry(Optional<MsgTemplate> value, long at) {
    }

    /**
     * 渲染一条可读消息。
     *
     * <p>未知占位符原样保留（而不是替换成空串）：消息里出现 {@code {foo}} 时
     * 运维能立刻定位「模板写了个 payload 里没有的键」，替换成空串则会被当成「文案就是这么短」。</p>
     */
    public Optional<RenderedMessage> render(String templateCode, Map<String, Object> payload) {
        MsgTemplate stored = find(templateCode);
        if (stored != null && stored.getEnabled() != null && stored.getEnabled() == MsgTemplate.DISABLED) {
            stored = null;
        }
        MessageTemplates.DefaultTemplate fallback =
                stored == null ? MessageTemplates.defaultOf(templateCode) : null;

        String title = stored != null ? stored.getTitle() : (fallback == null ? null : fallback.title());
        String content = stored != null ? stored.getContent() : (fallback == null ? null : fallback.content());
        if (title == null || content == null) {
            return Optional.empty();
        }
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        return Optional.of(new RenderedMessage(fill(title, safePayload), fill(content, safePayload)));
    }

    private String fill(String template, Map<String, Object> payload) {
        String result = template;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    /** 渲染结果：标题 + 正文，既用于通道发送，也用于本地联调时打印可读文案。 */
    public record RenderedMessage(String title, String content) {
        public String oneLine() {
            return title + "：" + content;
        }
    }
}
