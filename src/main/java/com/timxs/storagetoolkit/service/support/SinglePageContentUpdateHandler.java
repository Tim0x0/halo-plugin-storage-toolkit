package com.timxs.storagetoolkit.service.support;

import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * SinglePage 内容更新处理器
 * 处理独立页面内容和封面图的 URL 替换
 */
@Component
public class SinglePageContentUpdateHandler extends AbstractSnapshotContentUpdateHandler<SinglePage> {

    public SinglePageContentUpdateHandler(ReactiveExtensionClient client) {
        super(client);
    }

    @Override
    public String getSourceType() {
        return "SinglePage";
    }

    @Override
    protected Class<SinglePage> getEntityClass() {
        return SinglePage.class;
    }

    @Override
    protected String getCover(SinglePage entity) {
        return entity.getSpec().getCover();
    }

    @Override
    protected void setCover(SinglePage entity, String cover) {
        entity.getSpec().setCover(cover);
    }

    @Override
    protected String getHeadSnapshot(SinglePage entity) {
        return entity.getSpec().getHeadSnapshot();
    }

    @Override
    protected void setHeadSnapshot(SinglePage entity, String snapshotName) {
        entity.getSpec().setHeadSnapshot(snapshotName);
    }

    @Override
    protected String getBaseSnapshot(SinglePage entity) {
        return entity.getSpec().getBaseSnapshot();
    }

    @Override
    protected String getReleaseSnapshot(SinglePage entity) {
        return entity.getSpec().getReleaseSnapshot();
    }

    @Override
    protected void setReleaseSnapshot(SinglePage entity, String snapshotName) {
        entity.getSpec().setReleaseSnapshot(snapshotName);
    }

    @Override
    protected String getSubjectRefKind() {
        return "SinglePage";
    }
}
