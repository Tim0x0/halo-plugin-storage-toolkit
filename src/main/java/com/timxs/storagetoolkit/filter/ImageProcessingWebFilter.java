package com.timxs.storagetoolkit.filter;

import com.timxs.storagetoolkit.config.ProcessingConfig;
import com.timxs.storagetoolkit.model.ProcessingResult;
import com.timxs.storagetoolkit.model.ProcessingSource;
import com.timxs.storagetoolkit.model.ProcessingStatus;
import com.timxs.storagetoolkit.service.ImageProcessor;
import com.timxs.storagetoolkit.service.ProcessingLogService;
import com.timxs.storagetoolkit.service.SettingsManager;
import com.timxs.storagetoolkit.service.SettingsManager.AttachmentUploadConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.lang.NonNull;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.security.AdditionalWebFilter;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * 图片处理 WebFilter
 * 拦截附件上传请求，在图片传递给存储策略之前进行处理
 * 支持控制台上传和编辑器上传两种来源
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageProcessingWebFilter implements AdditionalWebFilter {

    private final ImageProcessor imageProcessor;
    private final SettingsManager settingsManager;
    private final ProcessingLogService processingLogService;

    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    /**
     * 默认并发处理数
     */
    private static final int DEFAULT_PROCESSING_CONCURRENCY = 3;

    /**
     * 并发处理限制信号量
     * 使用 volatile 确保多线程可见性
     */
    private volatile Semaphore processingPermits = new Semaphore(DEFAULT_PROCESSING_CONCURRENCY);

    /**
     * 当前配置的并发数
     */
    private volatile int currentConcurrency = DEFAULT_PROCESSING_CONCURRENCY;

    /**
     * 控制台编辑器上传路径匹配器（新版 Console API - Halo 2.22+）
     */
    private final ServerWebExchangeMatcher consoleEditorMatcher = ServerWebExchangeMatchers.pathMatchers(
        "/apis/console.api.storage.halo.run/v1alpha1/attachments/-/upload"
    );

    /**
     * 个人中心编辑器上传路径匹配器（UC API）
     */
    private final ServerWebExchangeMatcher ucEditorMatcher = ServerWebExchangeMatchers.pathMatchers(
        "/apis/uc.api.storage.halo.run/v1alpha1/attachments/-/upload"
    );

    /**
     * 控制台附件管理上传路径匹配器（旧版 Console API）
     */
    private final ServerWebExchangeMatcher attachmentManagerMatcher = ServerWebExchangeMatchers.pathMatchers(
        "/apis/api.console.halo.run/v1alpha1/attachments/upload"
    );

    /**
     * 获取处理许可的 Semaphore
     * 如果配置的并发数发生变化，会重建 Semaphore
     */
    private Semaphore getProcessingPermits(ProcessingConfig config) {
        int configuredConcurrency = config.getImageProcessingConcurrency();
        if (configuredConcurrency < 1) {
            configuredConcurrency = DEFAULT_PROCESSING_CONCURRENCY;
        } else if (configuredConcurrency > 10) {
            configuredConcurrency = 10;
        }

        if (configuredConcurrency != currentConcurrency) {
            synchronized (this) {
                if (configuredConcurrency != currentConcurrency) {
                    log.info("图片处理并发数配置变更: {} -> {}", currentConcurrency, configuredConcurrency);
                    currentConcurrency = configuredConcurrency;
                    processingPermits = new Semaphore(configuredConcurrency);
                }
            }
        }
        return processingPermits;
    }

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        return consoleEditorMatcher.matches(exchange)
            .filter(ServerWebExchangeMatcher.MatchResult::isMatch)
            .switchIfEmpty(
                // 不匹配 Console Editor，检查 UC Editor
                ucEditorMatcher.matches(exchange)
                    .filter(ServerWebExchangeMatcher.MatchResult::isMatch)
                    .switchIfEmpty(
                        // 不匹配 UC Editor，检查附件管理
                        attachmentManagerMatcher.matches(exchange)
                            .filter(ServerWebExchangeMatcher.MatchResult::isMatch)
                            .switchIfEmpty(chain.filter(exchange).then(Mono.empty()))
                            .flatMap(match -> processAttachmentManagerRequest(exchange, chain).then(Mono.empty()))
                    )
                    .flatMap(match -> processEditorRequest(exchange, chain, ProcessingSource.UC_EDITOR).then(Mono.empty()))
            )
            .flatMap(match -> processEditorRequest(exchange, chain, ProcessingSource.CONSOLE_EDITOR).then(Mono.empty()));
    }

    /**
     * 处理编辑器上传请求（Console/UC）
     * 通过 decorateExchange + chain.filter() 传递处理后的图片给下游控制器
     */
    private Mono<Void> processEditorRequest(ServerWebExchange exchange, WebFilterChain chain, ProcessingSource source) {
        return settingsManager.getConfig()
            .flatMap(config -> {
                if (!config.isEnabled()) {
                    log.debug("Image processing disabled globally");
                    return chain.filter(exchange);
                }
                if (!config.isProcessEditorImages()) {
                    log.debug("Editor image processing disabled");
                    return chain.filter(exchange);
                }

                // 获取对应的附件配置
                Mono<AttachmentUploadConfig> configMono = ProcessingSource.CONSOLE_EDITOR == source
                    ? settingsManager.getConsoleAttachmentConfig()
                    : settingsManager.getUcAttachmentConfig();

                return configMono.flatMap(attachConfig -> {
                    if (!shouldProcessForConfig(config, attachConfig.policyName(), attachConfig.groupName())) {
                        log.debug("{}: policy/group not matched, skip processing", source);
                        return chain.filter(exchange);
                    }
                    return doProcessEditorUpload(exchange, chain, config, source);
                });
            });
    }

    /**
     * 执行编辑器上传处理
     * 处理图片后通过 decorateExchange + chain.filter() 传递给下游控制器
     */
    private Mono<Void> doProcessEditorUpload(ServerWebExchange exchange, WebFilterChain chain,
                                              ProcessingConfig config, ProcessingSource source) {
        if (!isMultipartRequest(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        return exchange.getMultipartData()
            .flatMap(parts -> {
                FilePart filePart = (FilePart) parts.getFirst("file");
                if (filePart == null) {
                    return chain.filter(exchange);
                }

                String filename = filePart.filename();
                String contentType = getContentType(filePart);

                // 检查是否有任何处理功能启用
                if (!imageProcessor.hasProcessingEnabled(config)) {
                    log.debug("No processing enabled, skip: {}", filename);
                    // 重建请求传递下游（multipart 数据已被读取）
                    return filePart.content().collectList()
                        .flatMap(buffers -> decorateExchange(exchange, parts, filePart, Flux.fromIterable(buffers)))
                        .flatMap(chain::filter);
                }

                // 检查是否是允许处理的格式，不是则传递下游
                if (!imageProcessor.isAllowedFormat(contentType, config)) {
                    log.debug("Format not in allowed list, skip processing: {} ({})", filename, contentType);
                    // 重建请求传递下游（multipart 数据已被读取）
                    return filePart.content().collectList()
                        .flatMap(buffers -> decorateExchange(exchange, parts, filePart, Flux.fromIterable(buffers)))
                        .flatMap(chain::filter);
                }

                return doProcessImage(exchange, chain, parts, filePart, config, source);
            });
    }

    /**
     * 处理控制台附件管理上传请求（保持原有逻辑）
     */
    private Mono<Void> processAttachmentManagerRequest(ServerWebExchange exchange, WebFilterChain chain) {
        return settingsManager.getConfig()
            .flatMap(config -> {
                if (!config.isEnabled()) {
                    return chain.filter(exchange);
                }
                return processRequest(exchange, chain, ProcessingSource.ATTACHMENT_MANAGER, config);
            });
    }

    /**
     * 处理附件管理上传请求（装饰 exchange 方式）
     */
    private Mono<Void> processRequest(ServerWebExchange exchange, WebFilterChain chain,
                                       ProcessingSource source, ProcessingConfig config) {
        if (!isMultipartRequest(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (contentType == null || contentType.getParameter("boundary") == null) {
            return chain.filter(exchange);
        }

        return exchange.getMultipartData()
            .flatMap(parts -> {
                FilePart filePart = (FilePart) parts.getFirst("file");
                if (filePart == null) {
                    return chain.filter(exchange);
                }

                String fileContentType = getContentType(filePart);

                // 检查是否有任何处理功能启用
                if (!imageProcessor.hasProcessingEnabled(config)) {
                    log.debug("No processing enabled, skip: {}", filePart.filename());
                    return filePart.content().collectList()
                        .flatMap(buffers -> decorateExchange(exchange, parts, filePart, Flux.fromIterable(buffers)))
                        .flatMap(chain::filter);
                }

                // 检查是否是允许处理的格式
                if (!imageProcessor.isAllowedFormat(fileContentType, config)) {
                    log.debug("Format not in allowed list, skip processing: {} ({})",
                        filePart.filename(), fileContentType);
                    return filePart.content().collectList()
                        .flatMap(buffers -> decorateExchange(exchange, parts, filePart, Flux.fromIterable(buffers)))
                        .flatMap(chain::filter);
                }

                // 检查策略/分组
                if (!shouldProcessForPolicyAndGroup(parts, config)) {
                    log.debug("Attachment manager: policy or group not in target list");
                    return filePart.content().collectList()
                        .flatMap(buffers -> decorateExchange(exchange, parts, filePart, Flux.fromIterable(buffers)))
                        .flatMap(chain::filter);
                }

                return doProcessImage(exchange, chain, parts, filePart, config, source);
            });
    }

    /**
     * 执行图片处理核心逻辑（编辑器上传和附件管理共用）
     * 包含：大小检查 → 信号量控制 → 图片处理 → 结果分发
     */
    private Mono<Void> doProcessImage(ServerWebExchange exchange, WebFilterChain chain,
                                       MultiValueMap<String, Part> parts, FilePart filePart,
                                       ProcessingConfig config, ProcessingSource source) {
        String filename = filePart.filename();
        String contentType = getContentType(filePart);
        Instant startTime = Instant.now();

        // 提前检查 Content-Length，大文件直接跳过处理
        long contentLength = filePart.headers().getContentLength();
        long maxFileSize = config.getMaxFileSize();
        if (maxFileSize > 0 && contentLength > 0 && contentLength > maxFileSize) {
            log.debug("File size {} exceeds max limit {}, skip processing: {}",
                contentLength, maxFileSize, filename);
            saveSkippedLog(filename, contentType, contentLength, startTime,
                "文件大小超过限制（提前检查）", source);
            // 传递下游处理
            return filePart.content().collectList()
                .flatMap(buffers -> decorateExchange(exchange, parts, filePart, Flux.fromIterable(buffers)))
                .flatMap(chain::filter);
        }

        // 获取处理许可，限制并发数
        // 注意：必须在 acquire 时保存 Semaphore 引用，确保 release 同一个对象
        final Semaphore permits = getProcessingPermits(config);
        return Mono.fromCallable(() -> {
                permits.acquire();
                return permits; // 返回获取许可的 Semaphore 引用
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(acquiredPermits -> DataBufferUtils.join(filePart.content())
                .flatMap(dataBuffer -> {
                    byte[] imageData = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(imageData);
                    DataBufferUtils.release(dataBuffer);
                    long originalSize = imageData.length;

                    // 二次校验文件大小（Content-Length 可能为 -1 被绕过）
                    if (maxFileSize > 0 && originalSize > maxFileSize) {
                        log.debug("File size {} exceeds max limit {}, skip processing: {}",
                            originalSize, maxFileSize, filename);
                        saveSkippedLog(filename, contentType, originalSize, startTime,
                            "文件大小超过限制", source);
                        // 用原图数据重建请求，传递下游
                        DataBuffer buffer = bufferFactory.wrap(imageData);
                        return decorateExchange(exchange, parts, filePart, Flux.just(buffer))
                            .flatMap(chain::filter);
                    }

                    String skipReason = imageProcessor.getSkipReason(contentType, originalSize, config);
                    if (skipReason != null) {
                        log.debug("File skipped: {} - {}", filename, skipReason);
                        saveSkippedLog(filename, contentType, originalSize, startTime, skipReason, source);
                        // 用原图数据重建请求，传递下游
                        DataBuffer buffer = bufferFactory.wrap(imageData);
                        return decorateExchange(exchange, parts, filePart, Flux.just(buffer))
                            .flatMap(chain::filter);
                    }

                    return imageProcessor.process(imageData, filename, contentType, config)
                        .onErrorResume(e -> {
                            // 仅捕获图片处理异常，回退原图
                            log.warn("Image processing error, passing original to downstream: {}", e.getMessage());
                            return Mono.just(ProcessingResult.failed(imageData, filename, contentType, e.getMessage()));
                        })
                        .flatMap(result -> {
                            saveProcessingLog(result, filename, originalSize, startTime, source);

                            // SUCCESS 或 PARTIAL：用处理后的数据替换原始数据，传递给下游控制器
                            if (result.status() == ProcessingStatus.SUCCESS ||
                                result.status() == ProcessingStatus.PARTIAL) {
                                log.debug("Image processed: {} -> {} ({} bytes -> {} bytes, {}% reduction)",
                                    filename, result.filename(),
                                    originalSize, result.data().length,
                                    originalSize > 0 ? (100 - (result.data().length * 100 / originalSize)) : 0);

                                MediaType processedContentType = MediaType.parseMediaType(result.contentType());
                                DataBuffer buffer = bufferFactory.wrap(result.data());
                                return decorateExchange(exchange, parts, filePart, Flux.just(buffer),
                                        result.filename(), processedContentType)
                                    .flatMap(chain::filter);
                            }

                            // SKIPPED 或 FAILED，传递下游
                            DataBuffer buffer = bufferFactory.wrap(imageData);
                            return decorateExchange(exchange, parts, filePart, Flux.just(buffer))
                                .flatMap(chain::filter);
                        });
                })
                .doFinally(signal -> acquiredPermits.release()) // 释放获取许可时的同一个 Semaphore
            );
    }

    private boolean shouldProcessForConfig(ProcessingConfig config, String policyName, String groupName) {
        List<String> targetPolicies = config.getTargetPolicies();
        if (targetPolicies != null && !targetPolicies.isEmpty()) {
            String currentPolicy = policyName != null ? policyName : "";
            if (!targetPolicies.contains(currentPolicy)) {
                log.debug("Policy mismatch: targetPolicies={}, currentPolicy={}", targetPolicies, currentPolicy);
                return false;
            }
        }

        List<String> targetGroups = config.getTargetGroups();
        if (targetGroups != null && !targetGroups.isEmpty()) {
            String currentGroup = groupName != null ? groupName : "";
            if (!targetGroups.contains(currentGroup)) {
                log.debug("Group mismatch: targetGroups={}, currentGroup={}", targetGroups, currentGroup);
                return false;
            }
        }

        return true;
    }

    private boolean shouldProcessForPolicyAndGroup(MultiValueMap<String, Part> parts, ProcessingConfig config) {
        String currentPolicy = "";
        String currentGroup = "";

        FormFieldPart policyPart = (FormFieldPart) parts.getFirst("policyName");
        if (policyPart != null) {
            currentPolicy = policyPart.value();
        }

        Part groupPart = parts.getFirst("groupName");
        if (groupPart instanceof FormFieldPart formField) {
            currentGroup = formField.value();
        }

        return shouldProcessForConfig(config, currentPolicy, currentGroup);
    }

    private Mono<ServerWebExchange> decorateExchange(ServerWebExchange exchange,
                                                      MultiValueMap<String, Part> parts,
                                                      FilePart filePart,
                                                      Flux<DataBuffer> processedImage) {
        return decorateExchange(exchange, parts, filePart, processedImage,
            filePart.filename(), filePart.headers().getContentType());
    }

    private Mono<ServerWebExchange> decorateExchange(ServerWebExchange exchange,
                                                      MultiValueMap<String, Part> parts,
                                                      FilePart filePart,
                                                      Flux<DataBuffer> processedImage,
                                                      String newFilename,
                                                      MediaType newContentType) {
        String boundary = getBoundary(exchange);
        if (boundary == null) {
            log.warn("Missing boundary in request");
            return Mono.just(exchange);
        }

        return processedImage.collectList()
            .flatMap(buffers -> createDecoratedExchange(exchange, parts, boundary, buffers, newFilename, newContentType));
    }

    private Mono<ServerWebExchange> createDecoratedExchange(final ServerWebExchange exchange,
                                                             MultiValueMap<String, Part> parts,
                                                             String boundary,
                                                             List<DataBuffer> buffers,
                                                             String filename,
                                                             MediaType contentType) {
        final byte[] headerBytes = buildMultipartContent(boundary, parts, filename, contentType).getBytes();
        final byte[] footerBytes = ("\r\n--" + boundary + "--\r\n").getBytes();

        final List<DataBuffer> finalBuffers = buffers.isEmpty()
            ? List.of(bufferFactory.wrap(new byte[0]))
            : buffers;

        // 合并 buffers 为 byte[]，构建 ProcessedFilePart 用于覆写 getMultipartData()
        final byte[] combinedBytes = combineBuffers(finalBuffers);
        ProcessedFilePart processedFilePart = new ProcessedFilePart(
            filename, contentType, combinedBytes, bufferFactory);
        MultiValueMap<String, Part> newParts = new LinkedMultiValueMap<>();
        for (var entry : parts.entrySet()) {
            if (!"file".equals(entry.getKey())) {
                newParts.put(entry.getKey(), entry.getValue());
            }
        }
        newParts.add("file", processedFilePart);

        // 计算重建后的 body 总大小，用于修正 Content-Length
        final long newContentLength = headerBytes.length + combinedBytes.length + footerBytes.length;

        final ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            @NonNull
            public Flux<DataBuffer> getBody() {
                return Flux.just(
                    bufferFactory.wrap(headerBytes),
                    bufferFactory.wrap(combinedBytes),
                    bufferFactory.wrap(footerBytes)
                );
            }

            @Override
            @NonNull
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(exchange.getRequest().getHeaders());
                // 修正 Content-Length，使其与重建后的 body 大小一致
                headers.setContentLength(newContentLength);
                return headers;
            }
        };

        return Mono.just(new ServerWebExchangeDecorator(exchange) {
            @Override
            @NonNull
            public ServerHttpRequest getRequest() {
                return decoratedRequest;
            }

            @Override
            @NonNull
            public Mono<MultiValueMap<String, Part>> getMultipartData() {
                return Mono.just(newParts);
            }
        });
    }

    private String buildMultipartContent(String boundary,
                                          MultiValueMap<String, Part> parts,
                                          String filename,
                                          MediaType contentType) {
        StringBuilder content = new StringBuilder();

        for (var entry : parts.entrySet()) {
            String partName = entry.getKey();
            List<Part> partList = entry.getValue();

            if ("file".equals(partName)) {
                continue;
            }

            for (Part part : partList) {
                if (part instanceof FormFieldPart formField) {
                    content.append("--").append(boundary).append("\r\n");
                    content.append("Content-Disposition: form-data; name=\"").append(partName).append("\"\r\n");
                    content.append("\r\n");
                    content.append(formField.value()).append("\r\n");
                }
            }
        }

        content.append("--").append(boundary).append("\r\n");
        content.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
            .append(filename).append("\"\r\n");
        String contentTypeStr = contentType != null ? contentType.toString() : "application/octet-stream";
        content.append("Content-Type: ").append(contentTypeStr).append("\r\n");
        content.append("\r\n");

        return content.toString();
    }

    private String getBoundary(ServerWebExchange exchange) {
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (contentType == null || !contentType.isCompatibleWith(MediaType.MULTIPART_FORM_DATA)) {
            return null;
        }
        return contentType.getParameter("boundary");
    }

    private boolean isMultipartRequest(ServerHttpRequest request) {
        MediaType contentType = request.getHeaders().getContentType();
        return contentType != null && contentType.isCompatibleWith(MediaType.MULTIPART_FORM_DATA);
    }

    private String getContentType(FilePart filePart) {
        MediaType contentType = filePart.headers().getContentType();
        return contentType != null ? contentType.toString() : null;
    }

    private void saveProcessingLog(ProcessingResult result, String originalFilename,
                                    long originalSize, Instant startTime, ProcessingSource source) {
        processingLogService.saveResultLog(result, originalFilename, originalSize, startTime, source)
            .subscribe(
                saved -> log.debug("Processing log saved: {}", saved.getMetadata().getName()),
                error -> log.error("Failed to save processing log", error)
            );
    }

    private void saveSkippedLog(String filename, String contentType, long fileSize,
                                 Instant startTime, String reason, ProcessingSource source) {
        processingLogService.saveSkippedLog(filename, contentType, fileSize, startTime, reason, source)
            .subscribe(
                saved -> log.debug("Skipped log saved: {}", saved.getMetadata().getName()),
                error -> log.error("Failed to save skipped log", error)
            );
    }

    @Override
    public int getOrder() {
        return -1000;  // 确保最先执行，避免与其他插件冲突
    }

    /**
     * 合并多个 DataBuffer 为 byte[]
     */
    private byte[] combineBuffers(List<DataBuffer> buffers) {
        int total = buffers.stream().mapToInt(DataBuffer::readableByteCount).sum();
        byte[] result = new byte[total];
        int offset = 0;
        for (DataBuffer buf : buffers) {
            int len = buf.readableByteCount();
            buf.read(result, offset, len);
            offset += len;
        }
        return result;
    }

    /**
     * 包装处理后图片数据的 FilePart 实现
     * 用于覆写 getMultipartData() 返回的 Part，使编辑器端点能读取处理后的数据
     */
    private static class ProcessedFilePart implements FilePart {
        private final String filename;
        private final HttpHeaders headers;
        private final byte[] data;
        private final DefaultDataBufferFactory bufferFactory;

        ProcessedFilePart(String filename, MediaType contentType, byte[] data,
                          DefaultDataBufferFactory bufferFactory) {
            this.filename = filename;
            this.data = data;
            this.bufferFactory = bufferFactory;
            this.headers = new HttpHeaders();
            this.headers.setContentType(contentType);
            this.headers.setContentDispositionFormData("file", filename);
            this.headers.setContentLength(data.length);
        }

        @Override
        public String name() { return "file"; }

        @Override
        public String filename() { return filename; }

        @Override
        public HttpHeaders headers() { return headers; }

        @Override
        public Flux<DataBuffer> content() {
            return Flux.just(bufferFactory.wrap(data));
        }

        @Override
        public Mono<Void> transferTo(Path dest) {
            return DataBufferUtils.write(content(), dest);
        }
    }
}
