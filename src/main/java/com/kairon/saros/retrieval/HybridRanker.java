package com.kairon.saros.retrieval;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 混合打分（对齐阶段二 hybrid_score）：
 * {@code score = 0.6*cosine + 0.3*lex_overlap + 0.15*tag_hit}，阈值 0.35。
 */
@Component
public class HybridRanker {

    static final double W_COSINE = 0.6;
    static final double W_LEX = 0.3;
    static final double W_TAG = 0.15;
    /** 低于该阈值不引沉淀（避免噪音）。 */
    static final double SCORE_THRESHOLD = 0.35;

    @Resource
    private LexicalScorer lexical;

    @Resource
    private TagScorer tagScorer;

    public Set<String> tokens(String question) {
        return lexical.tokens(question);
    }

    public double score(double similarity, Set<String> tokens, String content, List<String> tags) {
        return W_COSINE * similarity
                + W_LEX * lexical.lexOverlap(tokens, content)
                + W_TAG * tagScorer.tagHit(tokens, tags);
    }

    public boolean aboveThreshold(double score) {
        return score >= SCORE_THRESHOLD;
    }
}
