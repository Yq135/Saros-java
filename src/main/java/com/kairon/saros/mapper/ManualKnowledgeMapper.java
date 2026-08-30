package com.kairon.saros.mapper;

import com.kairon.saros.po.ManualKnowledge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * manual_knowledge 表访问接口（SQL 实现见 src/main/resources/mapper/ManualKnowledgeMapper.xml）。
 *
 * <p>列表按 updated_at DESC, id DESC（同时间戳分页稳定），SQL 语义与阶段二对齐。
 */
@Mapper
public interface ManualKnowledgeMapper {

    int insertKnowledge(ManualKnowledge row);

    int updateKnowledge(ManualKnowledge row);

    int deleteKnowledge(@Param("id") long id, @Param("userId") long userId);

    ManualKnowledge findById(@Param("id") long id, @Param("userId") long userId);

    List<ManualKnowledge> findByIds(@Param("ids") List<Long> ids, @Param("userId") long userId);

    List<ManualKnowledge> listPage(@Param("userId") long userId, @Param("q") String q,
                                   @Param("qPattern") String qPattern, @Param("tag") String tag,
                                   @Param("mastery") Integer mastery,
                                   @Param("limit") int limit, @Param("offset") int offset);

    long countFiltered(@Param("userId") long userId, @Param("q") String q,
                       @Param("qPattern") String qPattern, @Param("tag") String tag,
                       @Param("mastery") Integer mastery);
}
