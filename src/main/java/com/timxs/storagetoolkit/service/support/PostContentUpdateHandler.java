package com.timxs.storagetoolkit.service.support;

import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * Post 内容更新处理器
 * 处理文章内容和封面图的 URL 替换
 */
@Component
public class PostContentUpdateHandler extends AbstractSnapshotContentUpdateHandler<Post> {

    public PostContentUpdateHandler(ReactiveExtensionClient client) {
        super(client);
    }

    @Override
    public String getSourceType() {
        return "Post";
    }

    @Override
    protected Class<Post> getEntityClass() {
        return Post.class;
    }

    @Override
    protected String getCover(Post entity) {
        return entity.getSpec().getCover();
    }

    @Override
    protected void setCover(Post entity, String cover) {
        entity.getSpec().setCover(cover);
    }

    @Override
    protected String getHeadSnapshot(Post entity) {
        return entity.getSpec().getHeadSnapshot();
    }

    @Override
    protected void setHeadSnapshot(Post entity, String snapshotName) {
        entity.getSpec().setHeadSnapshot(snapshotName);
    }

    @Override
    protected String getBaseSnapshot(Post entity) {
        return entity.getSpec().getBaseSnapshot();
    }

    @Override
    protected String getReleaseSnapshot(Post entity) {
        return entity.getSpec().getReleaseSnapshot();
    }

    @Override
    protected void setReleaseSnapshot(Post entity, String snapshotName) {
        entity.getSpec().setReleaseSnapshot(snapshotName);
    }

    @Override
    protected String getSubjectRefKind() {
        return "Post";
    }
}
