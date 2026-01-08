package com.timxs.storagetoolkit.endpoint;

import com.timxs.storagetoolkit.extension.ReferenceScanStatus;
import com.timxs.storagetoolkit.service.ReferenceService;
import com.timxs.storagetoolkit.service.ReferenceService.AttachmentReferenceVo;
import com.timxs.storagetoolkit.service.ReferenceService.ReferenceQuery;
import com.timxs.storagetoolkit.service.ReferenceService.SubjectInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Group;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ApiVersion;

/**
 * 引用统计 REST API 端点
 * 提供附件引用扫描和查询功能
 */
@ApiVersion("console.api.storage-toolkit.timxs.com/v1alpha1")
@RestController
@RequestMapping("/references")
@RequiredArgsConstructor
public class ReferenceEndpoint {

    private final ReferenceService referenceService;
    private final ReactiveExtensionClient client;

    /**
     * 触发全量扫描
     * 扫描所有内容建立附件引用关系
     */
    @PostMapping("/scan")
    public Mono<ScanResponse> startScan() {
        return referenceService.startScan()
            .map(status -> new ScanResponse(
                status.getStatus().getPhase(),
                "扫描已开始"
            ))
            .onErrorResume(IllegalStateException.class, e ->
                Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()))
            );
    }

    /**
     * 获取扫描状态和统计概览
     */
    @GetMapping("/stats")
    public Mono<StatsResponse> getStats() {
        return referenceService.getScanStatus()
            .map(status -> {
                var s = status.getStatus();
                return new StatsResponse(
                    s != null ? s.getPhase() : null,
                    s != null ? s.getLastScanTime() : null,
                    s != null ? s.getTotalAttachments() : 0,
                    s != null ? s.getReferencedCount() : 0,
                    s != null ? s.getUnreferencedCount() : 0,
                    s != null ? s.getUnreferencedSize() : 0,
                    s != null ? s.getErrorMessage() : null
                );
            });
    }

    /**
     * 获取附件引用列表
     */
    @GetMapping
    public Mono<ListResult<AttachmentReferenceVo>> listReferences(
            @RequestParam(defaultValue = "all") String filter,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return referenceService.listReferences(
            ReferenceQuery.of(filter, keyword, page, size, sort)
        );
    }

    /**
     * 获取单个附件的引用详情
     */
    @GetMapping("/{attachmentName}")
    public Mono<AttachmentReferenceVo> getReference(@PathVariable String attachmentName) {
        return referenceService.getReference(attachmentName)
            .switchIfEmpty(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在")
            ));
    }

    /**
     * 获取存储策略的 displayName
     */
    @GetMapping("/policy/{policyName}")
    public Mono<PolicyInfo> getPolicyInfo(@PathVariable String policyName) {
        return client.fetch(Policy.class, policyName)
            .map(policy -> new PolicyInfo(
                policy.getMetadata().getName(),
                policy.getSpec().getDisplayName()
            ))
            .defaultIfEmpty(new PolicyInfo(policyName, policyName));
    }

    /**
     * 获取分组的 displayName
     */
    @GetMapping("/group/{groupName}")
    public Mono<GroupInfo> getGroupInfo(@PathVariable String groupName) {
        return client.fetch(Group.class, groupName)
            .map(group -> new GroupInfo(
                group.getMetadata().getName(),
                group.getSpec().getDisplayName()
            ))
            .defaultIfEmpty(new GroupInfo(groupName, groupName));
    }

    /**
     * 获取评论/回复关联的文章/页面信息
     * kind: Post, SinglePage, Comment, Moment, Doc
     * 如果是 Comment，会追溯到其关联的文章/页面
     * 如果是 Doc，会查询 DocTree 获取标题和链接
     */
    @GetMapping("/subject/{kind}/{name}")
    public Mono<SubjectInfo> getSubjectInfo(@PathVariable String kind, @PathVariable String name) {
        return switch (kind) {
            case "Post" -> client.fetch(Post.class, name)
                .map(post -> new SubjectInfo(
                    post.getSpec().getTitle(),
                    "/archives/" + post.getSpec().getSlug()
                ))
                .defaultIfEmpty(new SubjectInfo(name, null));
            case "SinglePage" -> client.fetch(SinglePage.class, name)
                .map(page -> new SubjectInfo(
                    page.getSpec().getTitle(),
                    "/" + page.getSpec().getSlug()
                ))
                .defaultIfEmpty(new SubjectInfo(name, null));
            case "Moment" -> Mono.just(new SubjectInfo("瞬间", "/moments"));
            case "Comment" -> client.fetch(Comment.class, name)
                .flatMap(comment -> {
                    var subjectRef = comment.getSpec().getSubjectRef();
                    if (subjectRef == null) {
                        return Mono.just(new SubjectInfo("评论", null));
                    }
                    String subKind = subjectRef.getKind();
                    String subName = subjectRef.getName();
                    if ("Post".equals(subKind)) {
                        return client.fetch(Post.class, subName)
                            .map(post -> new SubjectInfo(
                                post.getSpec().getTitle(),
                                "/archives/" + post.getSpec().getSlug()
                            ))
                            .defaultIfEmpty(new SubjectInfo(subName, null));
                    } else if ("SinglePage".equals(subKind)) {
                        return client.fetch(SinglePage.class, subName)
                            .map(page -> new SubjectInfo(
                                page.getSpec().getTitle(),
                                "/" + page.getSpec().getSlug()
                            ))
                            .defaultIfEmpty(new SubjectInfo(subName, null));
                    } else if ("Moment".equals(subKind)) {
                        return Mono.just(new SubjectInfo("瞬间", "/moments"));
                    } else if ("DocTree".equals(subKind)) {
                        return referenceService.resolveDocTreeInfo(subName)
                            .defaultIfEmpty(new SubjectInfo(subName, null));
                    }
                    return Mono.just(new SubjectInfo("评论", null));
                })
                .defaultIfEmpty(new SubjectInfo(name, null));
            case "Doc" -> referenceService.resolveDocInfo(name)
                .defaultIfEmpty(new SubjectInfo(name, null));
            case "DocTree" -> referenceService.resolveDocTreeInfo(name)
                .defaultIfEmpty(new SubjectInfo(name, null));
            default -> Mono.just(new SubjectInfo(name, null));
        };
    }

    /**
     * 更新引用源信息（详情弹窗查询后缓存）
     */
    @PutMapping("/{attachmentName}/source/{sourceName}")
    public Mono<AttachmentReferenceVo> updateReferenceSource(
            @PathVariable String attachmentName,
            @PathVariable String sourceName,
            @RequestParam(required = false) String sourceTitle,
            @RequestParam(required = false) String sourceUrl) {
        return referenceService.updateReferenceSource(attachmentName, sourceName, sourceTitle, sourceUrl)
            .switchIfEmpty(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "引用源不存在")
            ));
    }

    /**
     * 清空所有引用记录和扫描状态
     */
    @DeleteMapping("/clear")
    public Mono<ClearResponse> clearAll() {
        return referenceService.clearAll()
            .thenReturn(new ClearResponse("引用记录已清空"));
    }

    /**
     * 获取 Setting group 的显示标签
     * 用于 ConfigMap 类型引用的 tag 显示
     */
    @GetMapping("/settings/{settingName}/groups/{groupKey}/label")
    public Mono<SettingGroupLabelResponse> getSettingGroupLabel(
            @PathVariable String settingName,
            @PathVariable String groupKey) {
        return referenceService.getSettingGroupLabel(settingName, groupKey)
            .map(label -> new SettingGroupLabelResponse(label));
    }

    /**
     * 扫描响应
     */
    public record ScanResponse(
        String phase,
        String message
    ) {}

    /**
     * 统计响应
     */
    public record StatsResponse(
        String phase,
        java.time.Instant lastScanTime,
        int totalAttachments,
        int referencedCount,
        int unreferencedCount,
        long unreferencedSize,
        String errorMessage
    ) {}

    public record PolicyInfo(String name, String displayName) {}

    public record GroupInfo(String name, String displayName) {}

    public record ClearResponse(String message) {}

    public record SettingGroupLabelResponse(String label) {}
}
