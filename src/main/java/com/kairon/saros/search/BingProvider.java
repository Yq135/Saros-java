package com.kairon.saros.search;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Bing 兜底源（结果页 jsoup 抓取，li.b_algo 块内 h2&gt;a 标题 + p 摘要；对齐阶段二正则语义）。
 */
public final class BingProvider implements SearchProvider {

    @Override
    public String name() {
        return "bing";
    }

    @Override
    public List<SearchSource> search(String query, int maxResults) throws IOException {
        Document doc = SearchSupport.connect("https://www.bing.com/search")
                .data("q", query)
                .get();
        List<SearchSource> results = new ArrayList<>();
        for (Element block : doc.select("li.b_algo")) {
            Element a = block.selectFirst("h2 a[href]");
            if (a == null) {
                continue;
            }
            String title = SearchSupport.truncate(a.text(), SearchSupport.TITLE_MAX);
            if (title.isBlank()) {
                continue;   // 对齐阶段二：标题空则跳过
            }
            Element p = block.selectFirst("p");
            String snippet = SearchSupport.truncate(p == null ? "" : p.text(), SearchSupport.SNIPPET_MAX);
            results.add(new SearchSource(title, a.attr("href"), snippet));
            if (results.size() >= maxResults) {
                break;
            }
        }
        return results;
    }
}
