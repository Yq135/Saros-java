package com.kairon.saros.retrieval;

import com.kairon.saros.embed.EmbeddingService;
import com.kairon.saros.mapper.EmbeddingMapper;
import com.kairon.saros.mapper.ManualKnowledgeMapper;
import com.kairon.saros.mapper.TagMapper;
import com.kairon.saros.po.KnnHit;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 混合检索（对齐阶段二 qa_service.py retrieve_knowledge）：
 * KNN 取 50 候选（仅手打笔记，cosine）→ 补笔记当前全文与正式标签
 * → 0.6/0.3/0.15 加权打分 → 阈值 0.35 过滤 → score 降序取 top N。
 *
 * <p>嵌入/查询失败向上抛（阶段二同款：外层兜底为 error 事件，不静默降级）。
 */
@Service
public class HybridRetriever {

    /** KNN 候选数（对齐阶段二 KNN_CANDIDATES）。 */
    static final int KNN_CANDIDATES = 50;
    /** 引用沉淀条数上限（对齐阶段二 KNOWLEDGE_TOP）。 */
    public static final int KNOWLEDGE_TOP = 5;

    @Resource
    private EmbeddingMapper embeddingMapper;

    @Resource
    private EmbeddingService embeddingService;

    @Resource
    private ManualKnowledgeMapper knowledgeMapper;

    @Resource
    private TagMapper tagMapper;

    @Resource
    private HybridRanker ranker;

    public List<KnowledgeHit> retrieve(String question, long userId, int topN) {
        List<KnnHit> candidates = embeddingMapper.knnSearch(
                userId, embeddingService.toPgVector(embeddingService.encodeQuery(question)), KNN_CANDIDATES);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<String> tokens = ranker.tokens(question);
        List<Long> ids = candidates.stream().map(KnnHit::getSourceId).distinct().toList();
        Map<Long, String> contentMap = knowledgeMapper.findByIds(ids, userId).stream()
                .collect(Collectors.toMap(k -> k.getId(), k -> k.getContent(), (a, b) -> a));
        Map<Long, List<String>> tagsMap = tagMapper.findTagsByKnowledgeIds(ids).stream()
                .collect(Collectors.groupingBy(t -> t.getManualKnowledgeId(),
                        Collectors.mapping(t -> t.getName(), Collectors.toList())));

        List<KnowledgeHit> scored = new ArrayList<>();
        for (KnnHit c : candidates) {
            long kid = c.getSourceId();
            // 笔记已删的候选仍参与打分：用 chunk 内容、tags=[]（对齐阶段二）
            String content = contentMap.getOrDefault(kid, c.getChunkContent());
            List<String> tags = tagsMap.getOrDefault(kid, List.of());
            double score = ranker.score(c.getSimilarity(), tokens, content, tags);
            if (ranker.aboveThreshold(score)) {
                scored.add(new KnowledgeHit(kid, content, round4(c.getSimilarity()), round4(score), tags));
            }
        }
        scored.sort(Comparator.comparingDouble(KnowledgeHit::score).reversed());
        return scored.stream().limit(topN).toList();
    }

    /** round(x, 4)（对齐阶段二输出精度）。 */
    static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
