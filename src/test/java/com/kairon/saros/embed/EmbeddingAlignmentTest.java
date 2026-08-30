package com.kairon.saros.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kairon.saros.config.SarosProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J1 对齐测试（PLAN.md §5.1 / §9）：Java（ONNX + 自研分词器）vs Python
 * sentence-transformers 参考输出——逐 token id 一致 + 余弦相似度 ≥ 0.999。
 *
 * <p>校准样本由 scripts/export_bge_onnx.py 产出（src/test/resources/embedding/calibration.json，
 * 含 token_ids 与 Python 侧 512 维向量）；ONNX 模型未导出时跳过。
 */
class EmbeddingAlignmentTest {

    private static final Path MODEL_DIR = Path.of("data/models/bge-small-zh-v1.5");

    @Test
    void tokenIdsAndEmbeddingsMatchPythonReference() throws Exception {
        Assumptions.assumeTrue(
                Files.exists(MODEL_DIR.resolve("model.onnx")) && Files.exists(MODEL_DIR.resolve("vocab.txt")),
                "ONNX 模型未导出（运行 scripts/export_bge_onnx.py），跳过对齐测试");

        JsonNode calib = new ObjectMapper()
                .readTree(new File("src/test/resources/embedding/calibration.json"));
        String prefix = calib.path("query_prefix").asText("");

        SarosProperties props = new SarosProperties();
        props.getEmbedding().setPath(MODEL_DIR.toString());
        OnnxEmbedder embedder = new OnnxEmbedder(props);

        int checked = 0;
        for (JsonNode sample : calib.path("doc_samples")) {
            assertSample(embedder, sample, sample.path("text").asText());
            checked++;
        }
        for (JsonNode sample : calib.path("query_samples")) {
            assertSample(embedder, sample, prefix + sample.path("text").asText());
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(10);
    }

    private void assertSample(OnnxEmbedder embedder, JsonNode sample, String tokenizeInput) {
        long[] expectedIds = new ObjectMapper().convertValue(sample.get("token_ids"), long[].class);
        long[] actualIds = embedder.tokenize(tokenizeInput);
        assertThat(actualIds)
                .as("token ids 应与 Python 参考完全一致（%s...）", tokenizeInput.substring(0, Math.min(20, tokenizeInput.length())))
                .containsExactly(expectedIds);

        float[] reference = new ObjectMapper().convertValue(sample.get("embedding"), float[].class);
        float[] actual = embedder.encodeText(tokenizeInput);
        assertThat(cosine(actual, reference))
                .as("嵌入余弦应与 Python 参考 ≥ 0.999")
                .isGreaterThanOrEqualTo(0.999);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
