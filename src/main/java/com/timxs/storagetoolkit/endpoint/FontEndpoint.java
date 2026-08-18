package com.timxs.storagetoolkit.endpoint;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.ApiVersion;

import java.awt.*;
import java.util.Arrays;
import java.util.List;

/**
 * 服务器字体 REST API 端点
 * 供设置页字体下拉框拉取 JVM 当前可用的全部字体
 * 注意：JVM 仅在启动时扫描系统字体，运行中新安装的字体需重启后才会出现在列表中
 */
@Slf4j
@ApiVersion("console.api.storage-toolkit.timxs.com/v1alpha1")
@RestController
@RequestMapping("/fonts")
public class FontEndpoint {

    /** 用于检测字体是否支持中文的样例字符 */
    private static final String CHINESE_SAMPLE = "中文水印";

    /**
     * 列出服务器可用字体
     * 返回全部字体家族名（不按语言过滤），支持中文的字体在名称后标注；
     * 一次性全量返回（total 等于 size），前端下拉组件会缓存并本地搜索
     *
     * @return 字体选项列表
     */
    @GetMapping
    public Mono<FontListResult> listFonts() {
        // 首次调用可能触发字体扫描（阻塞），放入弹性线程池执行
        return Mono.fromCallable(this::doListFonts)
            .subscribeOn(Schedulers.boundedElastic());
    }

    private FontListResult doListFonts() {
        String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getAvailableFontFamilyNames();
        List<FontOption> items = Arrays.stream(families)
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .map(family -> {
                boolean supportsChinese = new Font(family, Font.PLAIN, 12)
                    .canDisplayUpTo(CHINESE_SAMPLE) == -1;
                String label = supportsChinese ? family + "（支持中文）" : family;
                return new FontOption(label, family);
            })
            .toList();
        log.debug("服务器可用字体数量: {}", items.size());
        return new FontListResult(items, 1, Math.max(items.size(), 1), items.size());
    }

    /**
     * 字体选项，label 用于显示（支持中文的字体带标注），value 为字体家族名
     */
    public record FontOption(String label, String value) {
    }

    /**
     * 字体列表结果，结构与前端下拉组件的默认解析字段（items/page/size/total）对应
     */
    public record FontListResult(List<FontOption> items, int page, int size, int total) {
    }
}
