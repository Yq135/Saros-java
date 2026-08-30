package com.kairon.saros.embed;

import com.pgvector.PGvector;
import org.springframework.stereotype.Service;

/**
 * 嵌入门面：文档/查询编码 + PgVector 包装（对应阶段二 embeddings.py + vector_store.py）。
 */
@Service
public class EmbeddingService {

    private final OnnxEmbedder embedder;

    public EmbeddingService(OnnxEmbedder embedder) {
        this.embedder = embedder;
    }

    public float[] encodeText(String text) {
        return embedder.encodeText(text);
    }

    public float[] encodeQuery(String query) {
        return embedder.encodeQuery(query);
    }

    public PGvector toPgVector(float[] vector) {
        return new PGvector(vector);
    }
}
