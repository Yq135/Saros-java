package com.kairon.saros.po;

import java.time.OffsetDateTime;

/**
 * 表对象：qa_messages（会话内一问一答一轮次）。
 *
 * <p>search_sources 为 JSONB 对象数组，Java 侧以 JSON 字符串承载（service 层 Jackson 序列化，
 * SQL 读时 CAST 为 TEXT）；空数组与 NULL 在 DB 语义中等价，写库前转为 NULL（对齐阶段二）。
 */
public class QaMessage {

    public long id;
    public long conversationId;
    public long userId;
    public String question;
    public String answer;
    /** search_sources JSONB 的 JSON 文本（对象数组），可能为 NULL */
    public String searchSources;
    /** referenced_knowledge_ids BIGINT[]，可能为 NULL */
    public Long[] referencedKnowledgeIds;
    /** suggested_tags TEXT[]，可能为 NULL（仅会话首轮生成） */
    public String[] suggestedTags;
    public OffsetDateTime createdAt;
}
