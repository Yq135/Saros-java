package com.kairon.saros.embed;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kairon.saros.config.SarosProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * bge-small-zh-v1.5 本地 ONNX 推理（PLAN.md §5.1）。
 *
 * <p>模型由 scripts/export_bge_onnx.py 一次性导出：图内已固化「均值池化(attention mask) + L2
 * 归一化」，与阶段二 sentence-transformers encode(normalize_embeddings=True) 逐位一致。
 * 惰性初始化（首次调用加载 ONNX 会话与词表）；查询侧加 BGE 前缀（与阶段二一致）。
 */
@Component
public class OnnxEmbedder {

    private final Path modelDir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Object lock = new Object();
    private volatile boolean initialized = false;
    private OrtEnvironment env;
    private OrtSession session;
    private BertWordPieceTokenizer tokenizer;
    private String queryPrefix = "";

    public OnnxEmbedder(SarosProperties props) {
        this.modelDir = Path.of(props.getEmbedding().getPath());
    }

    /** 分词（含 [CLS]/[SEP] 与截断）；供对齐测试逐 token 断言，与嵌入走完全相同的分词路径。 */
    public long[] tokenize(String text) {
        ensureInit();
        return tokenizer.tokenize(text);
    }

    /** 文档侧编码（知识点内容，不加前缀）——对应阶段二 encode_text。 */
    public float[] encodeText(String text) {
        return embed(text, false);
    }

    /** 查询侧编码（加 BGE 检索前缀）——对应阶段二 encode_query。 */
    public float[] encodeQuery(String query) {
        return embed(query, true);
    }

    private float[] embed(String text, boolean isQuery) {
        ensureInit();
        long[] ids = tokenizer.tokenize(isQuery ? queryPrefix + text : text);
        long[] mask = new long[ids.length];
        java.util.Arrays.fill(mask, 1L);
        try (OnnxTensor inputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[]{1, ids.length});
             OnnxTensor attnMask = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), new long[]{1, mask.length});
             OrtSession.Result result = session.run(
                     Map.of("input_ids", inputIds, "attention_mask", attnMask))) {
            float[][] out = (float[][]) result.get("embedding").get().getValue();
            return out[0];
        } catch (Exception e) {
            throw new IllegalStateException("ONNX 嵌入推理失败：" + e.getMessage(), e);
        }
    }

    private void ensureInit() {
        if (initialized) {
            return;
        }
        synchronized (lock) {
            if (initialized) {
                return;
            }
            Path onnx = modelDir.resolve("model.onnx");
            Path vocab = modelDir.resolve("vocab.txt");
            Path calib = modelDir.resolve("calibration.json");
            if (!Files.exists(onnx) || !Files.exists(vocab)) {
                throw new IllegalStateException(
                        "嵌入模型缺失：" + onnx + " / " + vocab + "（运行 scripts/export_bge_onnx.py 导出）");
            }
            try {
                // 从校准样本元数据读 max_seq_length / query_prefix / do_lower_case
                JsonNode meta = objectMapper.readTree(calib.toFile());
                int maxSeq = meta.path("max_seq_length").asInt(256);
                this.queryPrefix = meta.path("query_prefix").asText("");
                boolean doLower = meta.path("do_lower_case").asBoolean(false);

                this.tokenizer = new BertWordPieceTokenizer(vocab, maxSeq, doLower);
                this.env = OrtEnvironment.getEnvironment();
                this.session = env.createSession(onnx.toString());
                initialized = true;
            } catch (Exception e) {
                throw new IllegalStateException("嵌入模型初始化失败：" + e.getMessage(), e);
            }
        }
    }

    @PreDestroy
    void close() {
        synchronized (lock) {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响进程退出
                }
            }
        }
    }
}
