package com.kairon.saros.qa;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J2 集成测试：会话列表/详情/删除 REST 契约（沙箱 saros_test schema，PG 不可达整类跳过）。
 *
 * <p>数据由 JDBC 直接播种（ask 流式端点的端到端见 QaAgentIntegrationTest），
 * 断言逐条对齐阶段二 qa.py 语义：列表聚合排序、关键词筛选、详情补正文、
 * 数组/JSONB 列回读（TypeHandler）、级联删除、404 文案。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QaConversationApiIntegrationTest {

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

    /** 每用例前清空业务表（qa/knowledge 共享同一沙箱 schema，计数断言需要确定性）。 */
    @BeforeEach
    void cleanTables() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, PG_USER, PG_PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("DELETE FROM qa_messages");
            s.execute("DELETE FROM qa_conversations");
            s.execute("DELETE FROM manual_knowledge");
        }
    }

    // ---- HTTP 工具 ----

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

    private JsonNode json(HttpResponse<String> resp) throws Exception {
        return JSON.readTree(resp.body());
    }

    // ---- JDBC 播种工具 ----

    private long userId() {
        return userService.getUserId();
    }

    private long seedConversation(String title) throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, PG_USER, PG_PASSWORD);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO qa_conversations (user_id, title) VALUES (?, ?) RETURNING id")) {
            ps.setLong(1, userId());
            ps.setString(2, title);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void seedMessage(long cid, String question, String answer, String sourcesJson,
                             String knowledgeIdsSql, String tagsSql) throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, PG_USER, PG_PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO qa_messages (conversation_id, user_id, question, answer, search_sources, "
                    + "referenced_knowledge_ids, suggested_tags) VALUES ("
                    + cid + ", " + userId() + ", '" + question.replace("'", "''") + "', '"
                    + answer.replace("'", "''") + "', "
                    + (sourcesJson == null ? "NULL" : "CAST('" + sourcesJson.replace("'", "''") + "' AS JSONB)")
                    + ", " + knowledgeIdsSql + ", " + tagsSql + ")");
        }
    }

    private long seedNote(String content) throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, PG_USER, PG_PASSWORD);
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO manual_knowledge (user_id, content) VALUES (?, ?) RETURNING id")) {
            ps.setLong(1, userId());
            ps.setString(2, content);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ---- 用例 ----

    @Test
    void listReturnsCountsAndOrdersByLastActive() throws Exception {
        long older = seedConversation("旧会话");
        seedMessage(older, "旧问题", "旧答案", null, "NULL", "NULL");
        long newer = seedConversation("新会话");
        seedMessage(newer, "第一问", "第一答", null, "NULL", "NULL");
        seedMessage(newer, "第二问", "第二答", null, "NULL", "NULL");

        var resp = send("GET", "/api/qa/conversations", null);
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode list = json(resp);
        // 最近活跃倒序：新会话（2 轮、更晚活跃）在前
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(newer);
        assertThat(list.get(0).get("title").asText()).isEqualTo("新会话");
        assertThat(list.get(0).get("message_count").asLong()).isEqualTo(2);
        assertThat(list.get(0).get("last_active").isNull()).isFalse();
        assertThat(list.get(0).get("created_at").asText()).contains("T");
        assertThat(list.get(1).get("id").asLong()).isEqualTo(older);
        assertThat(list.get(1).get("message_count").asLong()).isEqualTo(1);
    }

    @Test
    void listFiltersByKeywordMatchingTitleQuestionOrAnswer() throws Exception {
        long byTitle = seedConversation("虚拟线程入门");
        seedMessage(byTitle, "随便", "随便", null, "NULL", "NULL");
        long byQuestion = seedConversation("无关标题");
        seedMessage(byQuestion, "什么是虚拟线程？", "答", null, "NULL", "NULL");
        long byAnswer = seedConversation("另一个无关标题");
        seedMessage(byAnswer, "问题", "答案里提到虚拟线程", null, "NULL", "NULL");
        seedConversation("完全无关");

        // 关键词命中标题/问题/答案三种路径；无消息的空会话不出现在列表（JOIN 语义）
        var resp = send("GET", "/api/qa/conversations?q=%E8%99%9A%E6%8B%9F%E7%BA%BF%E7%A8%8B", null);
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode list = json(resp);
        assertThat(list.size()).isEqualTo(3);
        assertThat(list.findValuesAsText("id"))
                .contains(String.valueOf(byTitle), String.valueOf(byQuestion), String.valueOf(byAnswer));

        // q 全空白 → 不筛选；无消息的空会话被 INNER JOIN 排除（阶段二语义）
        resp = send("GET", "/api/qa/conversations?q=%20%20", null);
        assertThat(json(resp).size()).isEqualTo(3);
    }

    @Test
    void detailResolvesKnowledgeAndArraysInMessageOrder() throws Exception {
        long kid = seedNote("虚拟线程由 JVM 调度，阻塞时挂起并释放载体线程");
        long cid = seedConversation("会话");
        // 两轮：第一轮引用 kid；第二轮引用一个已删除的笔记 id（组装时应跳过）
        seedMessage(cid, "问题一", "回答一",
                "[{\"title\":\"某来源\",\"url\":\"https://example.com/a\",\"snippet\":\"摘要\"}]",
                "ARRAY[" + kid + "]::bigint[]", "ARRAY['Java','并发']::text[]");
        seedMessage(cid, "问题二", "回答二", null, "ARRAY[999999]::bigint[]", "NULL");

        var resp = send("GET", "/api/qa/conversations/" + cid, null);
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = json(resp);
        assertThat(body.get("id").asLong()).isEqualTo(cid);
        assertThat(body.get("title").asText()).isEqualTo("会话");

        // 消息按 id 升序
        JsonNode messages = body.get("messages");
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.get(0).get("question").asText()).isEqualTo("问题一");
        assertThat(messages.get(1).get("question").asText()).isEqualTo("问题二");

        // 第一轮：search_sources JSONB 回读、引用补正文、tags 恒空数组（对齐阶段二详情组装）
        JsonNode m0 = messages.get(0);
        assertThat(m0.get("search_sources").size()).isEqualTo(1);
        assertThat(m0.get("search_sources").get(0).get("url").asText()).isEqualTo("https://example.com/a");
        JsonNode refs = m0.get("referenced_knowledge");
        assertThat(refs.size()).isEqualTo(1);
        assertThat(refs.get(0).get("id").asLong()).isEqualTo(kid);
        assertThat(refs.get(0).get("content").asText()).contains("虚拟线程");
        assertThat(refs.get(0).get("tags").isArray()).isTrue();
        assertThat(refs.get(0).get("tags").size()).isZero();
        assertThat(m0.get("suggested_tags")).extracting(JsonNode::asText)
                .containsExactly("Java", "并发");

        // 第二轮：已删笔记的引用被跳过；NULL 数组回读为空数组
        JsonNode m1 = messages.get(1);
        assertThat(m1.get("referenced_knowledge").size()).isZero();
        assertThat(m1.get("suggested_tags").size()).isZero();
        assertThat(m1.get("search_sources").size()).isZero();
    }

    @Test
    void getAndDeleteMissingConversationReturn404() throws Exception {
        var resp = send("GET", "/api/qa/conversations/999999", null);
        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(json(resp).get("detail").asText()).isEqualTo("会话不存在");

        resp = send("DELETE", "/api/qa/conversations/999999", null);
        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(json(resp).get("detail").asText()).isEqualTo("会话不存在");
    }

    @Test
    void deleteCascadesMessages() throws Exception {
        long cid = seedConversation("待删除");
        seedMessage(cid, "问题", "回答", null, "NULL", "NULL");

        var resp = send("DELETE", "/api/qa/conversations/" + cid, null);
        assertThat(resp.statusCode()).isEqualTo(204);

        // 级联：消息随会话删除（FK ON DELETE CASCADE）
        try (Connection c = DriverManager.getConnection(JDBC_URL, PG_USER, PG_PASSWORD);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM qa_messages WHERE conversation_id = " + cid)) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }

        assertThat(send("GET", "/api/qa/conversations/" + cid, null).statusCode()).isEqualTo(404);
    }
}
