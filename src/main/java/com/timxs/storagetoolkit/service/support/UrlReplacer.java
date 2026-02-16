package com.timxs.storagetoolkit.service.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import run.halo.app.infra.ExternalLinkProcessor;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URL 替换工具类
 * 处理 HTML/Markdown 内容中的 URL 替换
 */
@Slf4j
public final class UrlReplacer {

    private UrlReplacer() {
        // 工具类，禁止实例化
    }

    /**
     * 在内容中替换 URL
     * - 完整 URL：直接精确替换
     * - 相对路径：使用正则确保不匹配完整 URL 中的相同路径
     *
     * @param content 原始内容
     * @param oldUrl 旧 URL（内容中实际存储的形式）
     * @param newUrl 新 URL
     * @return 替换后的内容
     */
    public static String replaceUrl(String content, String oldUrl, String newUrl) {
        if (!StringUtils.hasText(content) || !StringUtils.hasText(oldUrl) || newUrl == null) {
            return content;
        }

        // 判断是相对路径还是完整 URL
        // 相对路径以 / 开头但不是 // （// 是协议相对 URL）
        boolean isRelativePath = oldUrl.startsWith("/") && !oldUrl.startsWith("//");

        if (isRelativePath) {
            // 相对路径：使用正则确保不匹配完整 URL 中的相同路径
            // (?<![a-zA-Z0-9/]) 确保前面不是字母、数字或斜杠
            // 这样 /upload/abc.png 不会匹配 https://example.com/upload/abc.png 中的路径部分
            Pattern pattern = Pattern.compile(
                "(?<![a-zA-Z0-9/])" + Pattern.quote(oldUrl)
            );
            return pattern.matcher(content).replaceAll(Matcher.quoteReplacement(newUrl));
        } else {
            // 完整 URL：直接精确替换
            return content.replace(oldUrl, newUrl);
        }
    }

    /**
     * 检查内容中是否包含指定的 URL
     * - 完整 URL：直接检查
     * - 相对路径：使用正则确保是独立的相对路径
     *
     * @param content 内容
     * @param url URL
     * @return 是否包含
     */
    public static boolean containsUrl(String content, String url) {
        if (!StringUtils.hasText(content) || !StringUtils.hasText(url)) {
            return false;
        }

        // 判断是相对路径还是完整 URL
        boolean isRelativePath = url.startsWith("/") && !url.startsWith("//");

        if (isRelativePath) {
            // 相对路径：使用正则确保不匹配完整 URL 中的相同路径
            Pattern pattern = Pattern.compile(
                "(?<![a-zA-Z0-9/])" + Pattern.quote(url)
            );
            return pattern.matcher(content).find();
        } else {
            // 完整 URL：直接检查
            return content.contains(url);
        }
    }

    /**
     * 构建双形式 URL 映射
     * 同一个资源可能以相对路径和完整 URL 两种形式出现在内容中，
     * 此方法根据传入的 URL 自动补充另一种形式，确保两种引用都能被替换
     *
     * @param oldUrl 旧 URL（permalink 或完整 URL）
     * @param newUrl 新 URL
     * @param linkProcessor 用于将相对路径转换为完整 URL
     * @return 包含两种形式的 URL 映射
     */
    public static Map<String, String> buildDualFormMapping(
            String oldUrl, String newUrl, ExternalLinkProcessor linkProcessor) {
        Map<String, String> mapping = new HashMap<>();
        mapping.put(oldUrl, newUrl);

        if (isFullUrl(oldUrl)) {
            // 完整 URL → 补充相对路径形式
            String oldPath = extractPath(oldUrl);
            if (StringUtils.hasText(oldPath) && !oldPath.equals(oldUrl)) {
                String newPath = isFullUrl(newUrl) ? extractPath(newUrl) : newUrl;
                if (StringUtils.hasText(newPath)) {
                    mapping.put(oldPath, newPath);
                }
            }
        } else {
            // 相对路径 → 补充完整 URL 形式
            String oldFull = linkProcessor.processLink(oldUrl);
            if (StringUtils.hasText(oldFull) && isFullUrl(oldFull) && !oldFull.equals(oldUrl)) {
                String newFull = isFullUrl(newUrl) ? newUrl : linkProcessor.processLink(newUrl);
                if (StringUtils.hasText(newFull)) {
                    mapping.put(oldFull, newFull);
                }
            }
        }

        return mapping;
    }

    private static boolean isFullUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /**
     * 从完整 URL 中提取路径部分
     */
    private static String extractPath(String fullUrl) {
        if (!isFullUrl(fullUrl)) {
            return fullUrl;
        }
        try {
            return URI.create(fullUrl).getPath();
        } catch (Exception e) {
            int idx = fullUrl.indexOf("://");
            if (idx > 0) {
                int pathStart = fullUrl.indexOf('/', idx + 3);
                if (pathStart > 0) {
                    return fullUrl.substring(pathStart);
                }
            }
            return fullUrl;
        }
    }
}
