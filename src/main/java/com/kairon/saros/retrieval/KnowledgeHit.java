package com.kairon.saros.retrieval;

import java.util.List;

/**
 * 混合检索命中（对齐阶段二 retrieve_knowledge 返回结构）：
 * similarity 为 KNN 余弦（round 4 位），score 为 0.6/0.3/0.15 加权分（round 4 位）。
 */
public record KnowledgeHit(long id, String content, double similarity, double score, List<String> tags) {
}
