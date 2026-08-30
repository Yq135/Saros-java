package com.kairon.saros.mapper;

import com.kairon.saros.po.KnnHit;
import com.pgvector.PGvector;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * embeddings 表访问接口（SQL 实现见 src/main/resources/mapper/EmbeddingMapper.xml）。
 *
 * <p>source_type 当前仅 'MANUAL'（手打笔记）；KNN 走 {@code <=>}（cosine distance），
 * 与阶段二 SQL 语义一致。
 */
@Mapper
public interface EmbeddingMapper {

    int deleteEmbedding(@Param("userId") long userId, @Param("sourceId") long sourceId);

    int insertEmbedding(@Param("userId") long userId, @Param("sourceId") long sourceId,
                        @Param("content") String content, @Param("vector") PGvector vector);

    List<KnnHit> knnSearch(@Param("userId") long userId, @Param("vector") PGvector vector,
                           @Param("topK") int topK);
}
