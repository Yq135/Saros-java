package com.kairon.saros.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 模块一（联网问答）DTO（字段名与阶段二 OpenAPI 基线逐字段对齐，蛇形命名）。
 *
 * <p>SSE 事件（start/delta/done/error）的 data 结构在 OpenAPI 基线中无 schema，
 * 以阶段二 qa_service.py 实际行为为准（见 StartKnowledgeOut 等注释）。
 */
public final class QaDtos {

    private QaDtos() {
    }

    /** 提问/追问请求：question 长度 1-2000（校验在 service，纯空格放行——对齐 FastAPI minLength 语义）。 */
    public record AskRequest(
            @NotNull(message = "Field required")
            @JsonProperty("question") String question,
            @JsonProperty("conversation_id") Integer conversationId) {
    }

    public record SearchSourceOut(
            @JsonProperty("title") String title,
            @JsonProperty("url") String url,
            @JsonProperty("snippet") String snippet) {
    }

    public record ReferencedKnowledgeOut(
            @JsonProperty("id") long id,
            @JsonProperty("content") String content,
            @JsonProperty("tags") List<String> tags) {
    }

    public record MessageOut(
            @JsonProperty("id") long id,
            @JsonProperty("question") String question,
            @JsonProperty("answer") String answer,
            @JsonProperty("search_sources") List<SearchSourceOut> searchSources,
            @JsonProperty("referenced_knowledge") List<ReferencedKnowledgeOut> referencedKnowledge,
            @JsonProperty("suggested_tags") List<String> suggestedTags,
            @JsonProperty("created_at") OffsetDateTime createdAt) {
    }

    public record ConversationOut(
            @JsonProperty("id") long id,
            @JsonProperty("title") String title,
            @JsonProperty("message_count") long messageCount,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("last_active") OffsetDateTime lastActive) {
    }

    public record ConversationDetail(
            @JsonProperty("id") long id,
            @JsonProperty("title") String title,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("messages") List<MessageOut> messages) {
    }

    /** SSE start 事件 knowledge 数组元素（含 similarity，round 4 位——阶段二行为）。 */
    public record StartKnowledgeOut(
            @JsonProperty("id") long id,
            @JsonProperty("content") String content,
            @JsonProperty("similarity") double similarity,
            @JsonProperty("tags") List<String> tags) {
    }
}
