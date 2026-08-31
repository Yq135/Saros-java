package com.kairon.saros.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kairon.saros.agent.QaAgent;
import com.kairon.saros.agent.QaAgentFactory;
import com.kairon.saros.agent.QaRunContext;
import com.kairon.saros.llm.PromptTemplates;
import com.kairon.saros.retrieval.HybridRetriever;
import com.kairon.saros.retrieval.KnowledgeHit;
import com.kairon.saros.search.SearchFacade;
import com.kairon.saros.search.SearchSource;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * J2 核心集成测试（PLAN §7 J2 验收 ①②③）：FakeAgent + FakeLLM + MockitoBean 搜索/检索
 * + 沙箱 PG，覆盖 SSE 契约（start/delta/done 事件序列与字段）、多轮（is_new/历史注入/
 * 标签仅首轮）、守卫（重试→合规、两次拒绝→兜底管线）、降级（均空短路/会话不存在/
 * 空答案/流异常）、422 校验、入库与详情回读。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QaAgentIntegrationTest {

    private static final String SCHEMA = "saros_test";
    private static final String PG_HOST = System.getenv().getOrDefault("PG_HOST", "127.0.0.1");
    private static final String PG_PORT = System.getenv().getOrDefault("PG_PORT", "5432");
    private static final String PG_USER = System.getenv().getOrDefault("PG_USER", "postgres");
    private static final String PG_PASSWORD = System.getenv().getOrDefault("PG_PASSWORD", "");
    private static final String PG_DB = System.getenv().getOrDefault("PG_DB", "saros_db");
    private static final String JDBC_URL = "jdbc:postgresql://" + PG_HOST + ":" + PG_PORT + "/" + PG_DB
            + "?currentSchema=" + SCHEMA + ",public";

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @MockitoBean
    private SearchFacade searchFacade;
    @MockitoBean
    private HybridRetriever retriever;

    @Autowired
    private ScriptedAgent scriptedAgent;
    @Autowired
    private RecordingStreamingModel streamingModel;
    @Autowired
    private RecordingChatModel chatModel;

    // ---- Fake 配置（@Primary 覆盖） ----

    @TestConfiguration
    static class FakeConfig {

        @Bean
        ScriptedAgent scriptedAgent() {
            return new ScriptedAgent();
        }

        @Bean
        RecordingStreamingModel recordingStreamingModel() {
            return new RecordingStreamingModel();
        }

        @Bean
        RecordingChatModel recordingChatModel() {
            return new RecordingChatModel();
        }

        @Bean
        @Primary
        QaAgentFactory fakeQaAgentFactory(ScriptedAgent scripted, SearchFacade sf, HybridRetriever hr) {
            return new QaAgentFactory(null, sf, hr) {
                @Override
                public QaAgent create(String systemMessage, QaRunContext ctx) {
                    return scripted.agent(systemMessage);
                }
            };
        }

        @Bean
        @Primary
        StreamingChatModel fakeStreamingChatModel(RecordingStreamingModel m) {
            return m;
        }

        @Bean
        @Primary
        ChatModel fakeChatModel(RecordingChatModel m) {
            return m;
        }
    }

    // ---- Fake 实现 ----

    /** 按调用顺序排队脚本，捕获 system/user 消息。 */
    static class ScriptedAgent {
        final List<List<Step>> scriptQueue = new CopyOnWriteArrayList<>();
        final List<String> systemMessages = new CopyOnWriteArrayList<>();
        final List<String> userMessages = new CopyOnWriteArrayList<>();

        void enqueue(List<Step> steps) {
            scriptQueue.add(steps);
        }

        QaAgent agent(String systemMessage) {
            systemMessages.add(systemMessage);
            List<Step> steps = scriptQueue.isEmpty() ? List.of(new CompleteStep()) : scriptQueue.remove(0);
            return userMessage -> {
                userMessages.add(userMessage);
                return new FakeTokenStream(steps);
            };
        }
    }

    interface Step {
    }

    record ToolStep(String name) implements Step {
    }

    record DeltaStep(String text) implements Step {
    }

    record CompleteStep() implements Step {
    }

    record FailStep(String message) implements Step {
    }

    static class FakeTokenStream implements TokenStream {
        private final List<Step> steps;
        private Consumer<String> onPartial;
        private Consumer<ToolExecution> onTool;
        private Consumer<ChatResponse> onComplete;
        private Consumer<Throwable> onError;

        FakeTokenStream(List<Step> steps) {
            this.steps = steps;
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> c) {
            onPartial = c;
            return this;
        }

        @Override
        public TokenStream onRetrieved(Consumer<List<Content>> c) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(Consumer<ToolExecution> c) {
            onTool = c;
            return this;
        }

        @Override
        public TokenStream onCompleteResponse(Consumer<ChatResponse> c) {
            onComplete = c;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> c) {
            onError = c;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            for (Step s : steps) {
                if (s instanceof ToolStep t && onTool != null) {
                    onTool.accept(ToolExecution.builder()
                            .request(ToolExecutionRequest.builder().name(t.name())
                                    .arguments("{\"question\":\"x\"}").build())
                            .result("")
                            .invocationContext(dev.langchain4j.invocation.InvocationContext.builder()
                                    .invocationId(java.util.UUID.randomUUID())
                                    .interfaceName("QaAgent")
                                    .methodName("answer")
                                    .timestampNow()
                                    .build())
                            .build());
                } else if (s instanceof DeltaStep d && onPartial != null) {
                    onPartial.accept(d.text());
                } else if (s instanceof CompleteStep && onComplete != null) {
                    onComplete.accept(ChatResponse.builder().aiMessage(AiMessage.from("")).build());
                } else if (s instanceof FailStep f && onError != null) {
                    onError.accept(new RuntimeException(f.message()));
                }
            }
        }
    }

    /** 兜底流式模型：记录请求，按脚本分块输出。 */
    static class RecordingStreamingModel implements StreamingChatModel {
        final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
        List<String> answerChunks = List.of();
        String fullAnswer = "";

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            requests.add(chatRequest);
            for (String chunk : answerChunks) {
                handler.onPartialResponse(chunk);
            }
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(fullAnswer)).build());
        }
    }

    /** 标签模型：记录请求（含 responseFormat 断言），返回 JSON 数组文本。 */
    static class RecordingChatModel implements ChatModel {
        final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
        String response = "[\"标签A\",\"标签B\"]";

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            requests.add(chatRequest);
            return ChatResponse.builder().aiMessage(AiMessage.from(response)).build();
        }
    }

    // ---- 沙箱与数据准备 ----

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", () -> JDBC_URL);
        reg.add("spring.datasource.username", () -> PG_USER);
        reg.add("spring.datasource.password", () -> PG_PASSWORD);
    }

    @BeforeAll
    static void initSchema() {
        try (Connection c = DriverManager.getConnection(JDBC_URL, PG_USER, PG_PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            s.execute("CREATE SCHEMA " + SCHEMA);
            s.execute(Files.readString(Path.of("src/test/resources/schema/test_init.sql")));
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "PG 不可用，跳过集成测试：" + e.getMessage());
        }
    }

    @BeforeEach
    void resetFakesAndTables() throws Exception {
        reset(searchFacade, retriever);
        scriptedAgent.scriptQueue.clear();
        scriptedAgent.systemMessages.clear();
        scriptedAgent.userMessages.clear();
        streamingModel.requests.clear();
        streamingModel.answerChunks = List.of();
        streamingModel.fullAnswer = "";
        chatModel.requests.clear();
        chatModel.response = "[\"标签A\",\"标签B\"]";
        try (Connection c = DriverManager.getConnection(JDBC_URL, PG_USER, PG_PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM qa_messages");
            s.execute("DELETE FROM qa_conversations");
            s.execute("DELETE FROM manual_knowledge");
        }
    }

    private void stubData(long knowledgeId) {
        when(searchFacade.search(anyString(), anyInt())).thenReturn(List.of(
                new SearchSource("来源一", "https://a.example", "摘要一"),
                new SearchSource("来源二", "https://b.example", "摘要二")));
        when(retriever.retrieve(anyString(), anyLong(), anyInt())).thenReturn(List.of(
                new KnowledgeHit(knowledgeId, "沉淀内容：虚拟线程由 JVM 调度", 0.9, 0.8, List.of("Java"))));
    }

    private long seedNote(String content) throws Exception {
        String body = JSON.writeValueAsString(java.util.Map.of("content", content, "tags", List.of()));
        HttpResponse<String> resp = send("POST", "/api/knowledge", body);
        assertThat(resp.statusCode()).isEqualTo(201);
        return JSON.readTree(resp.body()).get("id").asLong();
    }

    // ---- HTTP / SSE 工具 ----

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path));
            if (body != null) {
                b.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    record SseEvent(String name, JsonNode data) {
    }

    /** 发起 ask（SSE），读到流结束返回事件序列（30s 兜底超时）。 */
    private List<SseEvent> ask(String body) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/api/qa/ask"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<InputStream> resp =
                client.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream()).get(60, TimeUnit.SECONDS);
        assertThat(resp.statusCode()).isEqualTo(200);
        CompletableFuture<List<SseEvent>> done = CompletableFuture.supplyAsync(() -> {
            try {
                return readSse(resp);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        return done.get(30, TimeUnit.SECONDS);
    }

    private List<SseEvent> readSse(HttpResponse<InputStream> resp) throws Exception {
        List<SseEvent> events = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            String name = null;
            StringBuilder data = new StringBuilder();
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    if (name != null) {
                        events.add(new SseEvent(name, JSON.readTree(data.toString())));
                        name = null;
                        data.setLength(0);
                    }
                    continue;
                }
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
        }
        return events;
    }

    // ---- 用例 ----

    @Test
    void happyPathSseContractAndPersistence() throws Exception {
        long kid = seedNote("虚拟线程由 JVM 调度");
        stubData(kid);
        scriptedAgent.enqueue(List.of(
                new ToolStep("search"), new ToolStep("knowledge"),
                new DeltaStep("第一"), new DeltaStep("段"), new CompleteStep()));

        List<SseEvent> events = ask("{\"question\": \"什么是虚拟线程？\"}");
        assertThat(events).extracting(SseEvent::name).containsExactly("start", "delta", "delta", "done");

        // start：conversation_id + is_new + 来源 + 引用沉淀（含 similarity）
        JsonNode start = events.get(0).data();
        long cid = start.get("conversation_id").asLong();
        assertThat(cid).isPositive();
        assertThat(start.get("is_new").asBoolean()).isTrue();
        JsonNode sources = start.get("sources");
        assertThat(sources.size()).isEqualTo(2);
        assertThat(sources.get(0).get("title").asText()).isEqualTo("来源一");
        assertThat(sources.get(0).get("url").asText()).isEqualTo("https://a.example");
        assertThat(sources.get(0).get("snippet").asText()).isEqualTo("摘要一");
        JsonNode knowledge = start.get("knowledge");
        assertThat(knowledge.size()).isEqualTo(1);
        assertThat(knowledge.get(0).get("id").asLong()).isEqualTo(kid);
        assertThat(knowledge.get(0).get("content").asText()).contains("虚拟线程");
        assertThat(knowledge.get(0).get("similarity").asDouble()).isEqualTo(0.9);
        assertThat(knowledge.get(0).get("tags").get(0).asText()).isEqualTo("Java");

        // delta：text 增量
        assertThat(events.get(1).data().get("text").asText()).isEqualTo("第一");
        assertThat(events.get(2).data().get("text").asText()).isEqualTo("段");

        // done：id + conversation_id + answer + suggested_tags
        JsonNode done = events.get(3).data();
        assertThat(done.get("id").asLong()).isPositive();
        assertThat(done.get("conversation_id").asLong()).isEqualTo(cid);
        assertThat(done.get("answer").asText()).isEqualTo("第一段");
        assertThat(done.get("suggested_tags")).extracting(JsonNode::asText)
                .containsExactly("标签A", "标签B");

        // 标签模型：仅一次调用，responseFormat=JSON，temperature=0.3
        assertThat(chatModel.requests).hasSize(1);
        ChatRequest tagReq = chatModel.requests.get(0);
        assertThat(tagReq.responseFormat().type().name()).isEqualTo("JSON");
        assertThat(tagReq.temperature()).isEqualTo(0.3);

        // 入库：question/answer/sources/引用沉淀/标签（TypeHandler 回读）
        var detail = send("GET", "/api/qa/conversations/" + cid, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        JsonNode msg = JSON.readTree(detail.body()).get("messages").get(0);
        assertThat(msg.get("question").asText()).isEqualTo("什么是虚拟线程？");
        assertThat(msg.get("answer").asText()).isEqualTo("第一段");
        assertThat(msg.get("search_sources").size()).isEqualTo(2);
        assertThat(msg.get("referenced_knowledge").get(0).get("id").asLong()).isEqualTo(kid);
        assertThat(msg.get("suggested_tags")).extracting(JsonNode::asText)
                .containsExactly("标签A", "标签B");

        // 会话标题 = 首问截断；agent 收到基础系统提示
        assertThat(JSON.readTree(detail.body()).get("title").asText()).isEqualTo("什么是虚拟线程？");
        assertThat(scriptedAgent.systemMessages.get(0)).isEqualTo(PromptTemplates.agentSystem());
        assertThat(scriptedAgent.userMessages.get(0)).contains("## 用户问题\n什么是虚拟线程？");
    }

    @Test
    void followUpTurnIsNotNewSkipsTagsAndInjectsHistory() throws Exception {
        stubData(1);
        scriptedAgent.enqueue(List.of(
                new ToolStep("search"), new ToolStep("knowledge"),
                new DeltaStep("第一轮回答"), new CompleteStep()));
        scriptedAgent.enqueue(List.of(
                new ToolStep("search"), new ToolStep("knowledge"),
                new DeltaStep("第二轮回答"), new CompleteStep()));

        List<SseEvent> first = ask("{\"question\": \"第一问\"}");
        long cid = first.get(0).data().get("conversation_id").asLong();

        List<SseEvent> second = ask("{\"question\": \"追问\", \"conversation_id\": " + cid + "}");
        assertThat(second).extracting(SseEvent::name).containsExactly("start", "delta", "done");
        assertThat(second.get(0).data().get("is_new").asBoolean()).isFalse();
        assertThat(second.get(0).data().get("conversation_id").asLong()).isEqualTo(cid);
        // 追问轮不生成标签（连标签模型都不调）
        assertThat(second.get(2).data().get("suggested_tags").size()).isZero();
        assertThat(chatModel.requests).hasSize(1);

        // 历史注入：第二轮 user 消息含第一轮问答原文
        String userMsg2 = scriptedAgent.userMessages.get(1);
        assertThat(userMsg2).contains("## 本轮之前的对话（仅供理解上下文，不要引用其中的编号）");
        assertThat(userMsg2).contains("用户：第一问\n助手：第一轮回答");

        // 会话消息数 = 2
        var detail = send("GET", "/api/qa/conversations/" + cid, null);
        assertThat(JSON.readTree(detail.body()).get("messages").size()).isEqualTo(2);
    }

    @Test
    void guardRetriesWithStricterSystemThenComplies() throws Exception {
        stubData(1);
        // attempt 1：拒绝调工具直接作答 → 缓冲被丢弃；attempt 2：合规
        scriptedAgent.enqueue(List.of(new DeltaStep("直接回答"), new CompleteStep()));
        scriptedAgent.enqueue(List.of(
                new ToolStep("search"), new ToolStep("knowledge"),
                new DeltaStep("合规回答"), new CompleteStep()));

        List<SseEvent> events = ask("{\"question\": \"问题\"}");
        assertThat(events).extracting(SseEvent::name).containsExactly("start", "delta", "done");
        assertThat(events.get(2).data().get("answer").asText()).isEqualTo("合规回答");
        // 第一轮的「直接回答」不泄漏
        assertThat(events.stream().filter(e -> e.name().equals("delta")).map(e -> e.data().get("text").asText()))
                .containsExactly("合规回答");
        // 两次尝试的系统提示：基础版 → 强化版
        assertThat(scriptedAgent.systemMessages).containsExactly(
                PromptTemplates.agentSystem(), PromptTemplates.retrySystem());
    }

    @Test
    void guardFallsBackToDeterministicPipelineWhenModelRefusesTwice() throws Exception {
        stubData(42);
        scriptedAgent.enqueue(List.of(new DeltaStep("a"), new CompleteStep()));
        scriptedAgent.enqueue(List.of(new DeltaStep("b"), new CompleteStep()));
        streamingModel.answerChunks = List.of("兜底", "回答");
        streamingModel.fullAnswer = "兜底回答";

        List<SseEvent> events = ask("{\"question\": \"问题\"}");
        assertThat(events).extracting(SseEvent::name).containsExactly("start", "delta", "delta", "done");
        assertThat(events.get(0).data().get("sources").size()).isEqualTo(2);
        assertThat(events.get(3).data().get("answer").asText()).isEqualTo("兜底回答");

        // 兜底管线：系统提示 = ANSWER_SYSTEM（非 agent 版），用户消息含阶段二格式
        assertThat(streamingModel.requests).hasSize(1);
        List<dev.langchain4j.data.message.ChatMessage> msgs = streamingModel.requests.get(0).messages();
        assertThat(((dev.langchain4j.data.message.SystemMessage) msgs.get(0)).text())
                .isEqualTo(PromptTemplates.ANSWER_SYSTEM);
        String userText = ((dev.langchain4j.data.message.UserMessage) msgs.get(1)).singleText();
        assertThat(userText).contains("[1] 来源一（https://a.example）\n摘要一");
        assertThat(userText).contains("- 笔记42：沉淀内容：虚拟线程由 JVM 调度");
    }

    @Test
    void bothUnavailableShortCircuitsWithErrorAndCleansEmptyConversation() throws Exception {
        when(searchFacade.search(anyString(), anyInt())).thenReturn(List.of());
        when(retriever.retrieve(anyString(), anyLong(), anyInt())).thenReturn(List.of());

        List<SseEvent> events = ask("{\"question\": \"问题\"}");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).name()).isEqualTo("error");
        assertThat(events.get(0).data().get("detail").asText())
                .isEqualTo("联网搜索与沉淀知识均不可用，请稍后重试");
        // 零 LLM 成本：agent 与两个模型都没被调用
        assertThat(scriptedAgent.systemMessages).isEmpty();
        assertThat(streamingModel.requests).isEmpty();
        assertThat(chatModel.requests).isEmpty();
        // 空会话被清理
        var list = send("GET", "/api/qa/conversations", null);
        assertThat(JSON.readTree(list.body()).size()).isZero();
    }

    @Test
    void followUpOnMissingConversationReturnsErrorEvent() throws Exception {
        stubData(1);
        List<SseEvent> events = ask("{\"question\": \"问题\", \"conversation_id\": 999999}");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).name()).isEqualTo("error");
        assertThat(events.get(0).data().get("detail").asText()).isEqualTo("会话不存在或已删除");
    }

    @Test
    void emptyAnswerReturnsErrorAfterStart() throws Exception {
        stubData(1);
        scriptedAgent.enqueue(List.of(
                new ToolStep("search"), new ToolStep("knowledge"), new CompleteStep()));

        List<SseEvent> events = ask("{\"question\": \"问题\"}");
        assertThat(events).extracting(SseEvent::name).containsExactly("start", "error");
        assertThat(events.get(1).data().get("detail").asText()).isEqualTo("模型未返回内容，请重试");
        // 未入库 → 空会话被清理
        var list = send("GET", "/api/qa/conversations", null);
        assertThat(JSON.readTree(list.body()).size()).isZero();
    }

    @Test
    void streamFailureYieldsServiceErrorEvent() throws Exception {
        stubData(1);
        scriptedAgent.enqueue(List.of(new FailStep("boom")));

        List<SseEvent> events = ask("{\"question\": \"问题\"}");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).name()).isEqualTo("error");
        assertThat(events.get(0).data().get("detail").asText()).contains("boom");
    }

    @Test
    void questionValidationReturns422BeforeSse() throws Exception {
        var resp = send("POST", "/api/qa/ask", "{\"question\": \"\"}");
        assertThat(resp.statusCode()).isEqualTo(422);
        JsonNode detail = JSON.readTree(resp.body()).get("detail");
        assertThat(detail.get(0).get("loc").get(1).asText()).isEqualTo("question");

        resp = send("POST", "/api/qa/ask", "{\"question\": \"" + "x".repeat(2001) + "\"}");
        assertThat(resp.statusCode()).isEqualTo(422);
    }
}
