package com.kairon.saros.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Saros 全局配置（对应 application.yml 的 saros.* 节）。
 *
 * <p>键名与阶段二 .env 对齐（LLM_* / ASR_* / COOKIE_PATH / SKIP_SUBTITLE ...），
 * 环境变量同名覆盖，便于切换期两套后端共用同一组环境变量。
 */
@Data
@ConfigurationProperties(prefix = "saros")
public class SarosProperties {

    private Llm llm = new Llm();

    private Asr asr = new Asr();

    private Embedding embedding = new Embedding();

    /** B 站 cookie 文件路径（相对路径基于进程工作目录） */
    private String cookiePath = "data/.cookies.txt";

    /** 跳过字幕下载：开启后所有视频直接走音频模式（ASR 转写） */
    private boolean skipSubtitle = false;

    /** 视频清晰度上限（720p 封顶，已无视频下载、配置保留） */
    private int maxVideoHeight = 720;

    /** 嵌入模型（HF 模型名或本地路径，J1 起用） */
    private String embeddingModel = "BAAI/bge-small-zh-v1.5";

    /** 搜索源（逗号分隔：ddgs,bing,baidu，可裁剪） */
    private String searchProviders = "ddgs,bing,baidu";

    /** CORS 允许来源（逗号分隔；nginx/vite 同源反代时不生效） */
    private String corsOrigins = "http://localhost:5173,http://127.0.0.1:5173";

    /** 主 LLM（OpenAI 兼容协议，DeepSeek 等国产模型） */
    @Data
    public static class Llm {
        private String baseUrl = "https://api.deepseek.com/v1";
        private String apiKey = "";
        private String model = "deepseek-chat";
    }

    /** 音频模式 ASR（自建 mlx-qwen3-asr，OpenAI 兼容接口；仅无 CC/AI 字幕时启用） */
    @Data
    public static class Asr {
        private String baseUrl = "http://100.100.61.45:9001/v1";
        private String apiKey = "";
        private String model = "mlx-community/Qwen3-ASR-1.7B-bf16";
    }

    /** 嵌入模型（本地 ONNX 推理，bge-small-zh-v1.5） */
    @Data
    public static class Embedding {
        /** 模型目录：含 model.onnx / vocab.txt / calibration.json（scripts/export_bge_onnx.py 产出） */
        private String path = "data/models/bge-small-zh-v1.5";
    }
}
