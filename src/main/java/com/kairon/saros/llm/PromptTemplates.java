package com.kairon.saros.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kairon.saros.po.QaHistoryRow;
import com.kairon.saros.retrieval.KnowledgeHit;
import com.kairon.saros.search.SearchSource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 问答提示词（阶段二 prompts.py 全量移植）与标签解析。
 *
 * <p>ANSWER_SYSTEM/TAG_SYSTEM 为原文移植；QaAgent 在系统提示前额外注入
 * 双工具强制约束（agentSystem()），AgentGuard 重试用 retryAppend() 拼接。
 */
public final class PromptTemplates {

    /** 历史回答截断长度（对齐阶段二 ANSWER_TRUNCATE，硬切无省略号）。 */
    public static final int ANSWER_TRUNCATE = 1000;
    /** 标签生成时回答截断长度（对齐阶段二 answer[:2000]）。 */
    static final int TAG_ANSWER_TRUNCATE = 2000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private PromptTemplates() {
    }

    /** 阶段二 ANSWER_SYSTEM 原文（七条回答规则）。 */
    public static final String ANSWER_SYSTEM = """
            你是 Saros，一个高度专业的个人知识沉淀与拓展助手。语气像陪读伙伴：温柔、不冷冰冰，回答清晰有温度。你的核心任务是结合用户的【沉淀笔记】和【实时网络搜索结果】，为用户提供准确、有深度且易于理解的答案。

            回答规则：
            1. 用中文 Markdown 输出，结构清晰（可用小标题、列表）。
            2. 引用联网搜索资料回答的关键事实、数据或观点时，在对应句末用 [n] 标注，n 与「搜索结果」列表中的编号一致。
            3. 用户沉淀笔记的权威性高于搜索结果：两者冲突时以沉淀笔记为基础框架进行解答，并温和地说明差异。若笔记中信息不全，再使用【网络搜索结果】进行补充、拓展和最新事实核查。
            4. 诚实原则：如果提供的参考资料中完全没有相关信息，或者信息不足以回答问题，请直接回复："抱歉，在您的个人笔记和网络资料中均未找到相关信息。"，绝不编造事实或来源，不要尝试强行作答。
            5. 本轮若没有搜索结果，仅基于沉淀笔记回答，并注明「本轮联网搜索不可用」。
            6. 若沉淀笔记与搜索结果都不足，直接说明无法回答，不要勉强。
            7. 拓展延伸（可选）：如果网络资料提供了笔记中没有的前沿观点或最新动态，请在此处补充说明，帮助用户拓宽认知。""";

    /** QaAgent 工具强制约束（PLAN §5.2：先调两工具再作答）。 */
    public static final String AGENT_TOOL_MANDATE = """
            【工具使用（强制）】在回答前，你必须先调用 search 与 knowledge 两个工具取得资料：
            search 获取本轮实时网络搜索结果，knowledge 检索用户沉淀笔记。
            两个工具都调用完成并取得资料后，再依据资料作答。""";

    /** 守卫重试时拼接在系统提示后的强化文案（PLAN §5.2）。 */
    public static final String GUARD_RETRY_APPEND = """

            （重要提醒：你上一轮没有调用全部两个工具就开始了作答，这违反了规则。请重新开始：先调用 search 工具获取网络搜索结果，再调用 knowledge 工具检索沉淀笔记，两个工具都调用完成并取得资料后，再依据资料作答。）""";

    /** 阶段二 TAG_SYSTEM 原文。 */
    public static final String TAG_SYSTEM = """
            你是标签生成器。根据用户的提问与回答，提炼 3-5 个中文标签。
            要求：每个标签 2-6 个汉字；覆盖主题关键词；只输出 JSON 数组（如 ["装饰器", "Python"]），不要输出其他内容。""";

    /** 引用兜底正则（阶段二 parse_tags：提取 "…"、「…」、'…' 内 1-20 字文本）。 */
    private static final Pattern QUOTE_RE = Pattern.compile("[\"「']([^\"」']{1,20})[\"」']");

