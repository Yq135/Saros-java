package com.kairon.saros.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * DeepSeek 模型装配（OpenAI 兼容协议）。
 *
 * <p>手动 Builder 装配而非 starter 自动配置：① LangChain4j Spring starter 线目前仍是
 * beta（1.19.0-beta29）；② J4 设置页热刷新需要重建模型 bean（重建逻辑挂在
 * SettingsService，见 PLAN.md §5.6）。
 */
@Configuration
public class LlmConfig {

    /** 非流式模型：结构化输出（出题/推荐标签）与 AgentGuard 兜底合成。
     *  bean 名刻意取 openAiChatModel：避免与 @Resource 字段名（如 TagSuggester.chatModel）
     *  撞名——@Resource 名字优先解析，撞名会让测试的 @Primary 假模型失效。 */
    @Bean
    ChatModel openAiChatModel(SarosProperties props) {
        return OpenAiChatModel.builder()
                .baseUrl(props.getLlm().getBaseUrl())
                .apiKey(props.getLlm().getApiKey())
                .modelName(props.getLlm().getModel())
                .temperature(0.7)
                .timeout(Duration.ofSeconds(300))
                .maxRetries(2)
                .build();
    }

    /** 流式模型：SSE 答案流 + QA Agent 工具调用（J2 起用）。bean 命名同上理由。 */
    @Bean
    StreamingChatModel openAiStreamingChatModel(SarosProperties props) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(props.getLlm().getBaseUrl())
                .apiKey(props.getLlm().getApiKey())
                .modelName(props.getLlm().getModel())
                // 对齐阶段二 llm.stream_chat：temperature 0.7 / max_tokens 2048
                .temperature(0.7)
                .maxTokens(2048)
                .timeout(Duration.ofSeconds(300))
                // DeepSeek 每个分块返回完整 toolCallId（与 OpenAI 分块拼接行为不同），
                // 必须关闭累积，否则 agent 工具调用流式解析出错
                .accumulateToolCallId(false)
                .build();
    }
}
