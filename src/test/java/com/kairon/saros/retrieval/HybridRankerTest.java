package com.kairon.saros.retrieval;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 混合打分单测（对齐阶段二 test_qa.py TestHybridScore）：
 * jieba 词长 ≥2 过滤、交集/问题词集、0.6/0.3/0.15 加权、0.35 阈值边界。
 */
class HybridRankerTest {

    // 单测不启 Spring 容器：无参构造 + ReflectionTestUtils 注入 @Resource 依赖
    private final LexicalScorer lexical = new LexicalScorer();
    private final TagScorer tagScorer = new TagScorer();
    private final HybridRanker ranker = new HybridRanker();

    {
        ReflectionTestUtils.setField(lexical, "segmenter", new JiebaSegmenter());
        ReflectionTestUtils.setField(tagScorer, "lexical", lexical);
        ReflectionTestUtils.setField(ranker, "lexical", lexical);
        ReflectionTestUtils.setField(ranker, "tagScorer", tagScorer);
    }

    @Test
    void tokensKeepOnlyWordsOfLengthAtLeastTwo() {
        // 「装饰器」jieba 切为「装饰」+「器」，单字「器」被过滤
        Set<String> tokens = ranker.tokens("装饰器是什么");
        assertThat(tokens).contains("装饰");
        assertThat(tokens).doesNotContain("器");
        assertThat(tokens).allMatch(w -> w.length() >= 2);
    }

    @Test
    void lexOverlapIsIntersectionOverQuestionTokens() {
        Set<String> tokens = Set.of("装饰", "Python");
        // 内容只含其一 → 0.5；两者都在（「装饰器」jieba 切出「装饰」）→ 1.0；均无 → 0
        assertThat(lexical.lexOverlap(tokens, "Python 是一种编程语言")).isCloseTo(0.5, within(1e-9));
        assertThat(lexical.lexOverlap(tokens, "Python 的装饰器是一种语法糖")).isCloseTo(1.0, within(1e-9));
        assertThat(lexical.lexOverlap(Set.of(), "任意内容")).isZero();
        assertThat(lexical.lexOverlap(tokens, "红烧肉的做法")).isZero();
    }

    @Test
    void tagHitTokenizesJoinedTagNames() {
        Set<String> tokens = Set.of("装饰");
        assertThat(tagScorer.tagHit(tokens, List.of("Python 装饰器"))).isEqualTo(1.0);
        assertThat(tagScorer.tagHit(tokens, List.of("物理"))).isZero();
        assertThat(tagScorer.tagHit(tokens, List.of())).isZero();
    }

    @Test
    void scoreUsesWeightedFormulaAndThresholdBoundary() {
        Set<String> tokens = Set.of("装饰");
        // 内容/标签均无重叠 → 0.6 * cosine
        assertThat(ranker.score(0.5, tokens, "红烧肉做法", List.of("美食"))).isCloseTo(0.3, within(1e-9));
        // 标签全命中 → +0.15
        assertThat(ranker.score(0.5, tokens, "红烧肉做法", List.of("装饰"))).isCloseTo(0.45, within(1e-9));
        // 内容全命中 → +0.3
        assertThat(ranker.score(0.5, tokens, "装饰是重要的语法", List.of())).isCloseTo(0.6, within(1e-9));

        assertThat(ranker.aboveThreshold(0.35)).isTrue();
        assertThat(ranker.aboveThreshold(0.3499)).isFalse();
    }

    @Test
    void round4RoundsToFourDecimals() {
        assertThat(HybridRetriever.round4(0.123456)).isEqualTo(0.1235);
        assertThat(HybridRetriever.round4(0.5)).isEqualTo(0.5);
    }
}
