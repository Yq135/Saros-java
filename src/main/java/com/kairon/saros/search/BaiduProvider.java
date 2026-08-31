package com.kairon.saros.search;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 百度兜底源（结果页 jsoup 抓取，div.result 块内 h3&gt;a 标题 + content-right_ 摘要；
 * 对齐阶段二正则语义；百度 href 为跳转链接，与阶段二行为一致）。
 */
public final class BaiduProvider implements SearchProvider {

    @Override
    public String name() {
        return "baidu";
    }

    @Override
    public List<SearchSource> search(String query, int maxResults) throws IOException {
        Document doc = SearchSupport.connect("https://www.baidu.com/s")
                .data("wd", query)
                .get();
        List<SearchSource> results = new ArrayList<>();
        for (Element block : doc.select("div.result.c-container")) {
            Element a = block.selectFirst("h3 a[href]");
            if (a == null) {
                continue;
            }
            String title = SearchSupport.truncate(a.text(), SearchSupport.TITLE_MAX);
            String url = a.attr("href");
            if (title.isBlank() || url.isBlank()) {
                continue;
            }
            Element span = block.selectFirst("span[class^=content-right_]");
            String snippet = SearchSupport.truncate(span == null ? "" : span.text(), SearchSupport.SNIPPET_MAX);
            results.add(new SearchSource(title, url, snippet));
            if (results.size() >= maxResults) {
                break;
            }
        }
        return results;
    }
}
