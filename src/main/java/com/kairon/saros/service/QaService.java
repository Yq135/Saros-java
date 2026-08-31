package com.kairon.saros.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kairon.saros.agent.AgentGuard;
import com.kairon.saros.agent.QaAgent;
import com.kairon.saros.agent.QaAgentFactory;
import com.kairon.saros.agent.QaRunContext;
import com.kairon.saros.common.ApiException;
import com.kairon.saros.common.GlobalExceptionHandler.ValidationException;
import com.kairon.saros.common.SseEmitterHelper;
import com.kairon.saros.dto.QaDtos;
import com.kairon.saros.llm.PromptTemplates;
import com.kairon.saros.mapper.ManualKnowledgeMapper;
import com.kairon.saros.mapper.QaConversationMapper;
import com.kairon.saros.mapper.QaMessageMapper;
import com.kairon.saros.po.QaConversation;
import com.kairon.saros.po.QaMessage;
import com.kairon.saros.retrieval.HybridRetriever;
import com.kairon.saros.retrieval.KnowledgeHit;
import com.kairon.saros.search.SearchFacade;
import com.kairon.saros.search.SearchSource;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 联网问答应用服务：会话 CRUD + /qa/ask 流式编排。
 *
 * <p>ask 主流程（每轮独立执行）：校验/新建会话 → 预检搜索+混合检索（均空短路 error）
 * → 历史上下文注入 → QaAgent 流式（守卫：不合规重试 1 轮 → 兜底管线）
 * → 答案+来源+引用沉淀+首轮标签一次入库 → done。会话语义与错误文案逐条对齐
 * 阶段二 qa.py / qa_service.py。
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    /** 会话列表上限（对齐阶段二 LIMIT 100）。 */
    static final int LIST_LIMIT = 100;
    /** 会话标题：首问前 30 字硬截断（对齐阶段二 TITLE_MAX_LEN）。 */
    static final int TITLE_MAX_LEN = 30;
    /** 多轮上下文轮数（对齐阶段二 CONTEXT_MAX_ROUNDS）。 */
    static final int CONTEXT_MAX_ROUNDS = 6;
    /** 每轮搜索结果条数（对齐阶段二 search_web max_results）。 */
    static final int SEARCH_MAX_RESULTS = 10;
    /** 流式等待超时（秒）。 */
    static final long STREAM_TIMEOUT_SECONDS = 300;
    /** 问题长度上限（对齐 QAAskRequest max_length=2000）。 */
    static final int QUESTION_MAX_LEN = 2000;

    private final QaConversationMapper conversationMapper;
    private final QaMessageMapper messageMapper;
    private final ManualKnowledgeMapper knowledgeMapper;
    private final UserService userService;
    private final ObjectMapper json;
    private final SearchFacade searchFacade;
    private final HybridRetriever retriever;
    private final QaAgentFactory agentFactory;
    private final AgentGuard guard;
    private final TagSuggester tagSuggester;

    public QaService(QaConversationMapper conversationMapper,
                     QaMessageMapper messageMapper,
                     ManualKnowledgeMapper knowledgeMapper,
                     UserService userService,
                     ObjectMapper json,
                     SearchFacade searchFacade,
                     HybridRetriever retriever,
                     QaAgentFactory agentFactory,
                     AgentGuard guard,
                     TagSuggester tagSuggester) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.userService = userService;
        this.json = json;
        this.searchFacade = searchFacade;
        this.retriever = retriever;
        this.agentFactory = agentFactory;
        this.guard = guard;
        this.tagSuggester = tagSuggester;
    }

    /**
     * 请求级校验（SSE 建立前，422 普通 JSON）：
     * 仅长度 1-2000（对齐 FastAPI minLength/maxLength 语义——纯空格放行，
     * strip 后进入管线，照抄阶段二边界行为）。
     */
    public static void validateQuestion(String question) {
        if (question.isEmpty()) {
            throw new ValidationException("question", "ensure this value has at least 1 characters");
        }
        if (question.length() > QUESTION_MAX_LEN) {
            throw new ValidationException("question", "ensure this value has at most 2000 characters");
        }
    }

    // ---- /qa/ask 流式编排（controller 以虚拟线程调用，SSE 语义见 QaSseSink） ----

    public void ask(String rawQuestion, Integer conversationId, SseEmitter emitter) {
        String question = rawQuestion.strip();
        QaSseSink sink = null;
        try {
            long userId = userService.getUserId();
            QaRunContext ctx = new QaRunContext(userId);
            sink = new QaSseSink(SseEmitterHelper.channel(emitter), json, ctx);
            try {
                ensureConversation(question, conversationId, ctx);
                // 预检：搜索 + 混合检索；均空 → error 短路（阶段二语义，零 LLM 成本）
                precheck(question, ctx);
                String history = ctx.isNew ? "" : loadHistory(ctx.conversationId, userId);
                String userMessage = PromptTemplates.buildAgentUserMessage(question, history);
                runAgentWithGuard(question, history, userMessage, ctx, sink);
                if (sink.aborted()) {
                    return;
                }
                String answer = sink.fullAnswer();
                if (answer.isBlank()) {
                    throw new QaAbortException("模型未返回内容，请重试");
                }
                // 推荐标签仅会话首轮（对齐阶段二：追问轮连 LLM 都不调）
                List<String> tags = ctx.isNew ? tagSuggester.suggest(question, answer) : List.of();
                if (sink.aborted()) {
                    return;
                }
                long messageId = saveMessage(ctx, question, answer, tags);
                sink.sendDone(messageId, answer, tags);
            } finally {
                // 空会话清理（对齐阶段二 delete_conversation_if_empty：覆盖失败/客户端断开等所有路径）
                if (ctx.isNew && ctx.conversationId > 0) {
                    conversationMapper.deleteIfEmpty(ctx.conversationId, userId);
                }
            }
        } catch (QaAbortException e) {
            if (sink != null) {
                sink.sendError(e.getDetail());
            } else {
                sendErrorQuietly(emitter, e.getDetail());
            }
        } catch (Exception e) {
            log.error("问答流异常", e);
            if (sink != null) {
                sink.sendError("服务异常：" + e.getMessage());
            } else {
                sendErrorQuietly(emitter, "服务异常：" + e.getMessage());
            }
        }
    }

    private void ensureConversation(String question, Integer conversationId, QaRunContext ctx) {
        if (conversationId == null) {
            QaConversation conv = new QaConversation();
            conv.userId = ctx.userId;
            conv.title = question.length() <= TITLE_MAX_LEN ? question : question.substring(0, TITLE_MAX_LEN);
            conversationMapper.insertConversation(conv);
            ctx.conversationId = conv.id;
            ctx.isNew = true;
            return;
        }
        if (conversationMapper.findById(conversationId, ctx.userId) == null) {
            throw new QaAbortException("会话不存在或已删除");
        }
        ctx.conversationId = conversationId;
        ctx.isNew = false;
    }

    private void precheck(String question, QaRunContext ctx) {
        List<SearchSource> sources = searchFacade.search(question, SEARCH_MAX_RESULTS);
        // 检索异常向上抛（阶段二同款：外层兜底 error 事件，不静默降级）
        List<KnowledgeHit> knowledge = retriever.retrieve(question, ctx.userId, HybridRetriever.KNOWLEDGE_TOP);
        ctx.sources = sources;
        ctx.knowledge = knowledge;
        if (sources.isEmpty() && knowledge.isEmpty()) {
            throw new QaAbortException(AgentGuard.NO_DATA_DETAIL);
        }
    }

    private String loadHistory(long cid, long userId) {
        return PromptTemplates.buildHistoryText(messageMapper.recentHistory(cid, userId, CONTEXT_MAX_ROUNDS));
    }

    /**
     * 守卫主循环：attempt 1 基础系统提示 → 不合规则重试 1 轮（强化提示）
     * → 仍不合规走确定性兜底管线。start 已发出则无法重试（事件不可回退），
     * 交由下游空答案检查兜底（error 替代 done，契约允许）。
     */
    private void runAgentWithGuard(String question, String history, String userMessage,
                                   QaRunContext ctx, QaSseSink sink) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            ctx.resetTools();
            runAgentAttempt(attempt == 1 ? PromptTemplates.agentSystem() : PromptTemplates.retrySystem(),
                    userMessage, ctx, sink);
            if (sink.aborted()) {
                return;
            }
            if (guard.isCompliant(ctx)) {
                return;
            }
            if (sink.started()) {
                return;
            }
            // 本轮缓冲文本作废（不进兜底）
            sink.resetForRetry();
            if (attempt == 2) {
                guard.runFallback(question, history, ctx, sink);
                return;
            }
        }
    }

    private void runAgentAttempt(String systemMessage, String userMessage, QaRunContext ctx, QaSseSink sink) {
        QaAgent agent = agentFactory.create(systemMessage, ctx);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        TokenStream ts = agent.answer(userMessage);
        ts.onPartialResponse(sink::onDelta)
                .onToolExecuted(te -> sink.onToolExecuted(te.request().name()))
                .onCompleteResponse(resp -> {
                    // 工具轮耗尽仍未作答 → 守卫视为不合规
                    if (resp.aiMessage().hasToolExecutionRequests()) {
                        ctx.endedWithToolRequest = true;
                    }
                    latch.countDown();
                })
                .onError(t -> {
                    streamError.set(t);
                    latch.countDown();
                });
        ts.start();
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

    /** 答案+来源+引用沉淀+首轮标签一次入库（流完成后）；空数组存 NULL（对齐阶段二 or None）。 */
    private long saveMessage(QaRunContext ctx, String question, String answer, List<String> tags) {
        QaMessage msg = new QaMessage();
        msg.conversationId = ctx.conversationId;
        msg.userId = ctx.userId;
        msg.question = question;
        msg.answer = answer;
        try {
            // search_sources 恒存（空也存 []，对齐阶段二 Jsonb(sources)）
            msg.searchSources = json.writeValueAsString(ctx.sources == null ? List.of() : ctx.sources);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("搜索来源序列化失败", e);
        }
        List<Long> knowledgeIds = ctx.knowledge == null ? List.of()
                : ctx.knowledge.stream().map(KnowledgeHit::id).toList();
        msg.referencedKnowledgeIds = knowledgeIds.isEmpty() ? null : knowledgeIds.toArray(Long[]::new);
        msg.suggestedTags = tags.isEmpty() ? null : tags.toArray(String[]::new);
        messageMapper.insertMessage(msg);
        return msg.id;
    }

    private void sendErrorQuietly(SseEmitter emitter, String detail) {
        try {
            SseEmitterHelper.send(emitter, "error", json.writeValueAsString(Map.of("detail", detail)));
        } catch (Exception ignored) {
            // 客户端已断开：仅完成 emitter
        } finally {
            emitter.complete();
        }
    }

    // ---- 会话 CRUD（REST 端点，异常走 HTTP 状态码） ----

    public List<QaDtos.ConversationOut> listConversations(String q) {
        long userId = userService.getUserId();
        // 阶段二：q strip 后非空才筛选，pattern 用未 strip 的 q
        String qPattern = (q == null || q.strip().isEmpty()) ? null : "%" + q + "%";
        return conversationMapper.listWithCount(userId, qPattern, LIST_LIMIT).stream()
                .map(r -> new QaDtos.ConversationOut(r.id, r.title, r.messageCount, r.createdAt, r.lastActive))
                .toList();
    }

    public QaDtos.ConversationDetail getConversation(long cid) {
        long userId = userService.getUserId();
        QaConversation conv = conversationMapper.findById(cid, userId);
        if (conv == null) {
            throw ApiException.notFound("会话不存在");
        }
        List<QaMessage> messages = messageMapper.findByConversation(cid, userId);
        Map<Long, String> contentMap = loadContentMap(messages, userId);
        List<QaDtos.MessageOut> outs = messages.stream()
                .map(m -> toMessageOut(m, contentMap))
                .toList();
        return new QaDtos.ConversationDetail(conv.id, conv.title, conv.createdAt, outs);
    }

    public void deleteConversation(long cid) {
        long userId = userService.getUserId();
        if (conversationMapper.deleteById(cid, userId) == 0) {
            throw ApiException.notFound("会话不存在");
        }
    }

    // ---- 内部工具 ----

    /** 引用沉淀 id 全集 → 笔记当前正文（已删笔记自然缺席，组装时跳过）。 */
    private Map<Long, String> loadContentMap(List<QaMessage> messages, long userId) {
        List<Long> ids = messages.stream()
                .flatMap(m -> m.referencedKnowledgeIds == null
                        ? java.util.stream.Stream.empty()
                        : Arrays.stream(m.referencedKnowledgeIds))
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return knowledgeMapper.findByIds(ids, userId).stream()
                .collect(Collectors.toMap(k -> k.id, k -> k.content, (a, b) -> a));
    }

    private QaDtos.MessageOut toMessageOut(QaMessage m, Map<Long, String> contentMap) {
        List<QaDtos.ReferencedKnowledgeOut> refs = new ArrayList<>();
        if (m.referencedKnowledgeIds != null) {
            for (Long kid : m.referencedKnowledgeIds) {
                String content = contentMap.get(kid);
                if (content != null) {
                    // tags 恒空数组：对齐阶段二详情组装行为（ReferencedKnowledgeOut.tags 默认空列表）
                    refs.add(new QaDtos.ReferencedKnowledgeOut(kid, content, List.of()));
                }
            }
        }
        return new QaDtos.MessageOut(
                m.id,
                m.question,
                m.answer,
                parseSources(m.searchSources),
                refs,
                m.suggestedTags == null ? List.of() : List.of(m.suggestedTags),
                m.createdAt);
    }

    /** search_sources JSONB → 对象列表；解析失败按阶段二防御行为返回空列表。 */
    private List<QaDtos.SearchSourceOut> parseSources(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(jsonText, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
