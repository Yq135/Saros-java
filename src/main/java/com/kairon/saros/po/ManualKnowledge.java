package com.kairon.saros.po;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 表对象：manual_knowledge（手打知识，正式标签的唯一宿主；向量存 embeddings 表）。
 */
@Data
public class ManualKnowledge {

    private long id;
    private long userId;
    private String content;
    private int masteryLevel;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
