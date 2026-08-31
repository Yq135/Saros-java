package com.kairon.saros.agent;

import com.kairon.saros.retrieval.HybridRetriever;
import com.kairon.saros.search.SearchFacade;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * QaAgent 装配工厂（AiServices 唯一构建处，测试的 @Primary 覆盖接缝）。
 *
 * <p>每次 ask 每 attempt 新建：fresh 状态 + 动态系统文案（基础/重试版）+ 本轮工具实例。
 * maxToolCallingRoundTrips 显式设 2（默认 100，失控模型可无限循环工具调用）。
 */
@Component
public class QaAgentFactory {

    @Resource
    private StreamingChatModel streamingModel;

    @Resource
    private SearchFacade searchFacade;

    @Resource
    private HybridRetriever retriever;

    public QaAgent create(String systemMessage, QaRunContext ctx) {
        return AiServices.builder(QaAgent.class)
                .streamingChatModel(streamingModel)
                .systemMessage(systemMessage)
                .tools(new SearchTool(searchFacade, ctx), new KnowledgeTool(retriever, ctx))
                .maxToolCallingRoundTrips(2)
                .build();
    }
}
