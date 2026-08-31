package com.kairon.saros.search;

import com.kairon.saros.config.SarosProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 搜索门面：按 saros.search-providers 配置顺序逐源检索、URL 去重（first-wins）、
 * 凑满 maxResults 即停、单源失败仅告警继续（FR-1.7：全挂返回空列表不抛）。
 *
 * <p>合并语义对齐阶段二 search.py search_web。
 */
@Service
public class SearchFacade {

    private static final Logger log = LoggerFactory.getLogger(SearchFacade.class);

    @Resource
    private SarosProperties props;

    private List<SearchProvider> providers = List.of();

    /** 按配置装配搜索源（@Resource 注入完成后执行）。 */
    @PostConstruct
    void init() {
        this.providers = Arrays.stream(props.getSearchProviders().split(","))
                .map(String::strip)
                .map(SearchFacade::createProvider)
                .filter(Objects::nonNull)
                .toList();
    }

    /** 测试接缝：直接注入假源（同包单测用，不经 Spring 生命周期）。 */
    SearchFacade(List<SearchProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    static SearchProvider createProvider(String name) {
        return switch (name) {
            case "ddgs" -> new DuckDuckGoProvider();
            case "bing" -> new BingProvider();
            case "baidu" -> new BaiduProvider();
            default -> {
                log.warn("未知搜索源配置 {}，忽略", name);
                yield null;
            }
        };
    }

    public List<SearchSource> search(String query, int maxResults) {
        Map<String, SearchSource> merged = new LinkedHashMap<>();
        for (SearchProvider provider : providers) {
            try {
                for (SearchSource r : provider.search(query, maxResults)) {
                    merged.putIfAbsent(r.url(), r);
                }
            } catch (Exception e) {
                log.warn("搜索源 {} 失败: {}", provider.name(), e.getMessage());
            }
            if (merged.size() >= maxResults) {
                break;
            }
        }
        return merged.values().stream().limit(maxResults).toList();
    }
}
