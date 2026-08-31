package com.kairon.saros.po;

import com.pgvector.PGvector;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 表对象：embeddings（统一向量检索入口，当前仅嵌入手打笔记 source_type='MANUAL'，
 * 维度 512 与 bge-small-zh-v1.5 一致）。
 */
@Data
public class Embedding {

    private Long id;
    private long userId;
    private String sourceType;
    private long sourceId;
    private String chunkContent;
    private PGvector embedding;
    private OffsetDateTime createdAt;
}
