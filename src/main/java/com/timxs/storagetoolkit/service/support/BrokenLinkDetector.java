package com.timxs.storagetoolkit.service.support;

import com.timxs.storagetoolkit.extension.AttachmentReference;
import com.timxs.storagetoolkit.extension.BrokenLink;
import com.timxs.storagetoolkit.service.ContentScanner;
import com.timxs.storagetoolkit.service.LinkChecker;
import com.timxs.storagetoolkit.service.SettingsManager;
import com.timxs.storagetoolkit.service.WhitelistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 断链检测执行器
 * 负责执行 HTTP 检测并记录断链结果
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrokenLinkDetector {

    private final ReactiveExtensionClient client;
    private final LinkChecker linkChecker;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final ContentScanner contentScanner;
    private final SettingsManager settingsManager;

    /**
     * 执行断链检测
     */
    public Mono<Integer> detect(
            ReferenceScanContext context,
            Set<String> matchedAttachmentUrls,
            List<WhitelistService.WhitelistItem> whitelist,
            SettingsManager.BrokenLinkSettings settings,
            long scanTimestamp) {

        Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources = context.getFullUrlToSources();
        if (fullUrlToSources.isEmpty()) {
            log.debug("没有需要检测的链接");
            return Mono.just(0);
        }

        // 读取代理配置后执行检测
        return settingsManager.getProxySettings()
            .flatMap(proxySettings -> {
                if (proxySettings.isEffective()) {
                    log.info("断链检测使用代理: {}:{}", proxySettings.host(), proxySettings.port());
                }
                return doDetect(context, matchedAttachmentUrls, whitelist, settings, scanTimestamp,
                    proxySettings);
            });
    }

    /**
     * 实际执行断链检测
     */
    private Mono<Integer> doDetect(
            ReferenceScanContext context,
            Set<String> matchedAttachmentUrls,
            List<WhitelistService.WhitelistItem> whitelist,
            SettingsManager.BrokenLinkSettings settings,
            long scanTimestamp,
            SettingsManager.ProxySettings proxySettings) {

        Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources = context.getFullUrlToSources();

        Instant now = Instant.now();
        AtomicInteger brokenLinkCount = new AtomicInteger(0);

        boolean checkExternalLinks = settings.checkExternalLinks();

        // 预处理配置的附件前缀
        List<String> fullAttachmentPrefixes = prepareAttachmentPrefixes(settings.attachmentUrlPrefixes());

        // 获取站点基础 URL（用于判断本站链接）
        String siteBaseUrl = getSiteBaseUrl();

        log.info("开始断链检测，共 {} 个链接，站点基础 URL: {}", fullUrlToSources.size(), siteBaseUrl);

        // 过滤需要检测的链接
        List<Map.Entry<String, Set<AttachmentReference.ReferenceSource>>> targetLinks =
            fullUrlToSources.entrySet().stream()
                .filter(entry -> {
                    String url = entry.getKey();
                    String originalUrl = context.getOriginalUrl(url);
                    return !isInWhitelist(url, originalUrl, whitelist);
                })
                .filter(entry ->
                    checkExternalLinks || // 开关开启，检测所有
                    isLocalUrl(entry.getKey(), siteBaseUrl) || // 本站链接
                    isAttachmentUrl(entry.getKey(), fullAttachmentPrefixes) // 匹配附件前缀（含外部存储）
                )
                .toList();

        if (targetLinks.isEmpty()) {
            log.debug("过滤后没有需要检测的链接");
            return Mono.just(0);
        }

        log.debug("过滤后需要检测 {} 个链接", targetLinks.size());

        // 并发执行检测
        return Flux.fromIterable(targetLinks)
            .flatMap(entry -> detectSingleLink(
                context,
                entry.getKey(),
                entry.getValue(),
                matchedAttachmentUrls,
                fullAttachmentPrefixes,
                settings,
                proxySettings,
                now,
                scanTimestamp,
                brokenLinkCount
            ), settings.checkConcurrency())
            .then(Mono.fromSupplier(brokenLinkCount::get));
    }

    /**
     * 检测单个链接
     */
    private Mono<Void> detectSingleLink(
            ReferenceScanContext context,
            String url,
            Set<AttachmentReference.ReferenceSource> sources,
            Set<String> matchedAttachmentUrls,
            List<String> fullAttachmentPrefixes,
            SettingsManager.BrokenLinkSettings settings,
            SettingsManager.ProxySettings proxySettings,
            Instant now,
            long scanTimestamp,
            AtomicInteger brokenLinkCount) {

        return linkChecker.check(url, SettingsManager.DEFAULT_USER_AGENT, settings.checkTimeout(), proxySettings)
            .flatMap(result -> {
                if (result.isValid()) {
                    // HTTP 检测通过
                    // 额外检查：如果是附件链接，必须存在于附件库中
                    if (isAttachmentUrl(url, fullAttachmentPrefixes)) {
                        if (!matchedAttachmentUrls.contains(url)) {
                            // 附件链接 HTTP 通过但附件库不存在 → ATTACHMENT_NOT_FOUND
                            log.debug("附件链接 HTTP 通过但附件库不存在: {}", url);
                            brokenLinkCount.incrementAndGet();
                            return createBrokenLinkRecord(context, url, sources, now, scanTimestamp,
                                "ATTACHMENT_NOT_FOUND");
                        }
                    }
                    return Mono.empty();
                } else {
                    // HTTP 检测失败
                    log.debug("HTTP 检测失败: {} - {}", url, result.reason());
                    brokenLinkCount.incrementAndGet();
                    return createBrokenLinkRecord(context, url, sources, now, scanTimestamp,
                        result.reason());
                }
            })
            .onErrorResume(e -> {
                log.debug("链接检测异常: {} - {}", url, e.getMessage());
                brokenLinkCount.incrementAndGet();
                return createBrokenLinkRecord(context, url, sources, now, scanTimestamp,
                    "CONNECTION_FAILED");
            });
    }

    private List<String> prepareAttachmentPrefixes(List<String> prefixes) {
        return prefixes.stream()
            .filter(StringUtils::hasText)
            .map(prefix -> {
                if (contentScanner.isFullUrl(prefix)) {
                    return prefix;
                }
                return externalLinkProcessor.processLink(prefix);
            })
            .filter(StringUtils::hasText)
            .toList();
    }

    private boolean isInWhitelist(String url, String originalUrl, List<WhitelistService.WhitelistItem> whitelist) {
        if (!StringUtils.hasText(url) || whitelist == null || whitelist.isEmpty()) {
            return false;
        }
        for (WhitelistService.WhitelistItem item : whitelist) {
            String pattern = item.url();
            String matchMode = item.matchMode();
            if ("exact".equals(matchMode)) {
                if (url.equals(pattern)) return true;
                if (originalUrl != null && originalUrl.equals(pattern)) return true;
            } else {
                if (url.startsWith(pattern)) return true;
                if (originalUrl != null && originalUrl.startsWith(pattern)) return true;
            }
        }
        return false;
    }

    private boolean isAttachmentUrl(String url, List<String> fullAttachmentPrefixes) {
        if (!StringUtils.hasText(url) || fullAttachmentPrefixes.isEmpty()) {
            return false;
        }
        for (String prefix : fullAttachmentPrefixes) {
            if (url.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * 获取站点基础 URL
     * 通过 externalLinkProcessor 将 "/" 转为完整 URL
     */
    private String getSiteBaseUrl() {
        String baseUrl = externalLinkProcessor.processLink("/");
        // 移除末尾的 "/"
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    /**
     * 判断是否为本站链接
     */
    private boolean isLocalUrl(String url, String siteBaseUrl) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        // 相对路径一定是本站
        if (url.startsWith("/") && !url.startsWith("//")) {
            return true;
        }
        // 完整 URL 检查是否以站点基础 URL 开头
        if (StringUtils.hasText(siteBaseUrl) && url.startsWith(siteBaseUrl)) {
            return true;
        }
        return false;
    }

    private Mono<Void> createBrokenLinkRecord(
            ReferenceScanContext context,
            String url,
            Set<AttachmentReference.ReferenceSource> sources,
            Instant discoveredAt,
            long scanTimestamp,
            String reason) {

        String linkName = "broken-link-" + scanTimestamp + "-" + System.nanoTime();

        BrokenLink brokenLink = new BrokenLink();
        brokenLink.setMetadata(new Metadata());
        brokenLink.getMetadata().setName(linkName);

        BrokenLink.BrokenLinkSpec spec = new BrokenLink.BrokenLinkSpec();
        spec.setUrl(url);
        brokenLink.setSpec(spec);

        BrokenLink.BrokenLinkStatus status = new BrokenLink.BrokenLinkStatus();
        status.setSourceCount(sources.size());
        status.setDiscoveredAt(discoveredAt);
        status.setReason(reason);
        // 保存原始 URL（用于显示）
        String originalUrl = context.getOriginalUrl(url);
        status.setOriginalUrl(originalUrl);
        log.debug("创建断链记录: url={}, originalUrl={}", url, originalUrl);

        List<BrokenLink.BrokenLinkSource> brokenLinkSources = sources.stream()
            .map(source -> {
                BrokenLink.BrokenLinkSource blSource = new BrokenLink.BrokenLinkSource();
                blSource.setSourceType(source.getSourceType());
                blSource.setSourceName(source.getSourceName());
                blSource.setSourceTitle(source.getSourceTitle());
                blSource.setSourceUrl(source.getSourceUrl());
                blSource.setDeleted(source.getDeleted());
                blSource.setReferenceType(source.getReferenceType());
                blSource.setSettingName(source.getSettingName());
                return blSource;
            })
            .toList();

        status.setSources(brokenLinkSources);
        brokenLink.setStatus(status);

        return client.create(brokenLink).then();
    }
}
