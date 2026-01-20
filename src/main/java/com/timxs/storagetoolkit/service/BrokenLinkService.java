package com.timxs.storagetoolkit.service;

import com.timxs.storagetoolkit.extension.BrokenLinkScanStatus;
import com.timxs.storagetoolkit.model.BrokenLinkVo;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;

/**
 * 断链扫描服务接口
 * 负责扫描内容中引用了不存在附件的链接
 */
public interface BrokenLinkService {

    /**
     * 开始断链扫描
     * 
     * @return 扫描状态
     */
    Mono<BrokenLinkScanStatus> startScan();

    /**
     * 获取扫描状态
     * 
     * @return 扫描状态
     */
    Mono<BrokenLinkScanStatus> getStatus();

    /**
     * 获取断链列表
     *
     * @param page 页码
     * @param size 每页数量
     * @return 断链列表
     */
    Mono<ListResult<BrokenLinkVo>> listBrokenLinks(int page, int size);

    /**
     * 获取断链列表（支持筛选和搜索）
     *
     * @param page 页码
     * @param size 每页数量
     * @param sourceType 来源类型过滤（可选）
     * @param keyword 搜索关键词（可选）
     * @return 断链列表
     */
    Mono<ListResult<BrokenLinkVo>> listBrokenLinks(int page, int size, String sourceType, String keyword);

    /**
     * 获取断链列表（支持筛选、搜索和排序）
     *
     * @param page 页码
     * @param size 每页数量
     * @param sourceType 来源类型过滤（可选）
     * @param keyword 搜索关键词（可选）
     * @param sort 排序参数，格式：field,asc|desc（可选）
     * @return 断链列表
     */
    Mono<ListResult<BrokenLinkVo>> listBrokenLinks(int page, int size, String sourceType, String keyword, String sort);

    /**
     * 获取所有来源类型（用于前端筛选下拉框）
     *
     * @return 来源类型列表
     */
    Mono<java.util.List<String>> getSourceTypes();

    /**
     * 添加 URL 到忽略白名单
     *
     * @param urls URL 列表
     * @return 完成信号
     */
    Mono<Void> addToWhitelist(java.util.List<String> urls);

    /**
     * 清空扫描结果
     * 
     * @return 完成信号
     */
    Mono<Void> clearAll();
}
