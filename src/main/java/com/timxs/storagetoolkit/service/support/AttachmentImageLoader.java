package com.timxs.storagetoolkit.service.support;

import com.timxs.storagetoolkit.config.ProcessingConfig;
import com.timxs.storagetoolkit.service.SettingsManager;

import lombok.RequiredArgsConstructor;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import run.halo.app.infra.ExternalLinkProcessor;

@Component
@RequiredArgsConstructor
public class AttachmentImageLoader {
    private final SettingsManager settingsManager;
    private final ExternalLinkProcessor externalLinkProcessor;
    private final ProxiedWebClientFactory webClientFactory;

    public Mono<byte[]> download(String permalink, ProcessingConfig config) {
        return settingsManager
                .getDownloadTimeoutSeconds()
                .zipWith(
                        settingsManager
                                .getProxySettings()
                                .defaultIfEmpty(SettingsManager.ProxySettings.disabled()))
                .flatMap(
                        tuple -> {
                            int timeoutSeconds = tuple.getT1();
                            SettingsManager.ProxySettings proxySettings = tuple.getT2();
                            String fullUrl = externalLinkProcessor.processLink(permalink);
                            String siteUrl = externalLinkProcessor.processLink("/");

                            // maxFileSize 为 0 表示用户不限制大小
                            long maxFileSize = config.getMaxFileSize();
                            int maxBytes =
                                    (maxFileSize > 0 && maxFileSize < Integer.MAX_VALUE)
                                            ? (int) maxFileSize
                                            : Integer.MAX_VALUE;

                            return webClientFactory
                                    .get(timeoutSeconds, proxySettings, true)
                                    .get()
                                    .uri(
                                            com.timxs.storagetoolkit.service.support
                                                    .ProxiedWebClientFactory.normalizeUri(fullUrl))
                                    .header(
                                            HttpHeaders.USER_AGENT,
                                            SettingsManager.DEFAULT_USER_AGENT)
                                    .header(HttpHeaders.REFERER, siteUrl)
                                    .retrieve()
                                    .onStatus(
                                            status -> !status.is2xxSuccessful(),
                                            response ->
                                                    Mono.error(
                                                            new RuntimeException(
                                                                    "HTTP "
                                                                            + response.statusCode()
                                                                                    .value())))
                                    .bodyToFlux(DataBuffer.class)
                                    // as 而非 transform：join 返回 Mono，用 transform 会被重新包成 Flux
                                    .as(body -> DataBufferUtils.join(body, maxBytes))
                                    .map(
                                            buffer -> {
                                                try {
                                                    byte[] bytes =
                                                            new byte[buffer.readableByteCount()];
                                                    buffer.read(bytes);
                                                    return bytes;
                                                } finally {
                                                    DataBufferUtils.release(buffer);
                                                }
                                            })
                                    .timeout(
                                            java.time.Duration.ofSeconds(timeoutSeconds),
                                            Mono.error(
                                                    new java.util.concurrent.TimeoutException(
                                                            "下载文件超时: " + permalink)));
                        });
    }
}
