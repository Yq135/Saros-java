package com.kairon.saros.search;

import java.util.List;

/**
 * 搜索源接口（免费源，接口化可插拔；失败抛异常，由 SearchFacade 降级）。
 */
public interface SearchProvider {

    /** 源名称（对应 saros.search-providers 配置键：ddgs/bing/baidu）。 */
    String name();

    /** 返回该源的前 maxResults 条结果（可少于）；网络/解析失败抛异常。 */
    List<SearchSource> search(String query, int maxResults) throws Exception;
}