    /** QaAgent 系统提示：工具强制约束 + ANSWER_SYSTEM。 */
    public static String agentSystem() {
        return AGENT_TOOL_MANDATE + "\n\n" + ANSWER_SYSTEM;
    }

    /** 守卫重试系统提示：agentSystem() + 强化文案。 */
    public static String retrySystem() {
        return agentSystem() + GUARD_RETRY_APPEND;
    }

    /** QaAgent 用户消息：历史节（若有）+ 用户问题。 */
    public static String buildAgentUserMessage(String question, String history) {
        List<String> parts = new ArrayList<>();
        if (history != null && !history.isBlank()) {
            parts.add("## 本轮之前的对话（仅供理解上下文，不要引用其中的编号）\n" + history);
        }
        parts.add("## 用户问题\n" + question);
        return String.join("\n\n", parts);
    }

    /** 兜底合成用户消息（阶段二 build_answer_messages 原文移植）。 */
    public static String buildFallbackUserMessage(String question, List<SearchSource> sources,
                                                  List<KnowledgeHit> knowledge, String history) {
        List<String> parts = new ArrayList<>();
        if (history != null && !history.isBlank()) {
            parts.add("## 本轮之前的对话（仅供理解上下文，不要引用其中的编号）\n" + history);
        }
        if (knowledge != null && !knowledge.isEmpty()) {
            parts.add("## 你的沉淀笔记（权威，优先采信）\n" + knowledge.stream()
                    .map(k -> "- 笔记" + k.id() + "：" + k.content())
                    .collect(Collectors.joining("\n\n")));
        }
        if (sources != null && !sources.isEmpty()) {
            parts.add("## 搜索结果\n" + java.util.stream.IntStream.range(0, sources.size())
                    .mapToObj(i -> {
                        SearchSource s = sources.get(i);
                        return "[" + (i + 1) + "] " + s.title() + "（" + s.url() + "）\n" + s.snippet();
                    })
                    .collect(Collectors.joining("\n")));
        } else {
            parts.add("（本轮联网搜索不可用，没有搜索结果。）");
        }
        parts.add("## 用户问题\n" + question);
        return String.join("\n\n", parts);
    }

    /** 历史上下文文本（阶段二 load_history）：每轮「用户：问题全文」+「助手：回答截断 1000 字」。 */
    public static String buildHistoryText(List<QaHistoryRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        return rows.stream()
                .flatMap(r -> Stream.of(
                        "用户：" + r.question,
                        "助手：" + truncate(r.answer == null ? "" : r.answer, ANSWER_TRUNCATE)))
                .collect(Collectors.joining("\n"));
    }

    /** 标签生成用户消息（阶段二 build_tag_messages；回答截断 2000 字）。 */
    public static String buildTagUserMessage(String question, String answer) {
        return "问题：" + question + "\n\n回答：" + truncate(answer == null ? "" : answer, TAG_ANSWER_TRUNCATE);
    }

    /**
     * 标签解析（阶段二 parse_tags 移植）：
     * ① JSON 数组解析成功且非空 → 取前 5 个（元素 strip，空丢弃）；
     * ② 否则（含解析出空数组）→ 引号正则兜底提取，上限 5。
     */
    public static List<String> parseTags(String text) {
        String t = text == null ? "" : text.strip();
        try {
            JsonNode data = JSON.readTree(t);
            if (data.isArray()) {
                List<String> tags = new ArrayList<>();
                for (JsonNode n : data) {
                    String s = n.asText().strip();
                    if (!s.isEmpty()) {
                        tags.add(s);
                    }
                }
                if (!tags.isEmpty()) {
                    return tags.subList(0, Math.min(tags.size(), 5));
                }
            }
        } catch (JsonProcessingException | RuntimeException e) {
            // 解析失败 → 引号正则兜底
        }
        List<String> tags = new ArrayList<>();
        Matcher m = QUOTE_RE.matcher(t);
        while (m.find() && tags.size() < 5) {
            tags.add(m.group(1));
        }
        return tags;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
