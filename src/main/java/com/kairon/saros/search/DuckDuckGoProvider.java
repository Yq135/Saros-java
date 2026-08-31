package com.kairon.saros.search;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * DuckDuckGo 主力源（HTML 端点 jsoup 抓取）。
 *
 * <p>阶段二用 ddgs 库（其内部亦走 DDG 接口），Java 侧按 PLAN.md 采用
 * html.duckduckgo.com 抓取，结果集不保证与 ddgs 逐条一致属预期。
 */
public final class DuckDuckGoProvider implements SearchProvider {

    @Override
    public String name() {
        return "ddgs";
    }

    @Override
    public List<SearchSource> search(String query, int maxResults) throws IOException {
        Document doc = SearchSupport.connect("https://html.duckduckgo.com/html/")
                .data("q", query)
                .get();
        List<SearchSource> results = new ArrayList<>();
        for (Element block : doc.select("div.result")) {
            Element a = block.selectFirst("a.result__a");
            if (a == null) {
                continue;
            }
            String title = SearchSupport.truncate(a.text(), SearchSupport.TITLE_MAX);
            String url = unwrapDdgUrl(a.attr("href"));
            if (title.isBlank() || url.isBlank()) {
                continue;
            }
            Element snippetEl = block.selectFirst("a.result__snippet");
            String snippet = SearchSupport.truncate(
                    snippetEl == null ? "" : snippetEl.text(), SearchSupport.SNIPPET_MAX);
            results.add(new SearchSource(title, url, snippet));
            if (results.size() >= maxResults) {
                break;
            }
        }
        return results;
    }

    /** DDG HTML 端点返回 //duckduckgo.com/l/?uddg=... 跳转链接，解出真实目标 URL。 */
    static String unwrapDdgUrl(String href) {
        if (href == null) {
            return "";
        }
        int p = href.indexOf("uddg=");
        if (p < 0) {
            return href;
        }
        String encoded = href.substring(p + "uddg=".length());
        int end = encoded.indexOf('&');
        if (end >= 0) {
            encoded = encoded.substring(0, end);
        }
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}
