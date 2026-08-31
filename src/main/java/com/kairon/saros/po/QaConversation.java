package com.kairon.saros.po;

import java.time.OffsetDateTime;

/**
 * 表对象：qa_conversations（问答会话，标题取首问前 30 字截断）。
 */
public class QaConversation {

    public long id;
    public long userId;
    public String title;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
