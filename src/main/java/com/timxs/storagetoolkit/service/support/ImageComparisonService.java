package com.timxs.storagetoolkit.service.support;

import com.timxs.storagetoolkit.config.ProcessingConfig;
import com.timxs.storagetoolkit.imageio.PinnedImageIo;
import com.timxs.storagetoolkit.service.ImageProcessor;
import com.timxs.storagetoolkit.service.SettingsManager;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.Base64;
import java.util.concurrent.Semaphore;

/** Runs the production image pipeline without uploading, logging or changing references. */
@Service
@RequiredArgsConstructor
public class ImageComparisonService {
    private final ReactiveExtensionClient client;
    private final SettingsManager settings;
    private final ImageProcessor processor;
    private final AttachmentImageLoader loader;
    private final PinnedImageIo imageIo;
    private final Semaphore permits = new Semaphore(1);

    public Mono<Comparison> compare(String name) {
        return Mono.defer(
                () -> {
                    if (!permits.tryAcquire())
                        return Mono.error(new IllegalStateException("已有图片正在试处理，请稍后再试"));
                    return client.fetch(Attachment.class, name)
                            .filter(
                                    a ->
                                            a.getMetadata().getDeletionTimestamp() == null
                                                    && a.getSpec() != null)
                            .switchIfEmpty(Mono.error(new IllegalArgumentException("附件不存在或已删除")))
                            .zipWith(settings.getConfig())
                            .flatMap(tuple -> compareExisting(tuple.getT1(), tuple.getT2()))
                            .doFinally(signal -> permits.release());
                });
    }

    private Mono<Comparison> compareExisting(Attachment attachment, ProcessingConfig config) {
        var spec = attachment.getSpec();
        if (!processor.hasProcessingEnabled(config))
            return Mono.error(new IllegalStateException("请先启用水印或格式转换"));
        if (!processor.isAllowedFormat(spec.getMediaType(), config))
            return Mono.error(new IllegalArgumentException("此格式不在允许处理列表中"));
        long size = spec.getSize() == null ? 0 : spec.getSize();
        if (!processor.shouldProcess(spec.getMediaType(), size, config))
            return Mono.error(
                    new IllegalArgumentException(
                            processor.getSkipReason(spec.getMediaType(), size, config)));
        String url = attachment.getStatus() == null ? null : attachment.getStatus().getPermalink();
        if (url == null || url.isBlank())
            return Mono.error(new IllegalArgumentException("附件链接尚未就绪"));
        // SettingsManager returns a fresh config. Bound both images in the interactive response.
        config.setMaxFileSize(
                config.getMaxFileSize() > 0
                        ? Math.min(config.getMaxFileSize(), 20 * 1024 * 1024)
                        : 20 * 1024 * 1024);
        return loader.download(url, config)
                .flatMap(
                        original ->
                                processor
                                        .process(
                                                original,
                                                spec.getDisplayName(),
                                                spec.getMediaType(),
                                                config)
                                        .flatMap(
                                                result ->
                                                        Mono.fromCallable(
                                                                        () ->
                                                                                new Comparison(
                                                                                        result.status()
                                                                                                .name(),
                                                                                        result
                                                                                                .message(),
                                                                                        variant(
                                                                                                original,
                                                                                                spec
                                                                                                        .getMediaType()),
                                                                                        variant(
                                                                                                result
                                                                                                        .data(),
                                                                                                result
                                                                                                        .contentType())))
                                                                .subscribeOn(
                                                                        Schedulers
                                                                                .boundedElastic())));
    }

    private Variant variant(byte[] data, String mediaType) throws Exception {
        var image = imageIo.read(data);
        int width = image == null ? 0 : image.getWidth();
        int height = image == null ? 0 : image.getHeight();
        if (image != null) image.flush();
        String type =
                mediaType != null && mediaType.startsWith("image/")
                        ? mediaType
                        : "application/octet-stream";
        return new Variant(
                "data:" + type + ";base64," + Base64.getEncoder().encodeToString(data),
                data.length,
                width,
                height,
                type);
    }

    public record Variant(String dataUrl, long size, int width, int height, String mediaType) {}

    public record Comparison(String status, String message, Variant original, Variant result) {}
}
