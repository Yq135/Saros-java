package com.kairon.saros.search;

/**
 * 搜索结果（对齐阶段二 search.py SearchResult：title/url/snippet 三字段）。
 */
public record SearchSource(String title, String url, String snippet) {
}
