package com.timxs.storagetoolkit.service.support;

import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * Reply 内容更新处理器
 */
@Component
public class ReplyContentUpdateHandler extends AbstractSimpleContentUpdateHandler<Reply> {

    public ReplyContentUpdateHandler(ReactiveExtensionClient client) {
        super(client);
    }

    @Override
    public String getSourceType() {
        return "Reply";
    }

    @Override
    protected Class<Reply> getEntityClass() {
        return Reply.class;
    }

    @Override
    protected String getContent(Reply entity) {
        return entity.getSpec().getContent();
    }

    @Override
    protected void setContent(Reply entity, String content) {
        entity.getSpec().setContent(content);
    }

    @Override
    protected String getRaw(Reply entity) {
        return entity.getSpec().getRaw();
    }

    @Override
    protected void setRaw(Reply entity, String raw) {
        entity.getSpec().setRaw(raw);
    }

    @Override
    protected String getEntityName(Reply entity) {
        return entity.getMetadata().getName();
    }
}
