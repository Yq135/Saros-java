package com.kairon.saros.po;

import com.pgvector.PGvector;

import java.time.OffsetDateTime;

/**
 * 表对象：embeddings（统一向量检索入口，当前仅嵌入手打笔记 source_type='MANUAL'，
 * 维度 512 与 bge-small-zh-v1.5 一致）。
 */
public class Embedding {

    public Long id;
    public long userId;
    public String sourceType;
    public long sourceId;
    public String chunkContent;
    public PGvector embedding;
    public OffsetDateTime createdAt;
}
