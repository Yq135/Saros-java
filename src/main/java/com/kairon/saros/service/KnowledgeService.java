package com.kairon.saros.service;

import com.kairon.saros.common.ApiException;
import com.kairon.saros.common.GlobalExceptionHandler.ValidationException;
import com.kairon.saros.embed.EmbeddingService;
import com.kairon.saros.mapper.EmbeddingMapper;
import com.kairon.saros.mapper.ManualKnowledgeMapper;
import com.kairon.saros.mapper.TagMapper;
import com.kairon.saros.po.KnnHit;
import com.kairon.saros.po.ManualKnowledge;
import com.kairon.saros.po.Tag;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.kairon.saros.dto.KnowledgeDtos.CreateRequest;
import static com.kairon.saros.dto.KnowledgeDtos.Hit;
import static com.kairon.saros.dto.KnowledgeDtos.ListOut;
import static com.kairon.saros.dto.KnowledgeDtos.Out;
import static com.kairon.saros.dto.KnowledgeDtos.SearchOut;
import static com.kairon.saros.dto.KnowledgeDtos.SearchRequest;
import static com.kairon.saros.dto.KnowledgeDtos.UpdateRequest;

/**
 * 模块四服务：手打知识 CRUD + 标签 + 语义查询（小RAG，纯检索）。
 *
 * <p>行为对齐阶段二 knowledge.py：标签清洗（去空白/去重/截断 100）、先嵌入后写库
 * （嵌入失败不产生脏数据）、写知识+标签+向量单事务、语义查询 KNN 后回查补全字段。
 */
@Service
public class KnowledgeService {

    private static final int MAX_TAG_LEN = 100;
    private static final int MAX_QUERY_LEN = 2000;

    private final ManualKnowledgeMapper manualKnowledgeMapper;
    private final TagMapper tagMapper;
    private final EmbeddingMapper embeddingMapper;
    private final UserService userService;
    private final EmbeddingService embeddingService;

    public KnowledgeService(ManualKnowledgeMapper manualKnowledgeMapper, TagMapper tagMapper,
                            EmbeddingMapper embeddingMapper, UserService userService,
                            EmbeddingService embeddingService) {
        this.manualKnowledgeMapper = manualKnowledgeMapper;
        this.tagMapper = tagMapper;
        this.embeddingMapper = embeddingMapper;
        this.userService = userService;
        this.embeddingService = embeddingService;
    }

    @Transactional
    public Out create(CreateRequest req) {
        validateContent(req.content());
        long userId = userService.getUserId();
        int mastery = normalizeMastery(req.masteryLevel());
        List<String> tags = cleanTags(req.tags());
        // 先嵌入（本地 CPU 推理），失败不写任何数据
        float[] vector = embeddingService.encodeText(req.content());

        ManualKnowledge row = new ManualKnowledge();
        row.setUserId(userId);
        row.setContent(req.content());
        row.setMasteryLevel(mastery);
        manualKnowledgeMapper.insertKnowledge(row);
        replaceTags(row.getId(), tags);
        embeddingMapper.deleteEmbedding(userId, row.getId());
        embeddingMapper.insertEmbedding(userId, row.getId(), req.content(), embeddingService.toPgVector(vector));
        return fetchOut(row.getId(), userId);
    }

    public ListOut list(String q, String tag, Integer mastery, int page, int pageSize) {
        if (page < 1) {
            throw new ValidationException("page", "ensure this value is greater than or equal to 1");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new ValidationException("page_size", "ensure this value is between 1 and 100");
        }
        long userId = userService.getUserId();
        String qPattern = (q == null || q.isEmpty()) ? null : "%" + q + "%";
        List<ManualKnowledge> rows = manualKnowledgeMapper.listPage(
                userId, emptyToNull(q), qPattern, emptyToNull(tag), mastery, pageSize, (page - 1) * pageSize);
        long total = manualKnowledgeMapper.countFiltered(userId, emptyToNull(q), qPattern, emptyToNull(tag), mastery);
        return new ListOut(rowsToOut(rows, userId), total, page, pageSize);
    }

    public Out get(long kid) {
        return fetchOut(kid, userService.getUserId());
    }

    @Transactional
    public Out update(long kid, UpdateRequest req) {
        validateContent(req.content());
        long userId = userService.getUserId();
        int mastery = normalizeMastery(req.masteryLevel());
        List<String> tags = cleanTags(req.tags());
        float[] vector = embeddingService.encodeText(req.content());

        ManualKnowledge row = new ManualKnowledge();
        row.setId(kid);
        row.setUserId(userId);
        row.setContent(req.content());
        row.setMasteryLevel(mastery);
        if (manualKnowledgeMapper.updateKnowledge(row) == 0) {
            throw ApiException.notFound("知识点不存在");
        }
        replaceTags(kid, tags);
        embeddingMapper.deleteEmbedding(userId, kid);
        embeddingMapper.insertEmbedding(userId, kid, req.content(), embeddingService.toPgVector(vector));
        return fetchOut(kid, userId);
    }

