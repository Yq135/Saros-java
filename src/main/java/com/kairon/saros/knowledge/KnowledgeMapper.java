package com.kairon.saros.knowledge;

import com.pgvector.PGvector;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 模块四数据访问（SQL 语义与阶段二 vector_store.py / knowledge.py 对齐）。
 *
 * <p>标签按 tags.id 升序（对应阶段二 array_agg ORDER BY t.id）；列表按
 * updated_at DESC, id DESC（同时间戳分页稳定）。
 */
@Mapper
public interface KnowledgeMapper {

    // ---- manual_knowledge ----

    @Insert("INSERT INTO manual_knowledge (user_id, content, mastery_level) VALUES (#{userId}, #{content}, #{masteryLevel})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertKnowledge(KnowledgeRow row);

    @Update("UPDATE manual_knowledge SET content = #{content}, mastery_level = #{masteryLevel} "
            + "WHERE id = #{id} AND user_id = #{userId}")
    int updateKnowledge(KnowledgeRow row);

    @Delete("DELETE FROM manual_knowledge WHERE id = #{id} AND user_id = #{userId}")
    int deleteKnowledge(@Param("id") long id, @Param("userId") long userId);

    @Select("SELECT id, content, mastery_level, created_at, updated_at FROM manual_knowledge "
            + "WHERE id = #{id} AND user_id = #{userId}")
    KnowledgeRow findById(@Param("id") long id, @Param("userId") long userId);

    @Select("<script>"
            + "SELECT id, content, mastery_level, created_at, updated_at FROM manual_knowledge "
            + "WHERE user_id = #{userId} AND id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<KnowledgeRow> findByIds(@Param("ids") List<Long> ids, @Param("userId") long userId);

    @Select("<script>"
            + "SELECT id, content, mastery_level, created_at, updated_at FROM manual_knowledge "
            + "WHERE user_id = #{userId}"
            + "<if test='q != null and q != \"\"'> AND content ILIKE #{qPattern}</if>"
            + "<if test='tag != null and tag != \"\"'> AND EXISTS (SELECT 1 FROM tags t2 "
            + "  WHERE t2.manual_knowledge_id = manual_knowledge.id AND t2.name = #{tag})</if>"
            + "<if test='mastery != null'> AND mastery_level = #{mastery}</if>"
            + " ORDER BY updated_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<KnowledgeRow> listPage(@Param("userId") long userId, @Param("q") String q,
                                @Param("qPattern") String qPattern, @Param("tag") String tag,
                                @Param("mastery") Integer mastery,
                                @Param("limit") int limit, @Param("offset") int offset);

    @Select("<script>"
            + "SELECT COUNT(*) FROM manual_knowledge WHERE user_id = #{userId}"
            + "<if test='q != null and q != \"\"'> AND content ILIKE #{qPattern}</if>"
            + "<if test='tag != null and tag != \"\"'> AND EXISTS (SELECT 1 FROM tags t2 "
            + "  WHERE t2.manual_knowledge_id = manual_knowledge.id AND t2.name = #{tag})</if>"
            + "<if test='mastery != null'> AND mastery_level = #{mastery}</if>"
            + "</script>")
    long countFiltered(@Param("userId") long userId, @Param("q") String q,
                       @Param("qPattern") String qPattern, @Param("tag") String tag,
                       @Param("mastery") Integer mastery);

    // ---- tags ----

    @Delete("DELETE FROM tags WHERE manual_knowledge_id = #{kid}")
    int deleteTags(@Param("kid") long kid);

    @Insert("INSERT INTO tags (manual_knowledge_id, name) VALUES (#{kid}, #{name})")
    int insertTag(@Param("kid") long kid, @Param("name") String name);

    @Select("<script>"
            + "SELECT manual_knowledge_id AS kid, name FROM tags WHERE manual_knowledge_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + " ORDER BY id"
            + "</script>")
    List<TagRow> findTagsByKnowledgeIds(@Param("ids") List<Long> ids);

    @Select("SELECT DISTINCT t.name FROM tags t JOIN manual_knowledge mk ON mk.id = t.manual_knowledge_id "
            + "WHERE mk.user_id = #{userId} AND t.name ILIKE #{pattern} ORDER BY t.name LIMIT 20")
    List<String> suggestTags(@Param("userId") long userId, @Param("pattern") String pattern);

    // ---- embeddings（source_type 当前仅 'MANUAL'） ----

    @Delete("DELETE FROM embeddings WHERE user_id = #{userId} AND source_type = 'MANUAL' AND source_id = #{sourceId}")
    int deleteEmbedding(@Param("userId") long userId, @Param("sourceId") long sourceId);

    @Insert("INSERT INTO embeddings (user_id, source_type, source_id, chunk_content, embedding) "
            + "VALUES (#{userId}, 'MANUAL', #{sourceId}, #{content}, #{vector})")
    int insertEmbedding(@Param("userId") long userId, @Param("sourceId") long sourceId,
                        @Param("content") String content, @Param("vector") PGvector vector);

    @Select("SELECT source_id, chunk_content, 1 - (embedding <=> #{vector}) AS similarity "
            + "FROM embeddings WHERE user_id = #{userId} AND source_type = 'MANUAL' "
            + "ORDER BY embedding <=> #{vector} LIMIT #{topK}")
    List<KnnRow> knnSearch(@Param("userId") long userId, @Param("vector") PGvector vector,
                           @Param("topK") int topK);

    // ---- 行模型 ----

    class KnowledgeRow {
        public long id;
        public long userId;
        public String content;
        public int masteryLevel;
        public OffsetDateTime createdAt;
        public OffsetDateTime updatedAt;
    }

    class TagRow {
        public long kid;
        public String name;
    }

    class KnnRow {
        public long sourceId;
        public String chunkContent;
        public double similarity;
    }
}
