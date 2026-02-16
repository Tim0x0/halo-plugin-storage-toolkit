package com.timxs.storagetoolkit.service;

import com.timxs.storagetoolkit.endpoint.BatchProcessingEndpoint.SettingsResponse;
import com.timxs.storagetoolkit.extension.BatchProcessingStatus;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 批量处理服务接口
 * 负责批量处理任务的创建、执行、取消和状态管理
 */
public interface BatchProcessingService {

    /**
     * 创建批量处理任务
     *
     * @param attachmentNames 待处理的附件名称列表
     * @param replaceReferences 是否替换引用
     * @return 任务状态
     */
    Mono<BatchProcessingStatus> createTask(List<String> attachmentNames, boolean replaceReferences);

    /**
     * 取消当前任务
     * 
     * @return 任务状态
     */
    Mono<BatchProcessingStatus> cancelTask();

    /**
     * 获取当前任务状态
     * 
     * @return 任务状态
     */
    Mono<BatchProcessingStatus> getStatus();

    /**
     * 获取批量处理设置
     *
     * @return 设置响应
     */
    Mono<SettingsResponse> getSettings();
}
