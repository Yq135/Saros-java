package com.kairon.saros.po;

import java.time.OffsetDateTime;

/**
 * 表对象：tags（正式标签，仅关联手打笔记；同一笔记下标签名不重复）。
 */
public class Tag {

    public Long id;
    public long manualKnowledgeId;
    public String name;
    public OffsetDateTime createdAt;
}
