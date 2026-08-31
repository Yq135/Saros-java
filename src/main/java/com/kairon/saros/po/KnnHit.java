package com.kairon.saros.po;

import lombok.Data;

/**
 * KNN 查询投影（embeddings 列 + 计算列 similarity，非纯表对象）：
 * EmbeddingMapper.knnSearch 的返回行，1 - (embedding <=> query) 为余弦相似度。
 */
@Data
public class KnnHit {

    private long sourceId;
    private String chunkContent;
    private double similarity;
}
