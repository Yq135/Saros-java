package com.kairon.saros.llm;

import com.kairon.saros.po.QaHistoryRow;
import com.kairon.saros.retrieval.KnowledgeHit;
import com.kairon.saros.search.SearchSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提示词移植单测（对齐阶段二 prompts.py / qa_service.py 行为）：
 * 消息组装格式、历史截断、parse_tags 三路径。
 */
class PromptTemplatesTest {

    @Test
    void agentSystemContainsMandateAndAnswerRules() {
        String system = PromptTemplates.agentSystem();
        assertThat(system).contains("必须先调用 search 与 knowledge 两个工具");
        assertThat(system).contains("沉淀笔记的权威性高于搜索结果");
        // 重试版 = 基础版 + 强化文案
        assertThat(PromptTemplates.retrySystem())
                .isEqualTo(PromptTemplates.agentSystem() + PromptTemplates.GUARD_RETRY_APPEND);
    }

    @Test
    void agentUserMessageIncludesHistorySectionOnlyWhenPresent() {
        String withHistory = PromptTemplates.buildAgentUserMessage("什么是虚拟线程？", "用户：之前\n助手：之前答");
        assertThat(withHistory).contains("## 本轮之前的对话（仅供理解上下文，不要引用其中的编号）");
        assertThat(withHistory).contains("## 用户问题\n什么是虚拟线程？");

        String withoutHistory = PromptTemplates.buildAgentUserMessage("问题", "");
        assertThat(withoutHistory).doesNotContain("本轮之前的对话");
        assertThat(withoutHistory).isEqualTo("## 用户问题\n问题");
    }

    @Test
    void fallbackUserMessageFormatsSourcesAndKnowledgeLikePhase2() {
        String msg = PromptTemplates.buildFallbackUserMessage(
                "什么是装饰器",
                List.of(new SearchSource("标题A", "https://a.example", "摘要A")),
                List.of(new KnowledgeHit(42, "装饰器是语法糖", 0.9, 0.8, List.of("Python"))),
                "");
        // 知识节：笔记{id}：{content}
        assertThat(msg).contains("## 你的沉淀笔记（权威，优先采信）\n- 笔记42：装饰器是语法糖");
        // 搜索节：[1] title（url）\n snippet
        assertThat(msg).contains("## 搜索结果\n[1] 标题A（https://a.example）\n摘要A");
        assertThat(msg).endsWith("## 用户问题\n什么是装饰器");
    }

    @Test
    void fallbackUserMessageNotesUnavailableSearch() {
        String msg = PromptTemplates.buildFallbackUserMessage("问题", List.of(), List.of(), "");
        assertThat(msg).contains("（本轮联网搜索不可用，没有搜索结果。）");
        assertThat(msg).doesNotContain("## 搜索结果");
        assertThat(msg).doesNotContain("## 你的沉淀笔记");
    }

    @Test
    void historyTextTruncatesAnswerAt1000CharsHard() {
        QaHistoryRow row = new QaHistoryRow();
        row.setQuestion("问题全文");
        row.setAnswer("答".repeat(1500));
        String text = PromptTemplates.buildHistoryText(List.of(row));
        assertThat(text).isEqualTo("用户：问题全文\n助手：" + "答".repeat(1000));

        QaHistoryRow nullAnswer = new QaHistoryRow();
        nullAnswer.setQuestion("q");
        assertThat(PromptTemplates.buildHistoryText(List.of(nullAnswer))).isEqualTo("用户：q\n助手：");
    }

    @Test
    void tagUserMessageTruncatesAnswerAt2000Chars() {
        String msg = PromptTemplates.buildTagUserMessage("问题", "答".repeat(3000));
        assertThat(msg).isEqualTo("问题：问题\n\n回答：" + "答".repeat(2000));
    }

    @Test
    void parseTagsHandlesJsonArray() {
        assertThat(PromptTemplates.parseTags("[\"装饰器\", \"Python\"]"))
                .containsExactly("装饰器", "Python");
        // 超 5 个截断；空元素丢弃
        assertThat(PromptTemplates.parseTags("[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]")).hasSize(5);
        assertThat(PromptTemplates.parseTags("[\"a\", \"  \", \"b\"]")).containsExactly("a", "b");
    }

    @Test
    void parseTagsFallsBackToQuoteRegex() {
        // 非 JSON 文本 → 引号正则兜底
        assertThat(PromptTemplates.parseTags("推荐标签是「装饰器」和'Python'，还有\"语法糖\""))
                .containsExactly("装饰器", "Python", "语法糖");
        // JSON 解析成功但为空数组 → 也走兜底（阶段二精确行为）
        assertThat(PromptTemplates.parseTags("[]")).isEmpty();
        assertThat(PromptTemplates.parseTags("")).isEmpty();
        assertThat(PromptTemplates.parseTags(null)).isEmpty();
    }
}
