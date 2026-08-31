package com.kairon.saros.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kairon.saros.agent.QaRunContext;
import com.kairon.saros.common.SseEmitterHelper;
import com.kairon.saros.dto.QaDtos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE 事件状态机（每次 ask 一个实例）：
 *
 * <ul>
 *   <li>start 前：delta 全部缓冲，不发出任何事件（模型可能在调工具前先流少量文字）；</li>
 *   <li>两工具都执行完且数据非空 → sendStart()（start + flush 缓冲 delta）；</li>
 *   <li>两工具都执行但数据全空 → 短路 error（PLAN §5.2 守卫语义，丢弃后续输出）；</li>
 *   <li>error 场景下若 start 未发出则流里只有 error 一个事件（对齐阶段二）。</li>
 * </ul>
 *
 * <p>客户端断开（send 抛 IOException）→ aborted，后续输出丢弃、跳过入库/done。
 */
public class QaSseSink {

    private final SseEmitterHelper.Channel channel;
    private final ObjectMapper json;
    private final QaRunContext ctx;

    private final StringBuilder answer = new StringBuilder();
    private final List<String> bufferedDeltas = new ArrayList<>();
    private boolean started = false;
    private boolean aborted = false;
    private boolean completed = false;

    public QaSseSink(SseEmitterHelper.Channel channel, ObjectMapper json, QaRunContext ctx) {
        this.channel = channel;
        this.json = json;
        this.ctx = ctx;
    }

    public synchronized void onDelta(String text) {
        if (aborted || completed) {
            return;
        }
        answer.append(text);
        if (!started) {
            bufferedDeltas.add(text);
            return;
        }
        sendEvent("delta", Map.of("text", text));
    }

    /** 工具执行回调：记录调用；两工具齐且数据非空 → 发 start；数据全空 → 短路 error。 */
    public synchronized void onToolExecuted(String toolName) {
        if (aborted || completed) {
            return;
        }
        ctx.recordTool(toolName);
        if (ctx.bothToolsCalled() && !started) {
            if (noData()) {
                sendError("联网搜索与沉淀知识均不可用，请稍后重试");
            } else {
                sendStart();
            }
        }
    }

    /** 兜底管线直接发 start（无工具调用路径）；幂等。 */
    public synchronized void sendStart() {
        if (started || aborted || completed) {
            return;
        }
        started = true;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversation_id", ctx.conversationId);
        data.put("is_new", ctx.isNew);
        data.put("sources", ctx.sources == null ? List.of() : ctx.sources);
        List<QaDtos.StartKnowledgeOut> knowledge = ctx.knowledge == null ? List.of()
                : ctx.knowledge.stream()
                        .map(k -> new QaDtos.StartKnowledgeOut(k.id(), k.content(), k.similarity(), k.tags()))
                        .toList();
        data.put("knowledge", knowledge);
        sendEvent("start", data);
        for (String d : bufferedDeltas) {
            sendEvent("delta", Map.of("text", d));
        }
        bufferedDeltas.clear();
    }

    public synchronized void sendDone(long messageId, String answerText, List<String> tags) {
        if (completed) {
            return;
        }
        completed = true;
        try {
            if (!aborted) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("id", messageId);
                data.put("conversation_id", ctx.conversationId);
                data.put("answer", answerText);
                data.put("suggested_tags", tags);
                sendEvent("done", data);
            }
        } finally {
            channel.complete();
        }
    }

    public synchronized void sendError(String detail) {
        if (completed) {
            return;
        }
        completed = true;
        try {
            sendEvent("error", Map.of("detail", detail));
        } finally {
            channel.complete();
        }
    }

    /** 守卫重试前：丢弃缓冲（不发出任何事件），清空已累计的回答文本。 */
    public synchronized void resetForRetry() {
        if (started || aborted || completed) {
            throw new IllegalStateException("事件已发出，无法重试");
        }
        bufferedDeltas.clear();
        answer.setLength(0);
    }

    public synchronized String fullAnswer() {
        return answer.toString();
    }

    public synchronized boolean aborted() {
        return aborted;
    }

    /** start 是否已发出（已发出则事件不可回退，守卫无法重试）。 */
    public synchronized boolean started() {
        return started;
    }

    private boolean noData() {
        return (ctx.sources == null || ctx.sources.isEmpty())
                && (ctx.knowledge == null || ctx.knowledge.isEmpty());
    }

    private void sendEvent(String event, Object data) {
        try {
            channel.send(event, json.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SSE 数据序列化失败", e);
        } catch (Exception e) {
            // 客户端断开（IOException/IllegalStateException）：丢弃后续输出
            aborted = true;
        }
    }
}
