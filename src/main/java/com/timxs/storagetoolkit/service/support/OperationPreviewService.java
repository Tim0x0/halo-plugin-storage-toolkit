package com.timxs.storagetoolkit.service.support;

import com.timxs.storagetoolkit.config.ProcessingConfig;
import com.timxs.storagetoolkit.extension.AttachmentReference.ReferenceSource;
import com.timxs.storagetoolkit.extension.DuplicateGroup;
import com.timxs.storagetoolkit.service.ImageProcessor;
import com.timxs.storagetoolkit.service.SettingsManager;
import com.timxs.storagetoolkit.service.SettingsManager.ExcludeSettings;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Fresh, read-only impact analysis. Execution continues to perform its own safety checks. */
@Service
@RequiredArgsConstructor
public class OperationPreviewService {
    private final ReactiveExtensionClient client;
    private final ReferenceContentScanner scanner;
    private final SettingsManager settings;
    private final ImageProcessor processor;

    public Mono<Preview> preview(Request request) {
        if (request.operation() == null
                || request.attachmentNames() == null
                || request.attachmentNames().isEmpty()
                || request.attachmentNames().size() > 2000
                || request.attachmentNames().stream().anyMatch(n -> n == null || n.isBlank())) {
            return Mono.error(new IllegalArgumentException("请选择 1 至 2000 个有效附件"));
        }
        return loadContext(request)
                .flatMap(
                        context ->
                                Flux.fromIterable(
                                                request.attachmentNames().stream()
                                                        .distinct()
                                                        .toList())
                                        .concatMap(name -> inspect(name, request, context))
                                        .collectList()
                                        .map(items -> summarize(request, items)));
    }

    private Mono<PreviewContext> loadContext(Request request) {
        return Mono.zip(
                        scanner.scanAll(),
                        settings.getConfig(),
                        settings.getKeepOriginalFile(),
                        settings.getRemoteStorageForBatchProcessing(),
                        settings.getExcludeSettings(),
                        client.listAll(
                                        DuplicateGroup.class,
                                        ListOptions.builder().build(),
                                        Sort.unsorted())
                                .collectList())
                .flatMap(tuple -> {
                    Map<String, String> kept = duplicateTargets(request, tuple.getT6());
                    return loadKeptTargets(kept).map(targets -> new PreviewContext(
                        tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4(), tuple.getT5(), kept, targets));
                });
    }

    private Mono<Map<String, Attachment>> loadKeptTargets(Map<String, String> kept) {
        return Flux.fromIterable(new HashSet<>(kept.values()))
            .concatMap(name -> client.fetch(Attachment.class, name)
                .filter(attachment -> attachment.getMetadata().getDeletionTimestamp() == null && attachment.getSpec() != null
                    && attachment.getStatus() != null && attachment.getStatus().getPermalink() != null
                    && !attachment.getStatus().getPermalink().isBlank()))
            .collectMap(attachment -> attachment.getMetadata().getName());
    }

    private Mono<Item> inspect(String name, Request request, PreviewContext context) {
        return client.fetch(Attachment.class, name)
                .filter(
                        attachment ->
                                attachment.getMetadata().getDeletionTimestamp() == null
                                        && attachment.getSpec() != null)
                .flatMap(
                        attachment ->
                                isRemote(attachment.getSpec().getPolicyName())
                                        .map(
                                                remote ->
                                                        describe(
                                                                attachment,
                                                                request,
                                                                context,
                                                                remote)))
                .defaultIfEmpty(missing(name));
    }

    private Item describe(
            Attachment attachment, Request request, PreviewContext context, boolean remote) {
        var spec = attachment.getSpec();
        String name = attachment.getMetadata().getName();
        String url = attachment.getStatus() == null ? null : attachment.getStatus().getPermalink();
        List<ReferenceSource> references =
                context.references().findSources(url).stream()
                        .sorted(
                                Comparator.comparing(
                                        source ->
                                                source.getSourceType()
                                                        + source.getSourceName()
                                                        + source.getReferenceType()))
                        .toList();
        String reason = skipReason(attachment, request, context, remote, references);
        boolean eligible = reason == null;
        String action = "跳过";
        if (eligible) {
            action =
                    request.operation() == Operation.BATCH
                            ? context.keepOriginal() ? "处理并保留原文件" : "处理后删除原文件"
                            : "删除";
        }
        String note = reason;
        if (note == null) {
            if (request.replaceReferences() && request.operation() != Operation.UNREFERENCED)
                note = "将更新所列内容中的链接，执行时再次核验";
            else note = references.isEmpty() ? "未发现当前内容引用" : "现有引用不会更新，请确认链接影响";
        }
        List<ReferenceSource> modified =
                request.replaceReferences() && eligible ? references : List.of();
        String keptName = context.kept().get(name);
        Attachment kept = context.targets().get(keptName);
        return new Item(
                name,
                spec.getDisplayName(),
                spec.getSize() == null ? 0 : spec.getSize(),
                eligible,
                action,
                note,
                modified,
                references,
                keptName, kept == null ? null : kept.getSpec().getDisplayName());
    }

