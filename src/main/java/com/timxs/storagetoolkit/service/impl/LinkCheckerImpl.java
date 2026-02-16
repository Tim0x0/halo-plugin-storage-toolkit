package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.service.LinkChecker;
import com.timxs.storagetoolkit.service.SettingsManager;
import com.timxs.storagetoolkit.service.support.TimeoutUtils;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * 链接检查服务实现类
 */
@Slf4j
@Service
public class LinkCheckerImpl implements LinkChecker {

    /**
     * WebClient 缓存 key，包含超时和代理信息
     */
    private record ClientCacheKey(int timeout, String proxyHost, int proxyPort) {
    }

    /**
     * 按超时时间和代理配置缓存 WebClient 实例
     */
    private final ConcurrentHashMap<ClientCacheKey, WebClient> webClientCache = new ConcurrentHashMap<>();

    /**
     * 获取或创建配置了底层 HTTP 超时和代理的 WebClient
     */
    private WebClient getWebClient(int timeoutSeconds, SettingsManager.ProxySettings proxySettings) {
        ClientCacheKey key;
        if (proxySettings.isEffective()) {
            key = new ClientCacheKey(timeoutSeconds, proxySettings.host(), proxySettings.port());
        } else {
            key = new ClientCacheKey(timeoutSeconds, "", 0);
        }

        return webClientCache.computeIfAbsent(key, k -> {
            HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                    TimeoutUtils.connectTimeoutMillis(k.timeout()))
                .responseTimeout(Duration.ofSeconds(k.timeout()));

            if (!k.proxyHost().isEmpty() && k.proxyPort() > 0) {
                httpClient = httpClient.proxy(proxy ->
                    proxy.type(ProxyProvider.Proxy.HTTP)
                        .host(k.proxyHost())
                        .port(k.proxyPort())
                );
                log.info("创建带代理的 WebClient: {}:{}", k.proxyHost(), k.proxyPort());
            }

            return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
        });
    }

    @Override
    public Mono<CheckResult> check(String url, String userAgent, int timeoutSeconds,
                                   SettingsManager.ProxySettings proxySettings) {
        // 先解码再编码，避免已编码的 URL（如 %E4%B8%AD）被双重编码（%25E4%25B8%25AD）
        URI encodedUri;
        try {
            String decoded = UriUtils.decode(url, StandardCharsets.UTF_8);
            encodedUri = UriComponentsBuilder.fromUriString(decoded)
                .build()
                .encode()
                .toUri();
        } catch (Exception e) {
            log.debug("URL 编码失败: {} - {}", url, e.getMessage());
            return Mono.just(CheckResult.invalid("CONNECTION_FAILED"));
        }

        WebClient client = getWebClient(timeoutSeconds, proxySettings);

        // 先尝试 HEAD 请求，失败时降级为 GET
        return checkWithHead(client, encodedUri, userAgent, timeoutSeconds)
            .onErrorResume(e -> {
                // HEAD 返回 404 时，用 GET 确认（某些服务器不支持 HEAD）
                if (is404Error(e)) {
                    log.debug("HEAD 返回 404，降级使用 GET 确认: {}", url);
                    return checkWithGet(client, encodedUri, userAgent, timeoutSeconds);
                }
                // 其他错误直接返回
                return handleError(url, e);
            });
    }

    /**
     * 使用 HEAD 请求检测链接
     */
    private Mono<CheckResult> checkWithHead(WebClient client, URI uri, String userAgent, int timeoutSeconds) {
        return client.method(HttpMethod.HEAD)
                .uri(uri)
                .header("User-Agent", userAgent)
                .retrieve()
                .toBodilessEntity()
                .map(response -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.is2xxSuccessful() || status.is3xxRedirection()) {
                        return CheckResult.valid();
                    }
                    return CheckResult.invalid("HTTP " + status.value(), status.value());
                })
                .timeout(Duration.ofSeconds(timeoutSeconds));
    }

    /**
     * 使用 GET 请求检测链接（只读取响应头，不读取 body）
     * 用于 HEAD 请求返回 404 时的降级确认
     */
    private Mono<CheckResult> checkWithGet(WebClient client, URI uri, String userAgent, int timeoutSeconds) {
        return client.method(HttpMethod.GET)
                .uri(uri)
                .header("User-Agent", userAgent)
                .retrieve()
                .toBodilessEntity()
                .map(response -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.is2xxSuccessful() || status.is3xxRedirection()) {
                        log.debug("GET 确认链接有效: {} -> {}", uri, status.value());
                        return CheckResult.valid();
                    }
                    // GET 也返回错误，确认是断链
                    if (status.value() == 404) {
                        log.debug("GET 确认链接 404: {}", uri);
                        return CheckResult.invalid("HTTP 404", 404);
                    }
                    log.debug("GET 确认链接错误: {} -> {}", uri, status.value());
                    return CheckResult.invalid("HTTP " + status.value(), status.value());
                })
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .onErrorResume(e -> handleError(uri.toString(), e));
    }

    /**
     * 判断错误是否为 404
     */
    private boolean is404Error(Throwable e) {
        if (e instanceof WebClientResponseException wcre) {
            return wcre.getStatusCode().value() == 404;
        }
        String message = e.getMessage();
        if (message != null) {
            return message.contains("404") || message.contains("NOT_FOUND");
        }
        return false;
    }

    /**
     * 处理错误并返回检测结果
     */
    private Mono<CheckResult> handleError(String url, Throwable e) {
        // 超时
        if (e instanceof TimeoutException || e.getCause() instanceof TimeoutException) {
            return Mono.just(CheckResult.invalid("HTTP_TIMEOUT"));
        }

        // 处理 WebClient 直接抛出的 HTTP 错误
        if (e instanceof WebClientResponseException wcre) {
            int statusCode = wcre.getStatusCode().value();
            if (statusCode == 404) {
                return Mono.just(CheckResult.invalid("HTTP 404", statusCode));
            }
            return Mono.just(CheckResult.invalid("HTTP " + statusCode, statusCode));
        }

        // Netty 连接超时
        if (e instanceof io.netty.channel.ConnectTimeoutException) {
            return Mono.just(CheckResult.invalid("HTTP_TIMEOUT"));
        }

        // 其他连接失败
        log.debug("链接检测失败: {} - {}", url, e.getMessage());
        return Mono.just(CheckResult.invalid("CONNECTION_FAILED"));
    }
}
