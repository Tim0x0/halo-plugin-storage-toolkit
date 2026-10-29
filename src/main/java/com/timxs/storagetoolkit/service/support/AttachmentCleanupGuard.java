package com.timxs.storagetoolkit.service.support;

import com.timxs.storagetoolkit.service.SettingsManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.List;

/** Validates the whole cleanup selection before any attachment is deleted. */
@Component
@RequiredArgsConstructor
public class AttachmentCleanupGuard {
    private final ReactiveExtensionClient client;
    private final ReferenceContentScanner referenceContentScanner;
    private final SettingsManager settingsManager;

    public Mono<Void> validateUnreferenced(List<String> attachmentNames) {
        return Mono.zip(referenceContentScanner.scanAll(), settingsManager.getExcludeSettings())
            .switchIfEmpty(Mono.error(new IllegalStateException("缺少引用核验所需的配置或内容")))
            .flatMap(tuple -> Flux.fromIterable(attachmentNames)
                .concatMap(name -> client.fetch(Attachment.class, name)
                    .filter(attachment -> attachment.getMetadata().getDeletionTimestamp() == null)
                    .switchIfEmpty(Mono.error(new IllegalStateException("附件不存在或已删除: " + name)))
                    .doOnNext(attachment -> validate(attachment, tuple.getT1(), tuple.getT2())))
                .then())
            .onErrorMap(error -> new IllegalStateException(
                "无法确认附件未被引用，已保留全部原文件: " + error.getMessage(), error));
    }

    private void validate(Attachment attachment, ReferenceScanContext context,
                          SettingsManager.ExcludeSettings excludes) {
        String name = attachment.getMetadata().getName();
        var spec = attachment.getSpec();
        if (spec == null || (spec.getGroupName() != null && excludes.excludeGroups().contains(spec.getGroupName()))
            || (spec.getPolicyName() != null && excludes.excludePolicies().contains(spec.getPolicyName()))) {
            throw new IllegalStateException("附件已被排除清理: " + name);
        }
        String permalink = attachment.getStatus() != null ? attachment.getStatus().getPermalink() : null;
        if (!StringUtils.hasText(permalink)) {
            throw new IllegalStateException("附件缺少可核验的链接: " + name);
        }
        if (!context.findSources(permalink).isEmpty()) {
            throw new IllegalStateException("附件当前仍被内容引用: " + name);
        }
    }
}
