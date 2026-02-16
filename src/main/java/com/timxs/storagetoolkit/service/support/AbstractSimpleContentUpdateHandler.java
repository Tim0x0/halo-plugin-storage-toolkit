package com.timxs.storagetoolkit.service.support;

import com.timxs.storagetoolkit.model.ReferenceReplacementResult;
import com.timxs.storagetoolkit.model.ReferenceReplacementTask;
import com.timxs.storagetoolkit.model.ReplaceResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 简单内容更新处理器的抽象基类
 * 适用于只有单一 content 字段需要替换的实体类型（如 Comment、Reply）
 *
 * @param <T> 实体类型
 */
@Slf4j
public abstract class AbstractSimpleContentUpdateHandler<T extends AbstractExtension> implements ContentUpdateHandler {

    protected final ReactiveExtensionClient client;

    protected AbstractSimpleContentUpdateHandler(ReactiveExtensionClient client) {
        this.client = client;
    }

    /**
     * 获取实体类型
     */
    protected abstract Class<T> getEntityClass();

    /**
     * 从实体中获取内容
     */
    protected abstract String getContent(T entity);

    /**
     * 设置实体的内容
     */
    protected abstract void setContent(T entity, String content);

    /**
     * 获取实体的名称（用于日志）
     */
    protected abstract String getEntityName(T entity);

    /**
     * 从实体中获取 raw 内容（默认返回 null，子类按需重写）
     */
    protected String getRaw(T entity) {
        return null;
    }

    /**
     * 设置实体的 raw 内容（默认空操作，子类按需重写）
     */
    protected void setRaw(T entity, String raw) {
    }

    @Override
    public Mono<ReplaceResult> replaceUrls(String sourceName, ReferenceReplacementTask task, ReferenceReplacementResult result) {
        return client.fetch(getEntityClass(), sourceName)
            .flatMap(entity -> {
                String content = getContent(entity);
                String raw = getRaw(entity);
                if (!StringUtils.hasText(content) && !StringUtils.hasText(raw)) {
                    return Mono.just(ReplaceResult.empty());  // 没有内容
                }

                String newContent = content;
                String newRaw = raw;
                ReplaceResult replaceResult = ReplaceResult.builder().build();

                for (Map.Entry<String, String> entry : task.getUrlMapping().entrySet()) {
                    String oldUrl = entry.getKey();
                    String newUrl = entry.getValue();

                    boolean replaced = false;
                    if (StringUtils.hasText(content) && UrlReplacer.containsUrl(content, oldUrl)) {
                        newContent = UrlReplacer.replaceUrl(newContent, oldUrl, newUrl);
                        replaced = true;
                    }
                    if (StringUtils.hasText(raw) && UrlReplacer.containsUrl(raw, oldUrl)) {
                        newRaw = UrlReplacer.replaceUrl(newRaw, oldUrl, newUrl);
                        replaced = true;
                    }
                    if (replaced) {
                        replaceResult.addReplaced(oldUrl);
                    }
                }

                if (!replaceResult.hasReplaced()) {
                    return Mono.just(ReplaceResult.empty());  // 未找到匹配内容
                }

                setContent(entity, newContent);
                setRaw(entity, newRaw);

                return client.update(entity)
                    .thenReturn(replaceResult)  // 返回替换结果
                    .doOnSuccess(v -> {
                        log.debug("{} {} 引用替换成功，替换了 {} 个 URL", getSourceType(), sourceName, replaceResult.getReplaced().size());
                        result.incrementTypeCount(getSourceType());
                    });
            })
            .retryWhen(RetryUtils.optimisticLockRetry())
            .onErrorResume(e -> {
                log.error("{} {} 替换失败（重试耗尽）: {}", getSourceType(), sourceName, e.getMessage());
                // 返回包含错误信息的结果
                ReplaceResult errorResult = ReplaceResult.builder().build();
                for (Map.Entry<String, String> entry : task.getUrlMapping().entrySet()) {
                    errorResult.addError(entry.getKey(), e.getMessage());
                }
                return Mono.just(errorResult);
            })
            .defaultIfEmpty(ReplaceResult.empty());  // 实体不存在
    }

    @Override
    public Mono<Set<String>> getAllSourceNames() {
        return client.listAll(getEntityClass(), ListOptions.builder().build(), Sort.unsorted())
            .map(this::getEntityName)
            .collect(Collectors.toSet());
    }

}
