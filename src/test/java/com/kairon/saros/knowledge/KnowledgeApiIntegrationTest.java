package com.kairon.saros.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J1 集成测试（PLAN.md §7 J1 验收 ②④）：完整 knowledge/tags 契约 + 嵌入入库 + KNN 命中。
 *
 * <p>测试库策略：优先用 PG_* 环境变量指向的 PG（阶段二同一实例）上的独立 schema
 * 「saros_test」沙箱（currentSchema=saros_test,public，绝不触碰真实数据）；无 PG 则整类跳过。
 * （本机无 Docker，Testcontainers 留作 CI 备选。）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KnowledgeApiIntegrationTest {

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

    private long createNote(String content, String... tags) throws Exception {
        var resp = send("POST", "/api/knowledge",
                JSON.writeValueAsString(java.util.Map.of("content", content, "tags", java.util.List.of(tags))));
        assertThat(resp.statusCode()).isEqualTo(201);
        return json(resp).get("id").asLong();
    }

    // ---- 用例 ----

    @Test
    void healthMatchesPhase2Contract() throws Exception {
        var resp = send("GET", "/api/health", null);
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = json(resp);
        assertThat(body.get("status").asText()).isEqualTo("ok");
        assertThat(body.get("version").asText()).isEqualTo("0.2.0");
    }

    @Test
    void createReturns201WithCleanedTagsAndSnakeCaseFields() throws Exception {
        var resp = send("POST", "/api/knowledge", """
                {"content": "Java 21 的虚拟线程由 JVM 调度，适合高并发 IO 场景",
                 "mastery_level": 2,
                 "tags": ["Java", " java ", "Java", "并发"]}""");
        assertThat(resp.statusCode()).isEqualTo(201);
        JsonNode body = json(resp);
        assertThat(body.get("id").asLong()).isPositive();
        assertThat(body.get("content").asText()).contains("虚拟线程");
        assertThat(body.get("mastery_level").asInt()).isEqualTo(2);
        // 标签清洗与阶段二一致：仅去空白/去重，不做大小写归一（"Java" 与 "java" 视为不同标签）
        assertThat(body.get("tags")).extracting(JsonNode::asText).containsExactly("Java", "java", "并发");
        assertThat(body.get("created_at").asText()).contains("T");
        assertThat(body.get("updated_at").asText()).contains("T");
    }

    @Test
    void createDefaultsMasteryToZero() throws Exception {
        var resp = send("POST", "/api/knowledge", "{\"content\": \"默认掌握度测试\", \"tags\": []}");
        assertThat(resp.statusCode()).isEqualTo(201);
        assertThat(json(resp).get("mastery_level").asInt()).isZero();
    }

    @Test
    void createRejectsEmptyContentWith422() throws Exception {
        var resp = send("POST", "/api/knowledge", "{\"content\": \"\"}");
        assertThat(resp.statusCode()).isEqualTo(422);
        JsonNode detail = json(resp).get("detail");
        assertThat(detail.isArray()).isTrue();
        assertThat(detail.get(0).get("loc").toString()).contains("content");
    }

    @Test
    void createRejectsOutOfRangeMasteryWith422() throws Exception {
        var resp = send("POST", "/api/knowledge", "{\"content\": \"x\", \"mastery_level\": 6}");
        assertThat(resp.statusCode()).isEqualTo(422);
    }

    @Test
    void listSupportsFiltersPaginationAndOrdering() throws Exception {
        createNote("学习笔记之一：HTTP Range 请求与视频拖动", "网络");
        long b = createNote("学习笔记之二：HTTP Range 请求与断点续传", "网络", "HTTP");
        createNote("做饭笔记：红烧肉的做法", "美食");

        // 标签精确筛选
        var resp = send("GET", "/api/knowledge?tag=%E7%BE%8E%E9%A3%9F", null);
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode list = json(resp);
        assertThat(list.get("total").asLong()).isEqualTo(1);
        assertThat(list.get("items").get(0).get("tags").get(0).asText()).isEqualTo("美食");

        // 关键词 ILIKE + 分页
        resp = send("GET", "/api/knowledge?q=Range&page=1&page_size=1", null);
        list = json(resp);
        assertThat(list.get("total").asLong()).isEqualTo(2);
        assertThat(list.get("page").asInt()).isEqualTo(1);
        assertThat(list.get("page_size").asInt()).isEqualTo(1);
        assertThat(list.get("items").size()).isEqualTo(1);

        // 排序：updated_at DESC, id DESC（后创建的 b 应在前）
        resp = send("GET", "/api/knowledge?q=HTTP+Range&page_size=10", null);
        var items = json(resp).get("items");
        assertThat(items.get(0).get("id").asLong()).isEqualTo(b);

        // page/page_size 越界 → 422
        assertThat(send("GET", "/api/knowledge?page=0", null).statusCode()).isEqualTo(422);
        assertThat(send("GET", "/api/knowledge?page_size=101", null).statusCode()).isEqualTo(422);
    }

    @Test
    void getUpdateDeleteFlowWith404s() throws Exception {
        long kid = createNote("原始内容：夸克胶子等离子体的颜色自由度", "物理");

        var resp = send("GET", "/api/knowledge/" + kid, null);
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(json(resp).get("content").asText()).contains("夸克");

        resp = send("PUT", "/api/knowledge/" + kid, """
                {"content": "第谷超新星 1572 年爆发的观测记录", "mastery_level": 4, "tags": ["天文"]}""");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = json(resp);
        assertThat(body.get("content").asText()).contains("第谷超新星");
        assertThat(body.get("mastery_level").asInt()).isEqualTo(4);
        assertThat(body.get("tags")).extracting(JsonNode::asText).containsExactly("天文");

        resp = send("DELETE", "/api/knowledge/" + kid, null);
        assertThat(resp.statusCode()).isEqualTo(204);

        resp = send("GET", "/api/knowledge/" + kid, null);
        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(json(resp).get("detail").asText()).isEqualTo("知识点不存在");

        assertThat(send("DELETE", "/api/knowledge/" + kid, null).statusCode()).isEqualTo(404);
        assertThat(send("PUT", "/api/knowledge/" + kid, "{\"content\": \"x\"}").statusCode()).isEqualTo(404);
    }

    @Test
    void updateRefreshesEmbeddingRow() throws Exception {
        Assumptions.assumeTrue(Files.exists(MODEL_ONNX), "嵌入模型未导出，跳过向量刷新用例");
        long kid = createNote("夸克胶子等离子体的颜色自由度研究", "物理");
        send("PUT", "/api/knowledge/" + kid, "{\"content\": \"第谷超新星1572年爆发观测\", \"tags\": [\"天文\"]}");

        // 向量行内容应与新正文一致（存储层确定性断言）
        try (Connection c = DriverManager.getConnection(JDBC_URL, PG_USER, PG_PASSWORD);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT chunk_content FROM embeddings WHERE source_id = " + kid + " AND source_type='MANUAL'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("第谷超新星1572年爆发观测");
        }

        // 新语义可命中（top_k=50 内必然包含唯一的新主题）
        var resp = send("POST", "/api/knowledge/search", "{\"query\": \"第谷超新星是什么时候爆发的\", \"top_k\": 50}");
        assertThat(resp.statusCode()).isEqualTo(200);
        var ids = json(resp).get("items").findValuesAsText("id");
        assertThat(ids).contains(String.valueOf(kid));
    }

    @Test
    void semanticSearchReturnsHitsWithSimilarity() throws Exception {
        Assumptions.assumeTrue(Files.exists(MODEL_ONNX), "嵌入模型未导出，跳过语义检索用例");
        long related = createNote("Java 虚拟线程与平台线程的区别：虚拟线程由 JVM 调度，阻塞时挂起并释放载体线程");
        createNote("红烧肉要先焯水再炒糖色，小火慢炖一个小时");

        var resp = send("POST", "/api/knowledge/search", "{\"query\": \"什么是虚拟线程\", \"top_k\": 5}");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode items = json(resp).get("items");
        assertThat(items.size()).isGreaterThan(0);
        // 语义命中应排在首位附近（其他用例也可能创建含「虚拟线程」的笔记，故只断言内容与字段形状）
        assertThat(items.get(0).get("content").asText()).contains("虚拟线程");
        assertThat(items.get(0).get("similarity").isNumber()).isTrue();
        assertThat(items.get(0).get("tags").isArray()).isTrue();
        assertThat(items.findValuesAsText("id")).contains(String.valueOf(related));
    }

    @Test
    void semanticSearchValidationMatchesContract() throws Exception {
        Assumptions.assumeTrue(Files.exists(MODEL_ONNX), "嵌入模型未导出，跳过语义检索用例");
        // 空/纯空格 query → 400（阶段二 strip 后判断）
        assertThat(send("POST", "/api/knowledge/search", "{\"query\": \"   \"}").statusCode()).isEqualTo(400);
        // top_k 越界 → 422
        assertThat(send("POST", "/api/knowledge/search", "{\"query\": \"x\", \"top_k\": 0}").statusCode()).isEqualTo(422);
        assertThat(send("POST", "/api/knowledge/search", "{\"query\": \"x\", \"top_k\": 51}").statusCode()).isEqualTo(422);
    }

    @Test
    void tagsAutocompleteDistinctAndMatching() throws Exception {
        // 用唯一标签名，避免与其他用例创建的标签互相干扰
        createNote("量子场论导论", "量子场论");
        createNote("量子力学基础", "量子力学");

        var resp = send("GET", "/api/tags?q=%E9%87%8F%E5%AD%90", null);
        assertThat(resp.statusCode()).isEqualTo(200);
        // 排序跟随 PG 中文排序规则（zh 拼音序），与阶段二同库同序；不断言具体顺序
        assertThat(json(resp)).extracting(JsonNode::asText).containsExactlyInAnyOrder("量子力学", "量子场论");

        resp = send("GET", "/api/tags", null);
        assertThat(json(resp)).extracting(JsonNode::asText).contains("量子力学", "量子场论");
    }
}
