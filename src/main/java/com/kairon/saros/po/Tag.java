package com.kairon.saros.po;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 表对象：tags（正式标签，仅关联手打笔记；同一笔记下标签名不重复）。
 */
@Data
public class Tag {

    private Long id;
    private long manualKnowledgeId;
    private String name;
    private OffsetDateTime createdAt;
}