    private String skipReason(
            Attachment attachment,
            Request request,
            PreviewContext context,
            boolean remote,
            List<ReferenceSource> references) {
        var spec = attachment.getSpec();
        String url = attachment.getStatus() == null ? null : attachment.getStatus().getPermalink();
        if (url == null || url.isBlank()) return "附件链接尚未就绪";
        if (request.operation() == Operation.BATCH) {
            if (!processor.hasProcessingEnabled(context.config())) return "没有启用处理功能";
            if (remote && !context.remoteEnabled()) return "远程存储未启用";
            if (!processor.isAllowedFormat(spec.getMediaType(), context.config()))
                return "文件格式不在允许列表中";
            return processor.getSkipReason(
                    spec.getMediaType(),
                    spec.getSize() == null ? 0 : spec.getSize(),
                    context.config());
        }
        if (request.operation() == Operation.DUPLICATE
            && !context.targets().containsKey(context.kept().get(attachment.getMetadata().getName()))) {
            return "保留附件不存在、已删除或链接尚未就绪";
        }
        if (request.operation() == Operation.UNREFERENCED) {
            if (!references.isEmpty()) return "当前仍有引用，不能作为未引用文件删除";
            if ((spec.getGroupName() != null
                            && context.excludes().excludeGroups().contains(spec.getGroupName()))
                    || (spec.getPolicyName() != null
                            && context.excludes()
                                    .excludePolicies()
                                    .contains(spec.getPolicyName()))) {
                return "附件已被排除清理";
            }
        }
        return null;
    }

    private Preview summarize(Request request, List<Item> items) {
        long eligible = items.stream().filter(Item::eligible).count();
        long freed =
                request.operation() == Operation.BATCH
                        ? 0
                        : items.stream().filter(Item::eligible).mapToLong(Item::size).sum();
        return new Preview(Instant.now(), items, eligible, items.size() - eligible, freed);
    }

    private Map<String, String> duplicateTargets(Request request, List<DuplicateGroup> groups) {
        Map<String, String> targets = new HashMap<>();
        if (request.operation() != Operation.DUPLICATE) return targets;
        if (request.groups() == null || request.groups().isEmpty())
            throw new IllegalArgumentException("缺少重复分组");
        for (var selection : request.groups().entrySet()) {
            var group =
                    groups.stream()
                            .filter(
                                    g ->
                                            g.getSpec() != null
                                                    && selection
                                                            .getKey()
                                                            .equals(g.getSpec().getMd5Hash())
                                                    && g.getMetadata().getDeletionTimestamp()
                                                            == null
                                                    && g.getStatus() != null
                                                    && !Boolean.TRUE.equals(
                                                            g.getStatus().getPendingDelete()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("重复组已失效"));
            var members = group.getStatus().getAttachmentNames();
            if (members == null
                    || selection.getValue() == null
                    || !members.containsAll(selection.getValue()))
                throw new IllegalArgumentException("附件不属于当前重复组");
            String keep =
                    members.stream()
                            .filter(n -> !selection.getValue().contains(n))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("每个重复组至少需要保留一个文件"));
            selection.getValue().forEach(name -> targets.put(name, keep));
        }
        if (!targets.keySet().equals(new HashSet<>(request.attachmentNames())))
            throw new IllegalArgumentException("附件选择与重复分组不一致");
        return targets;
    }

    private Mono<Boolean> isRemote(String policyName) {
        if (policyName == null) return Mono.just(false);
        return client.fetch(Policy.class, policyName)
                .map(policy -> !"local".equals(policy.getSpec().getTemplateName()))
                .defaultIfEmpty(false);
    }

    private Item missing(String name) {
        return new Item(name, name, 0, false, "跳过", "附件不存在或已删除", List.of(), List.of(), null, null);
    }

    private record PreviewContext(
            ReferenceScanContext references,
            ProcessingConfig config,
            boolean keepOriginal,
            boolean remoteEnabled,
            ExcludeSettings excludes,
            Map<String, String> kept, Map<String, Attachment> targets) {}

    public enum Operation {
        BATCH,
        UNREFERENCED,
        DUPLICATE
    }

    public record Request(
            Operation operation,
            List<String> attachmentNames,
            boolean replaceReferences,
            Map<String, List<String>> groups) {}

    public record Item(
            String attachmentName,
            String displayName,
            long size,
            boolean eligible,
            String action,
            String note,
            List<ReferenceSource> modifiedSources,
            List<ReferenceSource> references,
            String keptAttachmentName, String keptDisplayName) {}

    public record Preview(
            Instant checkedAt,
            List<Item> items,
            long eligibleCount,
            long skippedCount,
            long estimatedFreedBytes) {}
}
