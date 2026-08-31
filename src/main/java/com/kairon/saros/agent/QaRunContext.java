package com.kairon.saros.agent;

import com.kairon.saros.retrieval.KnowledgeHit;
import com.kairon.saros.search.SearchSource;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次 ask 的运行时上下文（每轮独立实例）：
 * 会话归属、预检结果缓存（工具被调用时直接返回，不重复网络请求）、
 * 工具调用记录（守卫合规判定）、流结束状态。
 */
public class QaRunContext {

    public static final String TOOL_SEARCH = "search";
    public static final String TOOL_KNOWLEDGE = "knowledge";

    public final long userId;
    public volatile long conversationId;
    public volatile boolean isNew;
    /** 预检或工具执行后的搜索结果（null = 尚未取得）。 */
    public volatile List<SearchSource> sources;
    /** 预检或工具执行后的沉淀命中（null = 尚未取得）。 */
    public volatile List<KnowledgeHit> knowledge;
    /** 最终回复仍带工具请求（工具轮耗尽未作答）→ 守卫视为不合规。 */
    public volatile boolean endedWithToolRequest;

    private final Set<String> executedTools = ConcurrentHashMap.newKeySet();

    public QaRunContext(long userId) {
        this.userId = userId;
    }

    public void recordTool(String toolName) {
        executedTools.add(toolName);
    }

    /** 每次 attempt 开始前清零（合规判定按「本轮 attempt」计，两工具须同一次尝试内被调用）。 */
    public void resetTools() {
        executedTools.clear();
        endedWithToolRequest = false;
    }

    public boolean bothToolsCalled() {
        return executedTools.contains(TOOL_SEARCH) && executedTools.contains(TOOL_KNOWLEDGE);
    }
}
