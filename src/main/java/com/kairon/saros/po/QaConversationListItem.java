package com.kairon.saros.po;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 查询投影（qa_conversations JOIN qa_messages 聚合行，非纯表对象）：
 * QaConversationMapper.listWithCount 的返回行。last_active = MAX(m.created_at)（最近活跃），
 * message_count = COUNT(m.id)，对齐阶段二 qa.py 列表 SQL。
 */
@Data
public class QaConversationListItem {

    private long id;
    private String title;
    private long messageCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime lastActive;
}
