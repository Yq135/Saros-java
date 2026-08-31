package com.kairon.saros.agent;

import com.kairon.saros.search.SearchFacade;
import com.kairon.saros.search.SearchSource;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;

/**
 * 联网搜索工具（每次 ask 新建实例、绑定本轮上下文）。
 *
 * <p>返回编号列表 [1] title（url）\n snippet，模型按 [n] 引用；预检已取得结果时
 * 直接返回缓存（与阶段二「以原始问题检索」语义一致，不重复网络请求）。
 */
public class SearchTool {

    private final SearchFacade searchFacade;
    private final QaRunContext ctx;

    public SearchTool(SearchFacade searchFacade, QaRunContext ctx) {
        this.searchFacade = searchFacade;
        this.ctx = ctx;
    }

    @Tool(name = QaRunContext.TOOL_SEARCH, value = {
            "联网搜索，获取与本轮用户问题相关的实时网络资料。在回答前必须先调用。",
            "参数 question 是用户原始问题全文"})
    public String search(@P("question") String question) {
        ctx.recordTool(QaRunContext.TOOL_SEARCH);
        List<SearchSource> results = ctx.sources != null ? ctx.sources : searchFacade.search(question, 10);
        ctx.sources = results;
        if (results.isEmpty()) {
            return "（本轮联网搜索不可用，没有搜索结果。）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            SearchSource s = results.get(i);
            sb.append('[').append(i + 1).append("] ")
                    .append(s.title()).append("（").append(s.url()).append("）\n")
                    .append(s.snippet()).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
