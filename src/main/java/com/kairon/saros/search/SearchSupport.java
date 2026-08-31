package com.kairon.saros.search;

import org.jsoup.Connection;
import org.jsoup.Jsoup;

/**
 * 搜索抓取公共参数（对齐阶段二 search.py：UA、10s 超时、title 200 / snippet 500 截断）。
 */
final class SearchSupport {

    static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    static final int TIMEOUT_MS = 10_000;
    static final int TITLE_MAX = 200;
    static final int SNIPPET_MAX = 500;

    private SearchSupport() {
    }

    static Connection connect(String url) {
        return Jsoup.connect(url).userAgent(USER_AGENT).timeout(TIMEOUT_MS).followRedirects(true);
    }

    /** 硬截断（无省略号），对齐阶段二 [:200] / [:500]。 */
    static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
