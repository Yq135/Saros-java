package com.kairon.saros.retrieval;

import com.huaban.analysis.jieba.JiebaSegmenter;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 关键词重叠打分（对齐阶段二 question_tokens / lex_overlap）：
 * jieba 精确模式分词、词长 ≥ 2 保留（单字噪声大）、交集 / 问题词集。
 */
@Component
public class LexicalScorer {

    @Resource
    private JiebaSegmenter segmenter;

    /** jieba lcut + 词长 ≥ 2 过滤（对齐 Python len(w.strip()) &gt;= 2）。 */
    public Set<String> tokens(String text) {
        return segmenter.sentenceProcess(text).stream()
                .map(String::strip)
                .filter(w -> w.length() >= 2)
                .collect(Collectors.toSet());
    }

    public double lexOverlap(Set<String> tokens, String content) {
        if (tokens.isEmpty()) {
            return 0.0;
        }
        Set<String> textTokens = tokens(content);
        long overlap = tokens.stream().filter(textTokens::contains).count();
        return (double) overlap / tokens.size();
    }
}
