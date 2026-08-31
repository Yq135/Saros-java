package com.kairon.saros.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kairon.saros.agent.QaRunContext;
import com.kairon.saros.common.SseEmitterHelper;
import com.kairon.saros.retrieval.KnowledgeHit;
import com.kairon.saros.search.SearchSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QaSseSink 状态机单测（记录 Channel，无 Spring/网络）：
 * 缓冲→start→flush、全空短路 error、重试重置、done/error 事件形状、事件回退保护。
 */
class QaSseSinkTest {

    static class RecordingChannel implements SseEmitterHelper.Channel {
        final List<String[]> events = new ArrayList<>();   // [event, dataJson]
        boolean completed = false;

        @Override
        public void send(String event, String dataJson) throws IOException {
            events.add(new String[]{event, dataJson});
        }

        @Override
        public void complete() {
            completed = true;
        }

        JsonNode data(int i) throws Exception {
            return new ObjectMapper().readTree(events.get(i)[1]);
        }
    }

    private QaRunContext ctx() {
        QaRunContext c = new QaRunContext(1L);
        c.conversationId = 7;
        c.isNew = true;
        c.sources = List.of(new SearchSource("标题", "https://u.example", "摘要"));
        c.knowledge = List.of(new KnowledgeHit(42, "笔记内容", 0.9123, 0.8123, List.of("Java")));
        return c;
    }

    private QaSseSink sink(RecordingChannel ch, QaRunContext ctx) {
        return new QaSseSink(ch, new ObjectMapper(), ctx);
    }

    @Test
    void buffersDeltasUntilBothToolsThenFlushesStartFirst() throws Exception {
        RecordingChannel ch = new RecordingChannel();
        QaSseSink sink = sink(ch, ctx());
        sink.onDelta("你");
        sink.onDelta("好");
        sink.onToolExecuted("search");   // 只有一个工具 → 不 start
        assertThat(ch.events).isEmpty();
        sink.onToolExecuted("knowledge"); // 两工具齐 → start + flush

        assertThat(ch.events).hasSize(3);
        assertThat(ch.events.get(0)[0]).isEqualTo("start");
        JsonNode start = ch.data(0);
        assertThat(start.get("conversation_id").asLong()).isEqualTo(7);
        assertThat(start.get("is_new").asBoolean()).isTrue();
        assertThat(start.get("sources").get(0).get("title").asText()).isEqualTo("标题");
        assertThat(start.get("sources").get(0).get("url").asText()).isEqualTo("https://u.example");
        assertThat(start.get("sources").get(0).get("snippet").asText()).isEqualTo("摘要");
        JsonNode k = start.get("knowledge").get(0);
        assertThat(k.get("id").asLong()).isEqualTo(42);
        assertThat(k.get("content").asText()).isEqualTo("笔记内容");
        assertThat(k.get("similarity").asDouble()).isEqualTo(0.9123);
        assertThat(k.get("tags").get(0).asText()).isEqualTo("Java");
        assertThat(ch.events.get(1)[0]).isEqualTo("delta");
        assertThat(ch.events.get(2)[0]).isEqualTo("delta");
        assertThat(ch.data(1).get("text").asText()).isEqualTo("你");

        // start 后 delta 直通
        sink.onDelta("。");
        assertThat(ch.events).hasSize(4);
        assertThat(sink.fullAnswer()).isEqualTo("你好。");
    }

    @Test
    void shortCircuitsErrorWhenBothToolsExecuteButNoData() throws Exception {
        QaRunContext ctx = ctx();
        ctx.sources = List.of();
        ctx.knowledge = List.of();
        RecordingChannel ch = new RecordingChannel();
        QaSseSink sink = sink(ch, ctx);
        sink.onDelta("不该出现");
        sink.onToolExecuted("search");
        sink.onToolExecuted("knowledge");

        assertThat(ch.events).hasSize(1);
        assertThat(ch.events.get(0)[0]).isEqualTo("error");
        assertThat(ch.data(0).get("detail").asText())
                .isEqualTo("联网搜索与沉淀知识均不可用，请稍后重试");
        assertThat(ch.completed).isTrue();
        // 后续输出全部丢弃
        sink.onDelta("更多");
        assertThat(ch.events).hasSize(1);
    }

