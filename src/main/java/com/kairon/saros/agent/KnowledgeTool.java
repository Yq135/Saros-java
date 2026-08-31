package com.kairon.saros.agent;

import com.kairon.saros.retrieval.HybridRetriever;
import com.kairon.saros.retrieval.KnowledgeHit;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 沉淀知识检索工具（每次 ask 新建实例、绑定本轮上下文）。
 *
 * <p>返回「- 笔记{id}：{content}」列表（id = manual_knowledge 主键，与阶段二
 * build_answer_messages 一致）；预检已取得结果时直接返回缓存。
 */
public class KnowledgeTool {

    private final HybridRetriever retriever;
    private final QaRunContext ctx;

    public KnowledgeTool(HybridRetriever retriever, QaRunContext ctx) {
        this.retriever = retriever;
        this.ctx = ctx;
    }

    @Tool(name = QaRunContext.TOOL_KNOWLEDGE, value = {
            "检索用户的沉淀笔记（手打知识，权威性高于网络搜索结果）。在回答前必须先调用。",
            "参数 question 是用户原始问题全文"})
    public String knowledge(@P("question") String question) {
        ctx.recordTool(QaRunContext.TOOL_KNOWLEDGE);
        List<KnowledgeHit> hits = ctx.knowledge != null
                ? ctx.knowledge
                : retriever.retrieve(question, ctx.userId, HybridRetriever.KNOWLEDGE_TOP);
        ctx.knowledge = hits;
        if (hits.isEmpty()) {
            return "（未检索到相关沉淀笔记。）";
        }
        return hits.stream()
                .map(k -> "- 笔记" + k.id() + "：" + k.content())
                .collect(Collectors.joining("\n\n"));
    }
}
