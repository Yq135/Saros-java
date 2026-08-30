package com.kairon.saros.knowledge;

import com.kairon.saros.common.ApiException;
import com.kairon.saros.common.GlobalExceptionHandler.ValidationException;
import com.kairon.saros.common.UserService;
import com.kairon.saros.embed.EmbeddingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.kairon.saros.knowledge.KnowledgeDtos.CreateRequest;
import static com.kairon.saros.knowledge.KnowledgeDtos.Hit;
import static com.kairon.saros.knowledge.KnowledgeDtos.ListOut;
import static com.kairon.saros.knowledge.KnowledgeDtos.Out;
import static com.kairon.saros.knowledge.KnowledgeDtos.SearchOut;
import static com.kairon.saros.knowledge.KnowledgeDtos.SearchRequest;
import static com.kairon.saros.knowledge.KnowledgeDtos.UpdateRequest;

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

    private final KnowledgeMapper mapper;
    private final UserService userService;
    private final EmbeddingService embeddingService;

    public KnowledgeService(KnowledgeMapper mapper, UserService userService, EmbeddingService embeddingService) {
        this.mapper = mapper;
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

        KnowledgeMapper.KnowledgeRow row = new KnowledgeMapper.KnowledgeRow();
        row.userId = userId;
        row.content = req.content();
        row.masteryLevel = mastery;
        mapper.insertKnowledge(row);
        replaceTags(row.id, tags);
        mapper.deleteEmbedding(userId, row.id);
        mapper.insertEmbedding(userId, row.id, req.content(), embeddingService.toPgVector(vector));
        return fetchOut(row.id, userId);
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
        List<KnowledgeMapper.KnowledgeRow> rows = mapper.listPage(
                userId, emptyToNull(q), qPattern, emptyToNull(tag), mastery, pageSize, (page - 1) * pageSize);
        long total = mapper.countFiltered(userId, emptyToNull(q), qPattern, emptyToNull(tag), mastery);
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

        KnowledgeMapper.KnowledgeRow row = new KnowledgeMapper.KnowledgeRow();
        row.id = kid;
        row.userId = userId;
        row.content = req.content();
        row.masteryLevel = mastery;
        if (mapper.updateKnowledge(row) == 0) {
            throw ApiException.notFound("知识点不存在");
        }
        replaceTags(kid, tags);
        mapper.deleteEmbedding(userId, kid);
        mapper.insertEmbedding(userId, kid, req.content(), embeddingService.toPgVector(vector));
        return fetchOut(kid, userId);
    }

    @Transactional
    public void delete(long kid) {
        long userId = userService.getUserId();
        mapper.deleteEmbedding(userId, kid);
        if (mapper.deleteKnowledge(kid, userId) == 0) {
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
        List<KnowledgeMapper.KnnRow> hits = mapper.knnSearch(
                userId, embeddingService.toPgVector(embeddingService.encodeQuery(query)), topK);
        if (hits.isEmpty()) {
            return new SearchOut(List.of());
        }
        Map<Long, Out> rows = fetchOutsByIds(hits.stream().map(h -> h.sourceId).toList(), userId);
        List<Hit> items = new ArrayList<>();
        for (KnowledgeMapper.KnnRow hit : hits) {
            Out row = rows.get(hit.sourceId);
            if (row == null) {
                continue; // 容错：向量行指向已删笔记则跳过（对齐阶段二）
            }
            items.add(new Hit(row.id(), row.content(), row.masteryLevel(), row.tags(),
                    row.createdAt(), row.updatedAt(), hit.similarity));
        }
        return new SearchOut(items);
    }

    public List<String> suggestTags(String q) {
        return mapper.suggestTags(userService.getUserId(), "%" + (q == null ? "" : q) + "%");
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
        mapper.deleteTags(kid);
        for (String t : tags) {
            mapper.insertTag(kid, t);
        }
    }

    private Out fetchOut(long kid, long userId) {
        KnowledgeMapper.KnowledgeRow row = mapper.findById(kid, userId);
        if (row == null) {
            throw ApiException.notFound("知识点不存在");
        }
        return toOut(row, tagsOf(kid, userId));
    }

    private List<Out> rowsToOut(List<KnowledgeMapper.KnowledgeRow> rows, long userId) {
        Map<Long, List<String>> tagsByKid = tagsOf(rows.stream().map(r -> r.id).toList(), userId);
        return rows.stream().map(r -> toOut(r, tagsByKid.getOrDefault(r.id, List.of()))).toList();
    }

    private Map<Long, Out> fetchOutsByIds(List<Long> ids, long userId) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> tagsByKid = tagsOf(ids, userId);
        Map<Long, Out> out = new LinkedHashMap<>();
        for (KnowledgeMapper.KnowledgeRow row : mapper.findByIds(ids, userId)) {
            out.put(row.id, toOut(row, tagsByKid.getOrDefault(row.id, List.of())));
        }
        return out;
    }

    private Map<Long, List<String>> tagsOf(List<Long> kids, long userId) {
        if (kids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> map = new LinkedHashMap<>();
        for (KnowledgeMapper.TagRow tag : mapper.findTagsByKnowledgeIds(kids)) {
            map.computeIfAbsent(tag.kid, k -> new ArrayList<>()).add(tag.name);
        }
        return map;
    }

    private List<String> tagsOf(long kid, long userId) {
        List<String> tags = mapper.findTagsByKnowledgeIds(List.of(kid)).stream()
                .map(t -> t.name)
                .toList();
        return tags;
    }

    private Out toOut(KnowledgeMapper.KnowledgeRow row, List<String> tags) {
        return new Out(row.id, row.content, row.masteryLevel, tags, row.createdAt, row.updatedAt);
    }

    private String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