    @Transactional
    public void delete(long kid) {
        long userId = userService.getUserId();
        embeddingMapper.deleteEmbedding(userId, kid);
        if (manualKnowledgeMapper.deleteKnowledge(kid, userId) == 0) {
            throw ApiException.notFound("知识点不存在");
        }
    }

    public SearchOut semanticSearch(SearchRequest req) {
        String query = req.query().strip();
        if (query.isEmpty()) {
            throw ApiException.badRequest("查询内容不能为空");
        }
        if (query.length() > MAX_QUERY_LEN) {
            throw new ValidationException("query", "ensure this value has at most 2000 characters");
        }
        int topK = req.topK() == null ? 10 : req.topK();
        if (topK < 1 || topK > 50) {
            throw new ValidationException("top_k", "ensure this value is between 1 and 50");
        }
        long userId = userService.getUserId();
        List<KnnHit> hits = embeddingMapper.knnSearch(
                userId, embeddingService.toPgVector(embeddingService.encodeQuery(query)), topK);
        if (hits.isEmpty()) {
            return new SearchOut(List.of());
        }
        Map<Long, Out> rows = fetchOutsByIds(hits.stream().map(h -> h.getSourceId()).toList(), userId);
        List<Hit> items = new ArrayList<>();
        for (KnnHit hit : hits) {
            Out row = rows.get(hit.getSourceId());
            if (row == null) {
                continue; // 容错：向量行指向已删笔记则跳过（对齐阶段二）
            }
            items.add(new Hit(row.id(), row.content(), row.masteryLevel(), row.tags(),
                    row.createdAt(), row.updatedAt(), hit.getSimilarity()));
        }
        return new SearchOut(items);
    }

    public List<String> suggestTags(String q) {
        return tagMapper.suggestTags(userService.getUserId(), "%" + (q == null ? "" : q) + "%");
    }

    // ---- 内部工具 ----

    /** 标签清洗：去空白、去重、截断 100，保持输入顺序（对齐阶段二 _clean_tags）。 */
    private List<String> cleanTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String t : tags) {
            String cleaned = (t == null ? "" : t.strip());
            if (cleaned.length() > MAX_TAG_LEN) {
                cleaned = cleaned.substring(0, MAX_TAG_LEN);
            }
            if (!cleaned.isEmpty() && seen.add(cleaned)) {
                out.add(cleaned);
            }
        }
        return out;
    }

    /** content 空串 → 422（对齐 FastAPI minLength=1）；纯空格放行（minLength 只数长度）。 */
    private void validateContent(String content) {
        if (content == null || content.isEmpty()) {
            throw new ValidationException("content", "ensure this value has at least 1 characters");
        }
    }

    private int normalizeMastery(Integer mastery) {
        if (mastery == null) {
            return 0;
        }
        if (mastery < 0 || mastery > 5) {
            throw new ValidationException("mastery_level", "ensure this value is between 0 and 5");
        }
        return mastery;
    }

    private void replaceTags(long kid, List<String> tags) {
        tagMapper.deleteTags(kid);
        for (String t : tags) {
            tagMapper.insertTag(kid, t);
        }
    }

    private Out fetchOut(long kid, long userId) {
        ManualKnowledge row = manualKnowledgeMapper.findById(kid, userId);
        if (row == null) {
            throw ApiException.notFound("知识点不存在");
        }
        return toOut(row, tagsOf(kid, userId));
    }

    private List<Out> rowsToOut(List<ManualKnowledge> rows, long userId) {
        Map<Long, List<String>> tagsByKid = tagsOf(rows.stream().map(ManualKnowledge::getId).toList(), userId);
        return rows.stream().map(r -> toOut(r, tagsByKid.getOrDefault(r.getId(), List.of()))).toList();
    }

    private Map<Long, Out> fetchOutsByIds(List<Long> ids, long userId) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> tagsByKid = tagsOf(ids, userId);
        Map<Long, Out> out = new LinkedHashMap<>();
        for (ManualKnowledge row : manualKnowledgeMapper.findByIds(ids, userId)) {
            out.put(row.getId(), toOut(row, tagsByKid.getOrDefault(row.getId(), List.of())));
        }
        return out;
    }

    private Map<Long, List<String>> tagsOf(List<Long> kids, long userId) {
        if (kids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> map = new LinkedHashMap<>();
        for (Tag tag : tagMapper.findTagsByKnowledgeIds(kids)) {
            map.computeIfAbsent(tag.getManualKnowledgeId(), k -> new ArrayList<>()).add(tag.getName());
        }
        return map;
    }

    private List<String> tagsOf(long kid, long userId) {
        List<String> tags = tagMapper.findTagsByKnowledgeIds(List.of(kid)).stream()
                .map(Tag::getName)
                .toList();
        return tags;
    }

    private Out toOut(ManualKnowledge row, List<String> tags) {
        return new Out(row.getId(), row.getContent(), row.getMasteryLevel(), tags,
                row.getCreatedAt(), row.getUpdatedAt());
    }

    private String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
