package com.timxs.storagetoolkit.filter;

import com.timxs.storagetoolkit.config.ProcessingConfig;
import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.model.ProcessingResult;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.security.AdditionalWebFilter;

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
    private final AttachmentService attachmentService;
    private final ServerSecurityContextRepository securityContextRepository;

    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();
    
    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = 
        run.halo.app.infra.utils.JsonUtils.mapper();

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

    private static final String SOURCE_ATTACHMENT_MANAGER = "attachment-manager";
    private static final String SOURCE_CONSOLE_EDITOR = "console-editor";
    private static final String SOURCE_UC_EDITOR = "uc-editor";

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
                    .flatMap(match -> processEditorRequest(exchange, chain, SOURCE_UC_EDITOR).then(Mono.empty()))
            )
            .flatMap(match -> processEditorRequest(exchange, chain, SOURCE_CONSOLE_EDITOR).then(Mono.empty()));
    }

    /**
     * 处理编辑器上传请求（Console/UC）
     * 直接调用 AttachmentService.upload() 完成上传，不传递给下游
     */
    private Mono<Void> processEditorRequest(ServerWebExchange exchange, WebFilterChain chain, String source) {
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
                Mono<AttachmentUploadConfig> configMono = SOURCE_CONSOLE_EDITOR.equals(source)
                    ? settingsManager.getConsoleAttachmentConfig()
                    : settingsManager.getUcAttachmentConfig();
                
                return configMono.flatMap(attachConfig -> {
                    if (!shouldProcessForConfig(config, attachConfig.policyName(), attachConfig.groupName())) {
                        log.debug("{}: policy/group not matched, skip processing", source);
                        return chain.filter(exchange);
                    }
                    return doProcessEditorUpload(exchange, chain, config, attachConfig, source);
                });
            });
    }

    /**
     * 执行编辑器上传处理
     * 处理图片后直接调用 AttachmentService 上传
     */
    private Mono<Void> doProcessEditorUpload(ServerWebExchange exchange, WebFilterChain chain,
                                              ProcessingConfig config, AttachmentUploadConfig attachConfig,
                                              String source) {
        if (!isMultipartRequest(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        // 使用 ServerSecurityContextRepository 从 session/cookie 中加载认证信息
        return securityContextRepository.load(exchange)
            .map(ctx -> ctx.getAuthentication())
            .filter(auth -> auth != null && auth.isAuthenticated())
            .switchIfEmpty(chain.filter(exchange).then(Mono.empty()))
            .flatMap(auth -> doProcessEditorUploadWithAuth(exchange, chain, config, attachConfig, source, auth)
                .then(Mono.empty()));
    }

    /**
     * 带认证信息的编辑器上传处理
     */
    private Mono<Void> doProcessEditorUploadWithAuth(ServerWebExchange exchange, WebFilterChain chain,
                                                      ProcessingConfig config, AttachmentUploadConfig attachConfig,
                                                      String source, org.springframework.security.core.Authentication auth) {
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

                Instant startTime = Instant.now();

                // 提前检查 Content-Length，大文件直接跳过处理
                long contentLength = filePart.headers().getContentLength();
                long maxFileSize = config.getMaxFileSize();
                if (maxFileSize > 0 && contentLength > 0 && contentLength > maxFileSize) {
                    log.debug("File size {} exceeds max limit {}, skip processing: {}",
                        contentLength, maxFileSize, filename);
                    saveSkippedLog(filename, contentType, contentLength, startTime,
                        "文件大小超过限制（提前检查）", source);
                    // 重建请求传递下游（multipart 数据已被读取）
                    return filePart.content().collectList()
                        .flatMap(buffers -> decorateExchange(exchange, parts, filePart, Flux.fromIterable(buffers)))
                        .flatMap(chain::filter);
                }

                // 获取处理许可，限制并发数
                // 注意：必须在 acquire 时保存 Semaphore 引用，确保 release 同一个对象
                // 避免配置变更导致的竞态条件
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

                            // 检查是否需要处理
                            String skipReason = imageProcessor.getSkipReason(contentType, originalSize, config);
                            if (skipReason != null) {
                                log.debug("File skipped: {} - {}", filename, skipReason);
                                saveSkippedLog(filename, contentType, originalSize, startTime, skipReason, source);
                                // 用原图数据重建请求，传递下游
                                DataBuffer buffer = bufferFactory.wrap(imageData);
                                return decorateExchange(exchange, parts, filePart, Flux.just(buffer))
                                    .flatMap(chain::filter);
                            }

                            // 处理图片
                            return imageProcessor.process(imageData, filename, contentType, config)
                                .flatMap(result -> {
                                    saveProcessingLog(result, filename, originalSize, startTime, source);

                                    // 只有 SUCCESS 和 PARTIAL 才直接上传
                                    if (result.status() == ProcessingStatus.SUCCESS ||
                                        result.status() == ProcessingStatus.PARTIAL) {
                                        log.debug("Image processed: {} -> {} ({} bytes -> {} bytes, {}% reduction)",
                                            filename, result.filename(),
                                            originalSize, result.data().length,
                                            originalSize > 0 ? (100 - (result.data().length * 100 / originalSize)) : 0);

                                        return uploadAndRespond(attachConfig, result.filename(),
                                            result.data(), MediaType.parseMediaType(result.contentType()), auth, exchange);
                                    }

                                    // SKIPPED 或 FAILED，传递下游
                                    DataBuffer buffer = bufferFactory.wrap(imageData);
                                    return decorateExchange(exchange, parts, filePart, Flux.just(buffer))
                                        .flatMap(chain::filter);
                                })
                                .onErrorResume(e -> {
                                    log.error("Image processing error, passing to downstream: {}", e.getMessage());
                                    // 异常时传递下游
                                    DataBuffer buffer = bufferFactory.wrap(imageData);
                                    return decorateExchange(exchange, parts, filePart, Flux.just(buffer))
                                        .flatMap(chain::filter);
                                });
                        })
                        .doFinally(signal -> acquiredPermits.release()) // 释放获取许可时的同一个 Semaphore
                    );
            });
    }

    /**
     * 调用 AttachmentService 上传文件并返回 JSON 响应
     */
    private Mono<Void> uploadAndRespond(AttachmentUploadConfig attachConfig, String filename, 
                                         byte[] data, MediaType mediaType,
                                         org.springframework.security.core.Authentication auth,
                                         ServerWebExchange exchange) {
        log.debug("Uploading file: {} to policy: {}, group: {}, user: {}", 
            filename, attachConfig.policyName(), attachConfig.groupName(), auth.getName());
        
        Flux<DataBuffer> content = Flux.just(bufferFactory.wrap(data));
        
        return attachmentService.upload(
                attachConfig.policyName(),
                attachConfig.groupName(),
                filename,
                content,
                mediaType
            )
            .doOnNext(a -> log.info("Upload success: {}", a.getMetadata().getName()))
            .flatMap(attachment -> attachmentService.getPermalink(attachment)
                .doOnNext(permalink -> {
                    if (attachment.getStatus() == null) {
                        attachment.setStatus(new Attachment.AttachmentStatus());
                    }
                    attachment.getStatus().setPermalink(permalink.toString());
                })
                .thenReturn(attachment)
            )
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
            .flatMap(attachment -> writeJsonResponse(exchange, attachment))
            .onErrorResume(e -> {
                log.error("Failed to upload attachment: {}", e.getMessage(), e);
                return writeErrorResponse(exchange, e.getMessage());
            });
    }

    /**
     * 写入 JSON 响应
     */
    private Mono<Void> writeJsonResponse(ServerWebExchange exchange, Attachment attachment) {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(
            Mono.fromCallable(() -> {
                byte[] jsonBytes = OBJECT_MAPPER.writeValueAsBytes(attachment);
                return bufferFactory.wrap(jsonBytes);
            })
        ).onErrorResume(e -> {
            log.error("Failed to serialize attachment: {}", e.getMessage());
            return writeErrorResponse(exchange, "Failed to serialize response");
        });
    }

    /**
     * 写入错误响应
     */
    private Mono<Void> writeErrorResponse(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String errorJson = "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        DataBuffer buffer = bufferFactory.wrap(errorJson.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
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
                return processRequest(exchange, chain, SOURCE_ATTACHMENT_MANAGER, config);
            });
    }

    /**
     * 处理附件管理上传请求（装饰 exchange 方式）
     */
    private Mono<Void> processRequest(ServerWebExchange exchange, WebFilterChain chain, 
                                       String source, ProcessingConfig config) {
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

                return doProcessAttachmentManager(exchange, chain, parts, filePart, config, source);
            });
    }

    /**
     * 执行附件管理上传的图片处理
     * 只有处理成功（SUCCESS/PARTIAL）才直接上传，其他情况传递下游
     */
    private Mono<Void> doProcessAttachmentManager(ServerWebExchange exchange, WebFilterChain chain,
                                                   MultiValueMap<String, Part> parts, FilePart filePart,
                                                   ProcessingConfig config, String source) {
        String filename = filePart.filename();
        String contentType = getContentType(filePart);
        Instant startTime = Instant.now();

        // 提取附件配置（policyName, groupName）
        AttachmentUploadConfig attachConfig = extractAttachmentConfig(parts);

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
                        .flatMap(result -> {
                            saveProcessingLog(result, filename, originalSize, startTime, source);

                            // 只有 SUCCESS 和 PARTIAL 才直接上传
                            if (result.status() == ProcessingStatus.SUCCESS ||
                                result.status() == ProcessingStatus.PARTIAL) {
                                log.info("图片处理完成，直接上传，绕过下游 Filter: {} -> {}", filename, result.filename());
                                log.debug("Image processed: {} -> {} ({} bytes -> {} bytes, {}% reduction)",
                                    filename, result.filename(),
                                    originalSize, result.data().length,
                                    originalSize > 0 ? (100 - (result.data().length * 100 / originalSize)) : 0);

                                MediaType newContentType = MediaType.parseMediaType(result.contentType());
                                return uploadWithAuth(exchange, attachConfig, result.filename(), result.data(), newContentType);
                            }

                            // SKIPPED 或 FAILED，传递下游
                            DataBuffer buffer = bufferFactory.wrap(imageData);
                            return decorateExchange(exchange, parts, filePart, Flux.just(buffer))
                                .flatMap(chain::filter);
                        })
                        .onErrorResume(e -> {
                            log.error("Image processing error, passing to downstream: {}", e.getMessage());
                            // 异常时传递下游
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
        String multipartContent = buildMultipartContent(boundary, parts, filename, contentType);
        String footer = "\r\n--" + boundary + "--\r\n";

        final DataBuffer headerBuffer = bufferFactory.wrap(multipartContent.getBytes());
        final DataBuffer footerBuffer = bufferFactory.wrap(footer.getBytes());

        final List<DataBuffer> finalBuffers = buffers.isEmpty()
            ? List.of(bufferFactory.wrap(new byte[0]))
            : buffers;

        final ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            @NonNull
            public Flux<DataBuffer> getBody() {
                return Flux.just(headerBuffer)
                    .concatWith(Flux.fromIterable(finalBuffers))
                    .concatWith(Flux.just(footerBuffer));
            }

            @Override
            @NonNull
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(exchange.getRequest().getHeaders());
                return headers;
            }
        };

        return Mono.just(new ServerWebExchangeDecorator(exchange) {
            @Override
            @NonNull
            public ServerHttpRequest getRequest() {
                return decoratedRequest;
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

    /**
     * 从 multipart parts 提取附件上传配置
     */
    private AttachmentUploadConfig extractAttachmentConfig(MultiValueMap<String, Part> parts) {
        String policyName = "";
        String groupName = "";
        FormFieldPart policyPart = (FormFieldPart) parts.getFirst("policyName");
        if (policyPart != null) {
            policyName = policyPart.value();
        }
        Part groupPart = parts.getFirst("groupName");
        if (groupPart instanceof FormFieldPart formField) {
            groupName = formField.value();
        }
        return new AttachmentUploadConfig(policyName, groupName);
    }

    /**
     * 获取认证信息并上传文件（用于附件管理上传）
     * 直接上传，不传递给下游 Filter，避免与其他插件冲突
     */
    private Mono<Void> uploadWithAuth(ServerWebExchange exchange, AttachmentUploadConfig attachConfig,
                                       String filename, byte[] data, MediaType mediaType) {
        return securityContextRepository.load(exchange)
            .map(ctx -> ctx.getAuthentication())
            .filter(auth -> auth != null && auth.isAuthenticated())
            .flatMap(auth -> uploadAndRespond(attachConfig, filename, data, mediaType, auth, exchange))
            .switchIfEmpty(writeErrorResponse(exchange, "Authentication required"));
    }

    private void saveProcessingLog(ProcessingResult result, String originalFilename,
                                    long originalSize, Instant startTime, String source) {
        processingLogService.saveResultLog(result, originalFilename, originalSize, startTime, source)
            .subscribe(
                saved -> log.debug("Processing log saved: {}", saved.getMetadata().getName()),
                error -> log.error("Failed to save processing log", error)
            );
    }

    private void saveSkippedLog(String filename, String contentType, long fileSize,
                                 Instant startTime, String reason, String source) {
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
}
