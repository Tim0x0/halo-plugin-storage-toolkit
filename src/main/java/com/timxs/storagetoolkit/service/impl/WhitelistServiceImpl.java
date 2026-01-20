package com.timxs.storagetoolkit.service.impl;

import com.timxs.storagetoolkit.extension.WhitelistEntry;
import com.timxs.storagetoolkit.service.WhitelistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 白名单管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhitelistServiceImpl implements WhitelistService {

    private final ReactiveExtensionClient client;

    @Override
    public Flux<WhitelistItem> list() {
        return client.listAll(WhitelistEntry.class, ListOptions.builder().build(), Sort.unsorted())
            .map(this::toWhitelistItem);
    }

    @Override
    public Flux<WhitelistItem> search(String keyword) {
        return client.listAll(WhitelistEntry.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(entry -> {
                if (entry.getSpec() == null) return false;
                String url = entry.getSpec().getUrl();
                String note = entry.getSpec().getNote();
                // 在 URL 或备注中搜索关键词
                if (url != null && url.toLowerCase().contains(keyword.toLowerCase())) {
                    return true;
                }
                return note != null && note.toLowerCase().contains(keyword.toLowerCase());
            })
            .map(this::toWhitelistItem);
    }

    @Override
    public Mono<Boolean> isWhitelisted(String url) {
        if (url == null || url.isBlank()) {
            return Mono.just(false);
        }

        return client.listAll(WhitelistEntry.class, ListOptions.builder().build(), Sort.unsorted())
            .filter(entry -> entry.getSpec() != null)
            .any(entry -> {
                String whitelistUrl = entry.getSpec().getUrl();
                String matchMode = entry.getSpec().getMatchMode();
                if (whitelistUrl == null) return false;

                // 精确匹配
                if ("exact".equals(matchMode)) {
                    return url.equals(whitelistUrl);
                }
                // 前缀匹配（默认）
                return url.startsWith(whitelistUrl);
            });
    }

    @Override
    public Mono<WhitelistItem> add(String url, String note, String matchMode) {
        if (url == null || url.isBlank()) {
            return Mono.error(new IllegalArgumentException("URL 不能为空"));
        }

        WhitelistEntry entry = new WhitelistEntry();
        Metadata metadata = new Metadata();
        metadata.setName(generateName(url));
        entry.setMetadata(metadata);

        WhitelistEntry.WhitelistEntrySpec spec = new WhitelistEntry.WhitelistEntrySpec();
        spec.setUrl(url);
        spec.setNote(note);
        spec.setCreatedAt(Instant.now());
        spec.setMatchMode(matchMode != null ? matchMode : "exact");
        entry.setSpec(spec);

        return client.create(entry)
            .onErrorResume(DuplicateKeyException.class, e -> {
                // 如果已存在（name 冲突），则更新
                log.debug("白名单条目已存在，尝试更新: {}", metadata.getName());
                return client.fetch(WhitelistEntry.class, metadata.getName())
                    .switchIfEmpty(Mono.error(new IllegalStateException("白名单条目不存在")))
                    .flatMap(existing -> {
                        existing.setSpec(spec);
                        return client.update(existing);
                    });
            })
            .map(this::toWhitelistItem);
    }

    @Override
    public Flux<WhitelistItem> addBatch(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return Flux.empty();
        }

        return Flux.fromStream(urls.stream())
            .flatMap(url -> add(url, null, "exact")
                .doOnError(e -> log.warn("添加白名单失败: {} - {}", url, e.getMessage())));
    }

    @Override
    public Mono<Void> delete(String name) {
        return client.fetch(WhitelistEntry.class, name)
            .flatMap(entry -> client.delete(entry).then(Mono.empty()));
    }

    @Override
    public Mono<Void> clearAll() {
        return client.listAll(WhitelistEntry.class, ListOptions.builder().build(), Sort.unsorted())
            .collectList()
            .flatMap(list -> {
                List<Mono<Void>> deleteMonos = new ArrayList<>();
                for (WhitelistEntry entry : list) {
                    deleteMonos.add(client.delete(entry).then(Mono.empty()));
                }
                return Mono.when(deleteMonos.toArray(new Mono[0]));
            });
    }

    private WhitelistItem toWhitelistItem(WhitelistEntry entry) {
        if (entry.getSpec() == null) {
            return null;
        }
        return new WhitelistItem(
            entry.getMetadata().getName(),
            entry.getSpec().getUrl(),
            entry.getSpec().getNote(),
            entry.getSpec().getCreatedAt(),
            entry.getSpec().getMatchMode()
        );
    }

    /**
     * 根据 URL 生成唯一的资源名称
     * 使用时间戳和纳秒时间确保唯一性，与断链记录逻辑一致
     */
    private String generateName(String url) {
        // 使用时间戳和纳秒时间作为名称，确保唯一性
        return "whitelist-" + System.currentTimeMillis() + "-" + System.nanoTime();
    }
}
