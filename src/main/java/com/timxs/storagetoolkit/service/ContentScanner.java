package com.timxs.storagetoolkit.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容扫描器
 * 从 HTML/Markdown/JSON 内容中提取 URL
 */
@Slf4j
@Component
public class ContentScanner {

    /**
     * Markdown 图片语法 - 改进版，提取括号内完整内容（不在空格截断）
     * 匹配 ![alt](url) 或 ![alt](url "title")
     */
    private static final Pattern MD_IMAGE_PATTERN =
        Pattern.compile("!\\[[^\\]]*\\]\\(([^)\"]+)(?:\\s+\"[^\"]*\")?\\)");

    /**
     * Markdown 链接语法 - 改进版，提取括号内完整内容
     * 匹配 [text](url) 或 [text](url "title")
     */
    private static final Pattern MD_LINK_PATTERN =
        Pattern.compile("(?<!!)\\[[^\\]]*\\]\\(([^)\"]+)(?:\\s+\"[^\"]*\")?\\)");

    /**
     * 通用相对路径匹配（适用于 JSON、纯文本等）
     * 匹配 /upload/ 开头的路径，支持包含空格和括号的文件名
     * 要求以文件扩展名结尾（2-5个字母数字字符）
     */
    private static final Pattern UPLOAD_PATH_PATTERN =
        Pattern.compile("(?<![a-zA-Z0-9.\\-])(/upload/[^\"'<>\\n]+?\\.\\w{2,5})(?=[\"'\\s<>\\]\\)\\},]|$)", Pattern.CASE_INSENSITIVE);

    /**
     * HTTP/HTTPS URL 匹配（适用于 JSON、纯文本等）
     * 要求以文件扩展名结尾，支持包含空格的 URL
     */
    private static final Pattern HTTP_URL_PATTERN =
        Pattern.compile("([\"']?)(https?://[^\"'<>\\n]+?\\.\\w{2,5})\\1(?=[\"'\\s<>\\]\\)\\},]|$)", Pattern.CASE_INSENSITIVE);

    /**
     * 提取结果，区分完整 URL 和相对路径
     */
    public record ExtractResult(Set<String> fullUrls, Set<String> relativePaths) {
        public ExtractResult() {
            this(new HashSet<>(), new HashSet<>());
        }
    }

    /**
     * 从 HTML 内容中提取 URL（使用 Jsoup 解析）
     * 提取 img[src], a[href], video[src], audio[src], source[src] 等属性
     */
    public ExtractResult extractUrlsFromHtml(String html) {
        ExtractResult result = new ExtractResult();

        if (!StringUtils.hasText(html)) {
            return result;
        }

        try {
            Document doc = Jsoup.parse(html);

            // 提取图片 src
            extractAttribute(doc.select("img[src]"), "src", result);

            // 提取链接 href
            extractAttribute(doc.select("a[href]"), "href", result);

            // 提取视频 src
            extractAttribute(doc.select("video[src]"), "src", result);

            // 提取音频 src
            extractAttribute(doc.select("audio[src]"), "src", result);

            // 提取 source 标签 src
            extractAttribute(doc.select("source[src]"), "src", result);

            // 提取 iframe src
            extractAttribute(doc.select("iframe[src]"), "src", result);

            // 提取 embed src
            extractAttribute(doc.select("embed[src]"), "src", result);

            // 提取 object data
            extractAttribute(doc.select("object[data]"), "data", result);

            // 提取背景图片 style 属性中的 url()
            doc.select("[style*=url]").forEach(el -> {
                String style = el.attr("style");
                extractUrlFromStyle(style, result);
            });

        } catch (Exception e) {
            log.warn("Jsoup 解析 HTML 失败，回退到正则提取: {}", e.getMessage());
            // 解析失败时回退到正则
            return extractUrlsWithType(html);
        }

        return result;
    }

    /**
     * 从元素属性中提取 URL
     */
    private void extractAttribute(Elements elements, String attr, ExtractResult result) {
        for (Element el : elements) {
            String url = el.attr(attr);
            if (StringUtils.hasText(url)) {
                String decodedUrl = decodeUrl(url.trim());
                if (isValidUrl(decodedUrl)) {
                    classifyUrl(decodedUrl, result);
                }
            }
        }
    }

    /**
     * 从 CSS style 中提取 url()
     */
    private void extractUrlFromStyle(String style, ExtractResult result) {
        if (!StringUtils.hasText(style)) return;

        Pattern urlPattern = Pattern.compile("url\\(['\"]?([^)'\"]+)['\"]?\\)");
        Matcher matcher = urlPattern.matcher(style);
        while (matcher.find()) {
            String url = matcher.group(1);
            if (StringUtils.hasText(url)) {
                String decodedUrl = decodeUrl(url.trim());
                if (isValidUrl(decodedUrl)) {
                    classifyUrl(decodedUrl, result);
                }
            }
        }
    }

    /**
     * 从内容中提取所有 URL，区分完整 URL 和相对路径
     * 用于非 HTML 内容（Markdown、JSON、纯文本）
     */
    public ExtractResult extractUrlsWithType(String content) {
        ExtractResult result = new ExtractResult();

        if (!StringUtils.hasText(content)) {
            return result;
        }

        // Markdown 语法
        extractByPatternWithType(content, MD_IMAGE_PATTERN, result, 1);
        extractByPatternWithType(content, MD_LINK_PATTERN, result, 1);

        // JSON、纯文本中的 URL（要求扩展名结尾）
        extractByPatternWithType(content, HTTP_URL_PATTERN, result, 2);
        extractByPatternWithType(content, UPLOAD_PATH_PATTERN, result, 1);

        return result;
    }

    /**
     * 从内容中提取所有 URL（兼容旧接口，返回合并结果）
     */
    public Set<String> extractUrls(String content) {
        ExtractResult result = extractUrlsWithType(content);
        Set<String> urls = new HashSet<>();
        urls.addAll(result.fullUrls());
        urls.addAll(result.relativePaths());
        return urls;
    }

    private void extractByPatternWithType(String content, Pattern pattern, ExtractResult result, int group) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String url = matcher.group(group);
            if (StringUtils.hasText(url)) {
                String decodedUrl = decodeUrl(url.trim());
                if (isValidUrl(decodedUrl)) {
                    classifyUrl(decodedUrl, result);
                }
            }
        }
    }

    /**
     * 根据 URL 类型分类到对应的集合
     */
    private void classifyUrl(String url, ExtractResult result) {
        if (isFullUrl(url)) {
            result.fullUrls().add(url);
        } else if (url.startsWith("/")) {
            result.relativePaths().add(url);
        }
    }

    /**
     * 判断是否为完整 URL
     */
    public boolean isFullUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /**
     * 从完整 URL 中提取路径部分
     */
    public String extractPath(String fullUrl) {
        if (!isFullUrl(fullUrl)) {
            return fullUrl;
        }
        try {
            return URI.create(fullUrl).getPath();
        } catch (Exception e) {
            // 解析失败，尝试简单截取
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

    private boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        if (url.startsWith("data:")) return false;
        if (url.startsWith("javascript:")) return false;
        if (url.startsWith("mailto:")) return false;
        if (url.startsWith("#")) return false;
        return true;
    }

    private String decodeUrl(String url) {
        try {
            return URLDecoder.decode(url, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
