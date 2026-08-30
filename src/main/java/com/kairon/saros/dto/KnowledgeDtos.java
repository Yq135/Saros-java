package com.kairon.saros.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 模块四 DTO（字段名与阶段二 OpenAPI 基线逐字段对齐，蛇形命名）。
 */
public final class KnowledgeDtos {

    private KnowledgeDtos() {
    }

    /** 创建请求：content 必填（空串 422，纯空格放行——对齐 FastAPI minLength 语义）；mastery_level 0-5（默认 0）。 */
    public record CreateRequest(
            @NotNull(message = "Field required")
            @JsonProperty("content") String content,
            @JsonProperty("mastery_level") Integer masteryLevel,
            @JsonProperty("tags") List<String> tags) {
    }

    /** 更新请求：同创建（content 必填）。 */
    public record UpdateRequest(
            @NotNull(message = "Field required")
            @JsonProperty("content") String content,
            @JsonProperty("mastery_level") Integer masteryLevel,
            @JsonProperty("tags") List<String> tags) {
    }

    /** 语义查询请求：query 必填（空/纯空格 → 400，对齐阶段二 strip 后判断）；top_k 1-50（默认 10）。 */
    public record SearchRequest(
            @NotNull(message = "Field required")
            @JsonProperty("query") String query,
            @JsonProperty("top_k") Integer topK) {
    }

    public record Out(
            @JsonProperty("id") long id,
            @JsonProperty("content") String content,
            @JsonProperty("mastery_level") int masteryLevel,
            @JsonProperty("tags") List<String> tags,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {
    }

    public record Hit(
            @JsonProperty("id") long id,
            @JsonProperty("content") String content,
            @JsonProperty("mastery_level") int masteryLevel,
            @JsonProperty("tags") List<String> tags,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("updated_at") OffsetDateTime updatedAt,
            @JsonProperty("similarity") double similarity) {
    }

    public record ListOut(
            @JsonProperty("items") List<Out> items,
            @JsonProperty("total") long total,
            @JsonProperty("page") int page,
            @JsonProperty("page_size") int pageSize) {
    }

    public record SearchOut(
            @JsonProperty("items") List<Hit> items) {
    }
}
