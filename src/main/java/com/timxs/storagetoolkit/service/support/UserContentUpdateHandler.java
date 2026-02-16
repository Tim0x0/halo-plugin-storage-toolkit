package com.timxs.storagetoolkit.service.support;

import com.timxs.storagetoolkit.model.ReferenceReplacementResult;
import com.timxs.storagetoolkit.model.ReferenceReplacementTask;
import com.timxs.storagetoolkit.model.ReplaceResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import org.springframework.data.domain.Sort;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * User 内容更新处理器
 * 处理用户头像的 URL 替换
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserContentUpdateHandler implements ContentUpdateHandler {

    private final ReactiveExtensionClient client;

    @Override
    public String getSourceType() {
        return "User";
    }

    @Override
    public Mono<ReplaceResult> replaceUrls(String sourceName, ReferenceReplacementTask task, ReferenceReplacementResult result) {
        return client.fetch(User.class, sourceName)
            .flatMap(user -> {
                String avatar = user.getSpec().getAvatar();
                if (!StringUtils.hasText(avatar)) {
                    return Mono.just(ReplaceResult.empty());  // 没有头像
                }

                String newAvatar = avatar;
                ReplaceResult replaceResult = ReplaceResult.builder().build();

                for (Map.Entry<String, String> entry : task.getUrlMapping().entrySet()) {
                    String oldUrl = entry.getKey();
                    String newUrl = entry.getValue();

                    if (UrlReplacer.containsUrl(avatar, oldUrl)) {
                        newAvatar = UrlReplacer.replaceUrl(newAvatar, oldUrl, newUrl);
                        replaceResult.addReplaced(oldUrl);
                    }
                }

                if (!replaceResult.hasReplaced()) {
                    return Mono.just(ReplaceResult.empty());  // 未找到匹配内容
                }

                user.getSpec().setAvatar(newAvatar);

                return client.update(user)
                    .thenReturn(replaceResult)  // 返回替换结果
                    .doOnSuccess(v -> {
                        log.debug("User {} 头像引用替换成功，替换了 {} 个 URL", sourceName, replaceResult.getReplaced().size());
                        result.incrementTypeCount(getSourceType());
                    });
            })
            .retryWhen(RetryUtils.optimisticLockRetry())
            .onErrorResume(e -> {
                log.error("User {} 替换失败（重试耗尽）: {}", sourceName, e.getMessage());
                ReplaceResult errorResult = ReplaceResult.builder().build();
                for (Map.Entry<String, String> entry : task.getUrlMapping().entrySet()) {
                    errorResult.addError(entry.getKey(), e.getMessage());
                }
                return Mono.just(errorResult);
            })
            .defaultIfEmpty(ReplaceResult.empty());  // 用户不存在
    }

    @Override
    public Mono<Set<String>> getAllSourceNames() {
        return client.listAll(User.class, ListOptions.builder().build(), Sort.unsorted())
            .map(user -> user.getMetadata().getName())
            .collect(Collectors.toSet());
    }

}
