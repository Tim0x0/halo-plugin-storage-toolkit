package com.timxs.storagetoolkit.service.support;

import com.timxs.storagetoolkit.extension.AttachmentReference;
import com.timxs.storagetoolkit.service.ContentScanner;
import lombok.Getter;
import org.springframework.util.StringUtils;
import run.halo.app.infra.ExternalLinkProcessor;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

/**
 * 引用扫描上下文
 * 用于封装扫描过程中的状态（URL 映射）和路径处理逻辑
 */
@Slf4j
public class ReferenceScanContext {

    private final ExternalLinkProcessor externalLinkProcessor;
    private final ContentScanner contentScanner;

    /**
     * 完整 URL -> 引用源列表
     * 用于：
     * 1. 匹配附件 Permalink（必须完全一致）
     * 2. 断链检测（检测所有完整 URL）
     */
    @Getter
    private final Map<String, Set<AttachmentReference.ReferenceSource>> fullUrlToSources = new ConcurrentHashMap<>();

    /**
     * 相对路径 -> 引用源列表
     * 用于：
     * 1. 匹配附件 Permalink（当附件 Permalink 本身是相对路径时）
     * 2. 备用匹配（作为 normalized path 匹配）
     */
    @Getter
    private final Map<String, Set<AttachmentReference.ReferenceSource>> relativePathToSources = new ConcurrentHashMap<>();

    /**
     * 完整 URL -> 原始 URL 映射
     * 用于断链列表显示原始 URL（未拼接的）
     * 如果是完整 URL，映射到自己；如果是相对路径拼接的，映射到原始相对路径
     */
    @Getter
    private final Map<String, String> fullUrlToOriginalUrl = new ConcurrentHashMap<>();

    public ReferenceScanContext(ExternalLinkProcessor externalLinkProcessor, ContentScanner contentScanner) {
        this.externalLinkProcessor = externalLinkProcessor;
        this.contentScanner = contentScanner;
    }

    /**
     * 添加提取到的 URL 结果
     */
    public void addExtractResult(ContentScanner.ExtractResult result, AttachmentReference.ReferenceSource source) {
        result.fullUrls().forEach(url -> addFullUrl(url, source));
        result.relativePaths().forEach(path -> addRelativePath(path, source));
    }

    /**
     * 添加完整 URL
     */
    public void addFullUrl(String url, AttachmentReference.ReferenceSource source) {
        if (!StringUtils.hasText(url) || url.startsWith("data:")) {
            return;
        }
        fullUrlToSources.computeIfAbsent(url, k -> ConcurrentHashMap.newKeySet()).add(source);
        // 完整 URL 的原始 URL 就是自己（只有不存在时才放入，优先保留相对路径）
        fullUrlToOriginalUrl.putIfAbsent(url, url);
    }

    /**
     * 添加相对路径
     * 逻辑：
     * 1. 规范化并存入 relativePathToSources
     * 2. 拼接域名并存入 fullUrlToSources (为了断链检测)
     * 3. 保存完整 URL -> 原始相对路径的映射（用于显示）
     */
    public void addRelativePath(String path, AttachmentReference.ReferenceSource source) {
        if (!StringUtils.hasText(path) || path.startsWith("data:")) {
            return;
        }
        // 跳过协议相对 URL（如 //cdn.example.com/font.css）
        if (path.startsWith("//")) {
            return;
        }
        String normalizedPath = normalizePath(path);
        if (StringUtils.hasText(normalizedPath)) {
            // 1. 存入相对路径 Map
            relativePathToSources.computeIfAbsent(normalizedPath, k -> ConcurrentHashMap.newKeySet()).add(source);

            // 2. 拼接为完整 URL 并存入完整 URL Map
            String fullUrl = externalLinkProcessor.processLink(normalizedPath);
            if (StringUtils.hasText(fullUrl) && contentScanner.isFullUrl(fullUrl)) {
                fullUrlToSources.computeIfAbsent(fullUrl, k -> ConcurrentHashMap.newKeySet()).add(source);
                // 3. 保存原始 URL 映射（使用输入的原始 path，而不是 normalized）
                fullUrlToOriginalUrl.put(fullUrl, path);
                log.debug("相对路径映射: {} -> {} (原始: {})", fullUrl, normalizedPath, path);
            }
        }
    }

    /**
     * 智能添加 URL（自动判断是完整 URL 还是相对路径）
     */
    public void addUrl(String url, AttachmentReference.ReferenceSource source) {
        if (!StringUtils.hasText(url) || url.startsWith("data:")) {
            return;
        }
        if (contentScanner.isFullUrl(url)) {
            addFullUrl(url, source);
        } else {
            addRelativePath(url, source);
        }
    }

    /**
     * 获取原始 URL（用于显示）
     * @param fullUrl 完整 URL
     * @return 原始 URL（可能是相对路径或完整 URL）
     */
    public String getOriginalUrl(String fullUrl) {
        String original = fullUrlToOriginalUrl.get(fullUrl);
        return original != null ? original : fullUrl;
    }

    /**
     * 规范化路径
     */
    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        if (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.startsWith("../")) {
            return null;
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path;
    }
}
