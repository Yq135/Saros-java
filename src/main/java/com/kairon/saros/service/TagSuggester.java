package com.kairon.saros.service;

import com.kairon.saros.llm.PromptTemplates;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 推荐标签生成（仅会话首轮，阶段二 generate_tags 对齐）：
 * 非流式轻量调用 + JSON 结构化输出 + 解析兜底；失败返回空列表不阻断回答。
 */
@Service
public class TagSuggester {

    private static final Logger log = LoggerFactory.getLogger(TagSuggester.class);

    @Resource
    private ChatModel chatModel;

    public List<String> suggest(String question, String answer) {
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(
                            SystemMessage.from(PromptTemplates.TAG_SYSTEM),
                            UserMessage.from(PromptTemplates.buildTagUserMessage(question, answer)))
                    // 对齐阶段二 llm.chat 标签调用：temperature 0.3 / max_tokens 512；
                    // response_format json_object 结构化输出（解析端另有正则兜底）
                    .temperature(0.3)
                    .maxOutputTokens(512)
                    .responseFormat(ResponseFormat.JSON)
                    .build();
            ChatResponse response = chatModel.chat(request);
            return PromptTemplates.parseTags(response.aiMessage().text());
        } catch (Exception e) {
            log.warn("推荐标签生成失败: {}", e.getMessage());
            return List.of();
        }
    }
}