    @Test
    void resetForRetryClearsBufferAndAnswerWithoutEvents() {
        RecordingChannel ch = new RecordingChannel();
        QaSseSink sink = sink(ch, ctx());
        sink.onDelta("第一次尝试的文本");
        sink.resetForRetry();
        assertThat(ch.events).isEmpty();
        assertThat(sink.fullAnswer()).isEmpty();

        sink.onToolExecuted("search");
        sink.onToolExecuted("knowledge");
        sink.onDelta("第二次文本");
        assertThat(sink.fullAnswer()).isEqualTo("第二次文本");
        // 第一次的缓冲文本未泄漏
        assertThat(ch.events).hasSize(2); // start + delta
    }

    @Test
    void doneCarriesMessageIdAnswerAndTags() throws Exception {
        RecordingChannel ch = new RecordingChannel();
        QaSseSink sink = sink(ch, ctx());
        sink.onToolExecuted("search");
        sink.onToolExecuted("knowledge");
        sink.onDelta("答案");
        sink.sendDone(99, "答案", List.of("标签A", "标签B"));

        assertThat(ch.events.get(ch.events.size() - 1)[0]).isEqualTo("done");
        JsonNode done = ch.data(ch.events.size() - 1);
        assertThat(done.get("id").asLong()).isEqualTo(99);
        assertThat(done.get("conversation_id").asLong()).isEqualTo(7);
        assertThat(done.get("answer").asText()).isEqualTo("答案");
        assertThat(done.get("suggested_tags").get(0).asText()).isEqualTo("标签A");
        assertThat(ch.completed).isTrue();
    }

    @Test
    void errorAfterStartReplacesDone() throws Exception {
        RecordingChannel ch = new RecordingChannel();
        QaSseSink sink = sink(ch, ctx());
        sink.onToolExecuted("search");
        sink.onToolExecuted("knowledge");
        sink.onDelta("部分答案");
        sink.sendError("模型未返回内容，请重试");

        assertThat(ch.events.get(ch.events.size() - 1)[0]).isEqualTo("error");
        assertThat(ch.data(ch.events.size() - 1).get("detail").asText())
                .isEqualTo("模型未返回内容，请重试");
        assertThat(ch.completed).isTrue();
        // error 后 done 不再发出
        sink.sendDone(1, "答案", List.of());
        assertThat(ch.events.get(ch.events.size() - 1)[0]).isEqualTo("error");
    }

    @Test
    void resetAfterStartIsRejected() {
        RecordingChannel ch = new RecordingChannel();
        QaSseSink sink = sink(ch, ctx());
        sink.onToolExecuted("search");
        sink.onToolExecuted("knowledge");
        assertThatThrownBy(sink::resetForRetry).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void channelIoErrorMarksAbortedAndDropsSubsequentOutput() {
        QaRunContext ctx = ctx();
        // 用会抛 IOException 的通道模拟客户端断开
        SseEmitterHelper.Channel broken = new SseEmitterHelper.Channel() {
            @Override
            public void send(String event, String dataJson) throws IOException {
                throw new IOException("断开");
            }

            @Override
            public void complete() {
            }
        };
        QaSseSink brokenSink = new QaSseSink(broken, new ObjectMapper(), ctx);
        brokenSink.onToolExecuted("search");
        brokenSink.onToolExecuted("knowledge");
        assertThat(brokenSink.aborted()).isTrue();
        brokenSink.onDelta("x");
        // aborted 后 delta 丢弃（QaService 靠 aborted() 跳过入库，不再依赖答案）
        assertThat(brokenSink.fullAnswer()).isEmpty();
        // done 不发事件
        brokenSink.sendDone(1, "x", List.of());
    }
}
