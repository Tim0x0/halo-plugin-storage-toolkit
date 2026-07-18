package com.timxs.storagetoolkit.service.support;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 引用替换串行化协调器
 *
 * 问题：批量处理时同一篇内容引用的多张图片会各自触发一次引用替换，
 * 并发执行会互相覆盖（后完成的实体更新会覆盖先完成任务的快照指向）。
 *
 * 解决方案：按 sourceName 将替换任务链接成串行链，后提交的任务等待前一个完成后再执行。
 *
 * 关键点：
 * - 存入 map 的任务经过 cache()，后续任务链接时只等待其完成信号，不会重新触发执行；
 * - 前序任务失败不阻断后续任务（错误被吞掉后继续执行本任务）；
 * - 任务终止时若链末端仍是自己（没有新任务排队），自动从 map 清理，避免泄漏。
 */
@Slf4j
public class ReferenceReplacementCoordinator {

    /** sourceName -> 该内容当前串行链末端的任务（已 cache，可安全被后续任务链接） */
    private final ConcurrentHashMap<String, Mono<Void>> pendingTasks = new ConcurrentHashMap<>();

    /**
     * 串行化执行替换任务
     *
     * @param sourceName 内容名称（如 post-xxx）
     * @param task 替换任务（冷 Mono，由返回值的订阅方触发执行）
     * @return 排队执行的 Mono，完成即表示本任务执行完毕
     */
    public Mono<Void> submit(String sourceName, Mono<Void> task) {
        if (sourceName == null || sourceName.isEmpty()) {
            return task;
        }

        AtomicReference<Mono<Void>> selfRef = new AtomicReference<>();
        return pendingTasks.compute(sourceName, (key, existingTask) -> {
            Mono<Void> chained;
            if (existingTask == null) {
                chained = task;
            } else {
                log.debug("引用替换任务排队：sourceName={}，等待前一个任务完成", sourceName);
                // existingTask 已 cache，这里只等待其完成信号；前序失败不阻断本任务
                chained = existingTask.onErrorResume(e -> Mono.empty()).then(task);
            }
            Mono<Void> shared = chained
                .doFinally(signal -> pendingTasks.remove(key, selfRef.get()))
                .cache();
            selfRef.set(shared);
            return shared;
        });
    }

    /**
     * 检查是否有正在处理的任务
     *
     * @param sourceName 内容名称
     * @return 是否有正在处理的任务
     */
    public boolean hasPendingTask(String sourceName) {
        return pendingTasks.containsKey(sourceName);
    }

    /**
     * 获取正在处理的任务数量
     *
     * @return 正在处理的任务数量
     */
    public int getPendingTaskCount() {
        return pendingTasks.size();
    }
}
