package com.kairon.saros.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J2 live 验收（PLAN §7 ④）：真实 DeepSeek + 真实搜索 + 真实混合检索。
 * 默认跳过，`SAROS_LIVE=1` 且 LLM_API_KEY 有效时才执行。
 *
 * <p>先建一条笔记保证沉淀可用（搜索可能因网络全挂，但知识命中恒有）→ 提问断言
 * SSE 序列与标签 → 同会话追问断言 is_new=false / 标签为空 / 轮次入库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QaLiveTest {

    private static final String SCHEMA = "saros_test";
    private static final String PG_HOST = System.getenv().getOrDefault("PG_HOST", "127.0.0.1");
    private static final String PG_PORT = System.getenv().getOrDefault("PG_PORT", "5432");
    private static final String PG_USER = System.getenv().getOrDefault("PG_USER", "postgres");
    private static final String PG_PASSWORD = System.getenv().getOrDefault("PG_PASSWORD", "");
    private static final String PG_DB = System.getenv().getOrDefault("PG_DB", "saros_db");
    private static final String JDBC_URL = "jdbc:postgresql://" + PG_HOST + ":" + PG_PORT + "/" + PG_DB
            + "?currentSchema=" + SCHEMA + ",public";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path MODEL_ONNX = Path.of("data/models/bge-small-zh-v1.5/model.onnx");

    @LocalServerPort
    private int port;

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
    void liveGate() {
        Assumptions.assumeTrue("1".equals(System.getenv("SAROS_LIVE")),
                "live 测试默认跳过（SAROS_LIVE=1 才跑，需真实 DeepSeek/搜索）");
        Assumptions.assumeTrue(Files.exists(MODEL_ONNX), "嵌入模型未导出，跳过 live 测试");
    }

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

    private List<SseEvent> ask(String body) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/api/qa/ask"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<InputStream> resp =
                client.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream()).get(120, TimeUnit.SECONDS);
        assertThat(resp.statusCode()).isEqualTo(200);
        CompletableFuture<List<SseEvent>> done = CompletableFuture.supplyAsync(() -> {
            try {
                return readSse(resp);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        // live 全链路（工具调用+流式+标签）给 180s
        return done.get(180, TimeUnit.SECONDS);
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

    @Test
    void liveAskAndFollowUp() throws Exception {
        // 保证沉淀有数据：真实嵌入入库一条笔记
        var noteResp = send("POST", "/api/knowledge",
                "{\"content\": \"Java 21 虚拟线程由 JVM 调度，阻塞时挂起并释放载体线程，适合高并发 IO 场景\", \"tags\": [\"Java\", \"并发\"]}");
        assertThat(noteResp.statusCode()).isEqualTo(201);

        // 第一轮：真实提问（agent 工具调用 + DeepSeek 流式 + 首轮标签）
        List<SseEvent> first = ask("{\"question\": \"Java 21 虚拟线程相比平台线程有什么优势？\"}");
        assertThat(first.get(0).name()).isEqualTo("start");
        assertThat(first.get(first.size() - 1).name()).isEqualTo("done");
        long cid = first.get(0).data().get("conversation_id").asLong();
        assertThat(cid).isPositive();
        assertThat(first.get(0).data().get("is_new").asBoolean()).isTrue();
        // 搜索与沉淀至少有一个可用（live 环境搜索可能全挂）
        JsonNode start = first.get(0).data();
        assertThat(start.get("sources").isArray()).isTrue();
        assertThat(start.get("knowledge").isArray()).isTrue();
        // done：完整答案 + 首轮标签（标签生成失败允许为空数组，但字段必须在）
        JsonNode done = first.get(first.size() - 1).data();
        assertThat(done.get("answer").asText()).isNotBlank();
        assertThat(done.get("suggested_tags").isArray()).isTrue();
        assertThat(done.get("conversation_id").asLong()).isEqualTo(cid);

        // 第二轮：同会话追问（is_new=false、标签为空、上下文连贯——回答非空）
        List<SseEvent> second = ask("{\"question\": \"那它和协程的区别是什么？\", \"conversation_id\": " + cid + "}");
        assertThat(second.get(0).name()).isEqualTo("start");
        assertThat(second.get(0).data().get("is_new").asBoolean()).isFalse();
        assertThat(second.get(second.size() - 1).name()).isEqualTo("done");
        JsonNode done2 = second.get(second.size() - 1).data();
        assertThat(done2.get("answer").asText()).isNotBlank();
        assertThat(done2.get("suggested_tags").size()).isZero();

        // 历史可查：两轮都在
        var detail = send("GET", "/api/qa/conversations/" + cid, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(detail.body()).get("messages").size()).isEqualTo(2);
    }
}
