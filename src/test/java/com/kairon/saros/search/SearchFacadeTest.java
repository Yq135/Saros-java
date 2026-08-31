package com.kairon.saros.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 搜索门面单测（fake providers，无网络）：合并顺序、URL 去重 first-wins、
 * 凑满即停、单源失败降级、全挂返空、未知源忽略（对齐阶段二 search_web 语义）。
 */
class SearchFacadeTest {

    private static SearchProvider provider(String name, List<SearchSource> results, Runnable onCall) {
        return new SearchProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<SearchSource> search(String query, int maxResults) {
                onCall.run();
                return results;
            }
        };
    }

    private static SearchProvider provider(String name, List<SearchSource> results) {
        return provider(name, results, () -> {
        });
    }

    @Test
    void dedupesByUrlFirstWinsAndAppendsNewOnes() {
        SearchFacade facade = new SearchFacade(List.of(
                provider("ddgs", List.of(
                        new SearchSource("重复A-ddgs", "https://a.example", "s"),
                        new SearchSource("独占-ddgs", "https://ddgs.example", "s"))),
                provider("bing", List.of(
                        new SearchSource("重复A-bing", "https://a.example", "s2"),
                        new SearchSource("独占-bing", "https://bing.example", "s")))));

        List<SearchSource> merged = facade.search("q", 10);
        // first-wins：重复 url 保留 ddgs 版本，新 url 按源顺序追加
        assertThat(merged).containsExactly(
                new SearchSource("重复A-ddgs", "https://a.example", "s"),
                new SearchSource("独占-ddgs", "https://ddgs.example", "s"),
                new SearchSource("独占-bing", "https://bing.example", "s"));
    }

    @Test
    void stopsTryingProvidersOnceMaxResultsReached() {
        AtomicInteger baiduCalls = new AtomicInteger();
        List<SearchSource> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(new SearchSource("t" + i, "https://ddgs.example/" + i, "s"));
        }
        SearchFacade facade = new SearchFacade(List.of(
                provider("ddgs", many),
                provider("baidu", List.of(), baiduCalls::incrementAndGet)));

        assertThat(facade.search("q", 10)).hasSize(10);
        // 凑满 10 条即停，baidu 不再请求
        assertThat(baiduCalls.get()).isZero();
    }

    @Test
    void trimsFinalResultToMaxResults() {
        List<SearchSource> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(new SearchSource("t" + i, "https://x.example/" + i, "s"));
        }
        SearchFacade facade = new SearchFacade(List.of(provider("ddgs", many)));
        assertThat(facade.search("q", 8)).hasSize(8);
    }

    @Test
    void singleSourceFailureDoesNotAffectOthers() {
        SearchFacade facade = new SearchFacade(List.of(
                provider("ddgs", List.of(), () -> {
                    throw new RuntimeException("网络超时");
                }),
                provider("bing", List.of(new SearchSource("b", "https://b.example", "s")))));
        assertThat(facade.search("q", 10))
                .containsExactly(new SearchSource("b", "https://b.example", "s"));
    }

    @Test
    void allSourcesFailingReturnsEmptyList() {
        SearchFacade facade = new SearchFacade(List.of(
                provider("ddgs", List.of(), () -> {
                    throw new RuntimeException("挂");
                }),
                provider("bing", List.of(), () -> {
                    throw new RuntimeException("也挂");
                })));
        assertThat(facade.search("q", 10)).isEmpty();
    }

    @Test
    void knownProviderNamesMapToProvidersAndUnknownOnesAreIgnored() {
        assertThat(SearchFacade.createProvider("ddgs")).isInstanceOf(DuckDuckGoProvider.class);
        assertThat(SearchFacade.createProvider("bing")).isInstanceOf(BingProvider.class);
        assertThat(SearchFacade.createProvider("baidu")).isInstanceOf(BaiduProvider.class);
        // 未知源返回 null，门面构造时过滤（不因配置崩溃；SarosProperties 构造路径由应用启动测试覆盖）
        assertThat(SearchFacade.createProvider("未知源")).isNull();
    }
}
