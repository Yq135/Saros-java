package com.kairon.saros.po;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 表对象：qa_messages（会话内一问一答一轮次）。
 *
 * <p>searchSources 为 search_sources JSONB 的 JSON 文本（service 层 Jackson 序列化，
 * SQL 读时 CAST 为 TEXT）；空数组与 NULL 在 DB 语义中等价，写库前转为 NULL（对齐阶段二）。
 */
@Data
public class QaMessage {

    private long id;
    private long conversationId;
    private long userId;
    private String question;
    private String answer;
    /** search_sources JSONB 的 JSON 文本（对象数组），可能为 NULL */
    private String searchSources;
    /** referenced_knowledge_ids BIGINT[]，可能为 NULL */
    private Long[] referencedKnowledgeIds;
    /** suggested_tags TEXT[]，可能为 NULL（仅会话首轮生成） */
    private String[] suggestedTags;
    private OffsetDateTime createdAt;
}
