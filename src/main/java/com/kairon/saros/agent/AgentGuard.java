package com.kairon.saros.agent;

import com.kairon.saros.llm.PromptTemplates;
import com.kairon.saros.retrieval.HybridRetriever;
import com.kairon.saros.retrieval.KnowledgeHit;
import com.kairon.saros.search.SearchFacade;
import com.kairon.saros.search.SearchSource;
import com.kairon.saros.service.QaAbortException;
import com.kairon.saros.service.QaSseSink;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AgentGuard（PLAN §5.2）：合规判定 + 确定性兜底管线。
 *
 * <ul>
 *   <li>合规 = search 与 knowledge 两个工具都被调用，且最终回复不带残留工具请求；</li>
 *   <li>兜底（阶段二原管线）：代码强制 search + retrieve → 均空抛 QaAbort（error 事件）
 *       → 否则以阶段二 build_answer_messages 组装用户消息，普通流式合成。</li>
 * </ul>
 */
@Component
public class AgentGuard {

    /** 搜索与沉淀均不可用时的 error 文案（阶段二原文）。 */
    public static final String NO_DATA_DETAIL = "联网搜索与沉淀知识均不可用，请稍后重试";

    private static final long STREAM_TIMEOUT_SECONDS = 300;

    private final SearchFacade searchFacade;
    private final HybridRetriever retriever;
    private final StreamingChatModel streamingModel;

    public AgentGuard(SearchFacade searchFacade, HybridRetriever retriever, StreamingChatModel streamingModel) {
        this.searchFacade = searchFacade;
        this.retriever = retriever;
        this.streamingModel = streamingModel;
    }

    public boolean isCompliant(QaRunContext ctx) {
        return ctx.bothToolsCalled() && !ctx.endedWithToolRequest;
    }

    /**
     * 确定性兜底管线：结果复用预检缓存（同轮数据），流式合成经 sink 发出。
     *
     * @throws QaAbortException 搜索与沉淀均不可用
     */
    public void runFallback(String question, String history, QaRunContext ctx, QaSseSink sink) {
        List<SearchSource> sources = ctx.sources != null ? ctx.sources : searchFacade.search(question, 10);
        List<KnowledgeHit> knowledge = ctx.knowledge != null
                ? ctx.knowledge
                : retriever.retrieve(question, ctx.userId, HybridRetriever.KNOWLEDGE_TOP);
        ctx.sources = sources;
        ctx.knowledge = knowledge;
        if (sources.isEmpty() && knowledge.isEmpty()) {
            throw new QaAbortException(NO_DATA_DETAIL);
        }
        sink.sendStart();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        streamingModel.chat(
                ChatRequest.builder()
                        .messages(
                                SystemMessage.from(PromptTemplates.ANSWER_SYSTEM),
                                UserMessage.from(PromptTemplates.buildFallbackUserMessage(
                                        question, sources, knowledge, history)))
                        .build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        sink.onDelta(partialResponse);
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {
                        streamError.set(error);
                        latch.countDown();
                    }
                });
        try {
            if (!latch.await(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new RuntimeException("模型响应超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("问答流被中断", e);
        }
        if (streamError.get() != null) {
            throw new RuntimeException("模型调用失败: " + streamError.get().getMessage(), streamError.get());
        }
    }
}
