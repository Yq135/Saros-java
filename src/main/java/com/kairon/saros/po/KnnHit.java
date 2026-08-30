package com.kairon.saros.po;

/**
 * KNN 查询投影（embeddings 列 + 计算列 similarity，非纯表对象）：
 * EmbeddingMapper.knnSearch 的返回行，1 - (embedding <=> query) 为余弦相似度。
 */
public class KnnHit {

    public long sourceId;
    public String chunkContent;
    public double similarity;
}
