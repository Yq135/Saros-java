package com.kairon.saros.po;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 表对象：qa_conversations（问答会话，标题取首问前 30 字截断）。
 */
@Data
public class QaConversation {

    private long id;
    private long userId;
    private String title;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
