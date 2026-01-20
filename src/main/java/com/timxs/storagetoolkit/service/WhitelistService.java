package com.timxs.storagetoolkit.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * 白名单管理服务
 */
public interface WhitelistService {

    /**
     * 获取所有白名单
     */
    Flux<WhitelistItem> list();

    /**
     * 根据 URL 前缀模糊搜索白名单
     */
    Flux<WhitelistItem> search(String keyword);

    /**
     * 检查 URL 是否在白名单中（精确匹配或前缀匹配）
     */
    Mono<Boolean> isWhitelisted(String url);

    /**
     * 添加到白名单
     */
    Mono<WhitelistItem> add(String url, String note, String matchMode);

    /**
     * 批量添加到白名单
     */
    Flux<WhitelistItem> addBatch(List<String> urls);

    /**
     * 删除白名单
     */
    Mono<Void> delete(String name);

    /**
     * 清空所有白名单
     */
    Mono<Void> clearAll();

    /**
     * 白名单条目 DTO
     */
    record WhitelistItem(
        String name,
        String url,
        String note,
        Instant createdAt,
        String matchMode
    ) {}
}

