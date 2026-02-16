package com.timxs.storagetoolkit.service;

import com.timxs.storagetoolkit.extension.BrokenLinkScanStatus;
import com.timxs.storagetoolkit.model.BrokenLinkReplaceResult;
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
     * 获取断链列表（支持筛选、搜索和排序）
     *
     * @param page 页码
     * @param size 每页数量
     * @param sourceType 来源类型过滤（可选）
     * @param keyword 搜索关键词（可选）
     * @param reason 断链原因过滤（可选），支持 HTTP_ERROR、HTTP_TIMEOUT、CONNECTION_FAILED、ATTACHMENT_NOT_FOUND
     * @param sort 排序参数，格式：field,asc|desc（可选）
     * @return 断链列表
     */
    Mono<ListResult<BrokenLinkVo>> listBrokenLinks(int page, int size, String sourceType, String keyword, String reason, String sort);

    /**
     * 清空扫描结果
     *
     * @return 完成信号
     */
    Mono<Void> clearAll();

    /**
     * 替换断链
     * 将断链 URL 替换为新 URL
     *
     * @param oldUrl 断链 URL
     * @param newUrl 新 URL
     * @return 替换结果
     */
    Mono<BrokenLinkReplaceResult> replaceBrokenLink(String oldUrl, String newUrl);
}
