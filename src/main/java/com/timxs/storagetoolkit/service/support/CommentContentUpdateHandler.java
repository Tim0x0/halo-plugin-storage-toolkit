package com.timxs.storagetoolkit.service.support;

import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * Comment 内容更新处理器
 */
@Component
public class CommentContentUpdateHandler extends AbstractSimpleContentUpdateHandler<Comment> {

    public CommentContentUpdateHandler(ReactiveExtensionClient client) {
        super(client);
    }

    @Override
    public String getSourceType() {
        return "Comment";
    }

    @Override
    protected Class<Comment> getEntityClass() {
        return Comment.class;
    }

    @Override
    protected String getContent(Comment entity) {
        return entity.getSpec().getContent();
    }

    @Override
    protected void setContent(Comment entity, String content) {
        entity.getSpec().setContent(content);
    }

    @Override
    protected String getRaw(Comment entity) {
        return entity.getSpec().getRaw();
    }

    @Override
    protected void setRaw(Comment entity, String raw) {
        entity.getSpec().setRaw(raw);
    }

    @Override
    protected String getEntityName(Comment entity) {
        return entity.getMetadata().getName();
    }
}
