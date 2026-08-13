package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.service.LinkChecker;
import com.timxs.storagetoolkit.service.SettingsManager;
import com.timxs.storagetoolkit.service.support.ProxiedWebClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * 链接检查服务实现。
 * <p>
 * WebClient 由 {@link ProxiedWebClientFactory} 统一提供（含代理、连接池与超时）。
 * 检测不跟随重定向：3xx 本身即视为链接有效。
 */
@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class LinkCheckerImpl implements LinkChecker {

    private final ProxiedWebClientFactory webClientFactory;

    @Override
    public Mono<CheckResult> check(String url, String userAgent, int timeoutSeconds,
                                   SettingsManager.ProxySettings proxySettings) {
        URI encodedUri;
        try {
            encodedUri = ProxiedWebClientFactory.normalizeUri(url);
        } catch (Exception e) {
            log.debug("URL 编码失败: {} - {}", url, e.getMessage());
            return Mono.just(CheckResult.invalid("CONNECTION_FAILED"));
        }

        WebClient client = webClientFactory.get(timeoutSeconds, proxySettings, false);

        return checkWithMethod(client, encodedUri, userAgent, timeoutSeconds, HttpMethod.HEAD)
            .onErrorResume(e -> {
                if (shouldRetryWithGet(e)) {
                    log.debug("HEAD 不可信（{}），降级使用 GET 确认: {}", e.getMessage(), url);
                    return checkWithMethod(client, encodedUri, userAgent, timeoutSeconds, HttpMethod.GET);
                }
                return handleError(url, e);
            });
    }

    private Mono<CheckResult> checkWithMethod(WebClient client, URI uri, String userAgent,
                                              int timeoutSeconds, HttpMethod method) {
        return client.method(method)
            .uri(uri)
            .header("User-Agent", userAgent)
            .retrieve()
            .toBodilessEntity()
            .map(response -> {
                HttpStatusCode status = response.getStatusCode();
                // retrieve() 已把 4xx/5xx 转成异常，这里只会收到 2xx/3xx
                if (status.is2xxSuccessful() || status.is3xxRedirection()) {
                    return CheckResult.valid();
                }
                return CheckResult.invalid("HTTP " + status.value(), status.value());
            })
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .onErrorResume(e -> {
                if (method == HttpMethod.GET) {
                    return handleError(uri.toString(), e);
                }
                return Mono.error(e);
            });
    }

    /**
     * HEAD 的结果是否不可信、需要用 GET 复核。
     * <p>
     * 按 RFC 9110，服务器不支持某方法的正确信号是 405 Method Not Allowed 与
     * 501 Not Implemented；404 则是实践中不少 CDN/服务器对 HEAD 的错误应答。
     * 三者都复核一次，避免把可访问的链接误报成断链。
     * <p>
     * 用类型判断而非匹配异常文本：{@code retrieve()} 的 4xx/5xx 直接抛
     * {@link WebClientResponseException}，不经包装；而文本匹配会被 host 名、
     * 端口号里恰好出现的 404 之类误触发，白白多发一次请求。
     */
    private boolean shouldRetryWithGet(Throwable e) {
        if (!(e instanceof WebClientResponseException wcre)) {
            return false;
        }
        int status = wcre.getStatusCode().value();
        return status == 404 || status == 405 || status == 501;
    }

    private Mono<CheckResult> handleError(String url, Throwable e) {
        if (e instanceof TimeoutException || e.getCause() instanceof TimeoutException) {
            return Mono.just(CheckResult.invalid("HTTP_TIMEOUT"));
        }

        if (e instanceof WebClientResponseException wcre) {
            int statusCode = wcre.getStatusCode().value();
            if (statusCode == 404) {
                return Mono.just(CheckResult.invalid("HTTP 404", statusCode));
            }
            return Mono.just(CheckResult.invalid("HTTP " + statusCode, statusCode));
        }

        // responseTimeout 触发的是 Netty ReadTimeoutException（继承 IOException 而非
        // java.util.concurrent.TimeoutException），不识别的话会被误判为连接失败
        if (isNettyTimeout(e) || isNettyTimeout(e.getCause())) {
            return Mono.just(CheckResult.invalid("HTTP_TIMEOUT"));
        }

        log.debug("链接检测失败: {} - {}", url, e.getMessage());
        return Mono.just(CheckResult.invalid("CONNECTION_FAILED"));
    }

    private boolean isNettyTimeout(Throwable e) {
        return e instanceof io.netty.channel.ConnectTimeoutException
            || e instanceof io.netty.handler.timeout.TimeoutException;
    }
}
