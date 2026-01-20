package com.timxs.storagetoolkit.service;

import com.timxs.storagetoolkit.extension.AttachmentReference;
import com.timxs.storagetoolkit.extension.ReferenceScanStatus;
import com.timxs.storagetoolkit.model.CleanupResult;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;

/**
 * 引用扫描服务接口
 * 提供附件引用扫描和查询功能
 */
public interface ReferenceService {

    /**
     * 触发全量扫描
     * 扫描所有内容（文章、页面、评论等），建立附件引用关系
     *
     * @return 扫描状态
     */
    Mono<ReferenceScanStatus> startScan();

    /**
     * 获取扫描状态和统计数据
     *
     * @return 扫描状态
     */
    Mono<ReferenceScanStatus> getScanStatus();

    /**
     * 获取附件引用列表
     *
     * @param query 查询参数
     * @return 分页结果
     */
    Mono<ListResult<AttachmentReferenceVo>> listReferences(ReferenceQuery query);

    /**
     * 获取单个附件的引用详情
     *
     * @param attachmentName 附件名称
     * @return 引用详情
     */
    Mono<AttachmentReferenceVo> getReference(String attachmentName);

    /**
     * 更新引用源信息（详情弹窗查询后更新）
     *
     * @param attachmentName 附件名称
     * @param sourceName 引用源名称
     * @param sourceTitle 新的标题
     * @param sourceUrl 新的 URL
     * @return 更新后的引用详情
     */
    Mono<AttachmentReferenceVo> updateReferenceSource(String attachmentName, String sourceName, 
                                                       String sourceTitle, String sourceUrl);

    /**
     * 解析文档信息（从 DocTree 获取标题和链接，通过 Doc name 查找）
     *
     * @param docName 文档名称
     * @return 文档信息（标题和链接）
     */
    Mono<SubjectInfo> resolveDocInfo(String docName);

    /**
     * 解析文档信息（直接通过 DocTree name 查找）
     *
     * @param docTreeName DocTree 名称
     * @return 文档信息（标题和链接）
     */
    Mono<SubjectInfo> resolveDocTreeInfo(String docTreeName);

    /**
     * 清空所有引用记录和扫描状态
     *
     * @return 完成信号
     */
    Mono<Void> clearAll();

    /**
     * 删除未引用附件
     *
     * @param attachmentNames 要删除的附件名称列表
     * @return 删除结果
     */
    Mono<CleanupResult> deleteUnreferenced(java.util.List<String> attachmentNames);

    /**
     * 获取 Setting group 的显示标签
     *
     * @param settingName Setting 的 metadata.name
     * @param groupKey group 的 key
     * @return group 的显示标签
     */
    Mono<String> getSettingGroupLabel(String settingName, String groupKey);

    /**
     * 附件引用查询参数
     */
    record ReferenceQuery(
        String filter,      // all / referenced / unreferenced
        String keyword,     // 文件名搜索关键词
        int page,           // 页码（从 1 开始）
        int size,           // 每页数量
        String sort         // 排序字段和方向，如 referenceCount,desc
    ) {
        public static ReferenceQuery of(String filter, String keyword, int page, int size, String sort) {
            return new ReferenceQuery(
                filter != null ? filter : "all",
                keyword,
                page > 0 ? page : 1,
                size > 0 ? size : 20,
                sort
            );
        }
    }

    /**
     * 附件引用视图对象
     * 包含附件基本信息和引用信息
     */
    record AttachmentReferenceVo(
        String attachmentName,
        String displayName,
        String mediaType,
        long size,
        String permalink,
        String policyName,
        String groupName,
        int referenceCount,
        java.util.List<AttachmentReference.ReferenceSource> references
    ) {}

    /**
     * 引用源信息（标题和链接）
     */
    record SubjectInfo(String title, String url) {}
}
