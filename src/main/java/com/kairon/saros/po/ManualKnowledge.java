package com.kairon.saros.po;

import java.time.OffsetDateTime;

/**
 * 表对象：manual_knowledge（手打知识，正式标签的唯一宿主；向量存 embeddings 表）。
 */
public class ManualKnowledge {

    public long id;
    public long userId;
    public String content;
    public int masteryLevel;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
