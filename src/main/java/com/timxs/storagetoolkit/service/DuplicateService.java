package com.timxs.storagetoolkit.service;

import com.timxs.storagetoolkit.extension.DuplicateScanStatus;
import com.timxs.storagetoolkit.model.CleanupResult;
import com.timxs.storagetoolkit.model.DuplicateGroupVo;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;

/**
 * 重复检测服务接口
 */
public interface DuplicateService {

    /**
     * 触发重复检测扫描
     * @return 扫描状态
     */
    Mono<DuplicateScanStatus> startScan();

    /**
     * 获取扫描状态和统计数据
     * @return 扫描状态
     */
    Mono<DuplicateScanStatus> getScanStatus();

    /**
     * 获取重复组列表
     * @param page 页码（从 1 开始）
     * @param size 每页数量
     * @return 重复组列表
     */
    Mono<ListResult<DuplicateGroupVo>> listDuplicateGroups(int page, int size);

    /**
     * 清空所有重复检测记录和扫描状态
     * @return 完成信号
     */
    Mono<Void> clearAll();

    /**
     * 删除重复文件
     * @param groupMd5 重复组 MD5
     * @param attachmentNames 要删除的附件名称列表
     * @return 删除结果
     */
    Mono<CleanupResult> deleteDuplicates(String groupMd5, java.util.List<String> attachmentNames);
}
