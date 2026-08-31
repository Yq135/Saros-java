package com.kairon.saros.retrieval;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 标签命中打分（对齐阶段二 tag_hit）：标签名拼接后 jieba 分词，
 * 与问题词集取交集 / 问题词集。
 */
@Component
public class TagScorer {

    @Resource
    private LexicalScorer lexical;

    public double tagHit(Set<String> tokens, List<String> tagNames) {
        if (tokens.isEmpty() || tagNames == null || tagNames.isEmpty()) {
            return 0.0;
        }
        Set<String> tagTokens = lexical.tokens(String.join(" ", tagNames));
        long overlap = tokens.stream().filter(tagTokens::contains).count();
        return (double) overlap / tokens.size();
    }
}
