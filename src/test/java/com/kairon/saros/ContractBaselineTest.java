package com.kairon.saros;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J0 契约基座：① 阶段二 OpenAPI 基线可解析且覆盖全部核心端点；
 * ② 应用可启动、/actuator/health 含 PG 检查为 UP（需 PG_* 环境变量指向阶段二 PG）。
 *
 * <p>完整逐字段契约测试随 J1-J4 各模块迁移时补充（PLAN.md §6）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractBaselineTest {

    @LocalServerPort
    private int port;

    @Test
    void openapiBaselineParsesAndCoversExpectedEndpoints() throws Exception {
        File baseline = new File("docs/openapi-phase2.json");
        assertThat(baseline).exists();
        JsonNode paths = new ObjectMapper().readTree(baseline).get("paths");
        assertThat(paths).isNotNull();

        List<String> pathKeys = new ArrayList<>();
        paths.fieldNames().forEachRemaining(pathKeys::add);

        // 契约基线必须覆盖的端点（前缀包含匹配，容忍 {id}/{cid}/{tid} 路径参数命名差异）
        List<String> required = List.of(
                "/api/qa/ask",
                "/api/qa/conversations",
                "/api/knowledge",
                "/api/tags",
                "/api/webpages",
                "/api/bilibili/tasks",
                "/api/settings",
                "/api/health");
        for (String endpoint : required) {
            assertThat(pathKeys)
                    .as("基线应包含端点：%s", endpoint)
                    .anyMatch(path -> path.contains(endpoint));
        }
    }

    @Test
    void healthIsUpIncludingPg() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);
            // 整体 status 必须 UP（DB health indicator 依赖 PG_* 环境变量）
            assertThat(resp.body()).contains("\"status\":\"UP\"");
        }
    }
}
