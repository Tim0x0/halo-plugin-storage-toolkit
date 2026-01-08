package com.timxs.storagetoolkit.endpoint;

import com.timxs.storagetoolkit.model.StatisticsData;
import com.timxs.storagetoolkit.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ApiVersion;

/**
 * 存储统计 REST API 端点
 * 提供存储空间统计数据
 */
@ApiVersion("console.api.storage-toolkit.timxs.com/v1alpha1")
@RestController
@RequestMapping("/statistics")
@RequiredArgsConstructor
public class StatisticsEndpoint {

    private final StatisticsService statisticsService;

    /**
     * 获取存储统计数据
     * 包括按类型、策略、分组的聚合统计
     *
     * @return 统计数据
     */
    @GetMapping
    public Mono<StatisticsData> getStatistics() {
        return statisticsService.getStatistics();
    }
}
