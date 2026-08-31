package com.kairon.saros.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kairon.saros.service.UserService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 混合检索端到端（依赖 ONNX 嵌入模型 + 沙箱 PG）：建笔记（真实嵌入）→ KNN 候选
 * → 加权打分 → 相关笔记排在无关笔记之前、tags 补全、相似度 round 4。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HybridRetrieverIntegrationTest {

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

    @Autowired
    private HybridRetriever retriever;
    @Autowired
    private UserService userService;

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
    void cleanTables() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, PG_USER, PG_PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM embeddings");
            s.execute("DELETE FROM manual_knowledge");
        }
    }

    private long createNote(String content, String... tags) throws Exception {
        String body = JSON.writeValueAsString(
                java.util.Map.of("content", content, "tags", List.of(tags)));
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/knowledge"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(201);
            return JSON.readTree(resp.body()).get("id").asLong();
        }
    }

    @Test
    void retrievesRelatedNoteFirstWithTagsAndThreshold() throws Exception {
        Assumptions.assumeTrue(Files.exists(MODEL_ONNX), "嵌入模型未导出，跳过混合检索端到端用例");
        long related = createNote("Java 虚拟线程由 JVM 调度，阻塞时挂起并释放载体线程", "Java", "并发");
        createNote("红烧肉要先焯水再炒糖色，小火慢炖一个小时", "美食");

        List<KnowledgeHit> hits = retriever.retrieve("什么是虚拟线程", userService.getUserId(), 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).id()).isEqualTo(related);
        assertThat(hits.get(0).content()).contains("虚拟线程");
        assertThat(hits.get(0).tags()).containsExactlyInAnyOrder("Java", "并发");
        // 相似度/加权分 round 4 且通过阈值（0.35 过滤后的结果都 ≥ 阈值）
        assertThat(hits).allSatisfy(h -> assertThat(h.score()).isGreaterThanOrEqualTo(0.35));
        assertThat(hits).allSatisfy(h -> {
            assertThat(h.similarity()).isBetween(-1.0, 1.0);
            assertThat(h.score()).isBetween(0.0, 1.0);
        });
        // 排序：score 降序（相关笔记在最前）
        assertThat(hits).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
    }

    @Test
    void returnsEmptyWhenNoNotesExist() throws Exception {
        Assumptions.assumeTrue(Files.exists(MODEL_ONNX), "嵌入模型未导出，跳过混合检索端到端用例");
        assertThat(retriever.retrieve("任意问题", userService.getUserId(), 5)).isEmpty();
    }
}
